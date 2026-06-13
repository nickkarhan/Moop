package com.noop.ai

import android.content.Context
import com.noop.data.DailyMetric
import com.noop.data.WhoopRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The AI Coach.
 *
 * Privacy posture: this is the ONE networked feature in the app. Nothing leaves the device
 * until the user has saved their own API key (see [AiKeyStore]) and asked a question. Only a
 * compact plain-text summary of their metrics plus their question is sent to the provider the
 * user picked. No raw samples, no identifiers.
 *
 * Anonymous: the only branding is the provider name the user selected. The system prompt does
 * not name any app author or model vendor.
 */
class AiCoach(private val repo: WhoopRepository) {

    /** The device key the rest of the app reads/writes daily metrics under. Coach reads go
     *  through the MERGED raw+computed view ([WhoopRepository.daysMerged]) — the same per-field
     *  coalesce every screen uses — so on-device "-noop" scores are visible too (#124). */
    private val deviceId = "my-whoop"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Send the conversation to [provider] using [model] and return the assistant reply text.
     *
     * Builds the data context from the user's cached daily metrics and prepends it to the
     * FIRST user turn so the model is grounded in real numbers. The system prompt is passed
     * out-of-band (top-level field for Anthropic, a system message for OpenAI).
     *
     * Runs on [Dispatchers.IO]. Throws a clear [Exception] on any failure (missing key, bad
     * key, network, rate limit, malformed response); the ViewModel maps that to a visible
     * error message and the app never crashes.
     */
    suspend fun chat(
        ctx: Context,
        history: List<ChatMsg>,
        provider: AiProvider,
        model: String,
        consent: Boolean = false,
        customBaseUrl: String = "",
    ): String = withContext(Dispatchers.IO) {
        // Local (Custom) servers usually need no key; the cloud providers always do.
        val key = AiKeyStore.read(ctx)
        if (key == null && provider != AiProvider.CUSTOM) {
            throw Exception("No API key set. Add your ${provider.displayName} key to use the coach.")
        }
        if (provider == AiProvider.CUSTOM) {
            require(customBaseUrl.isNotBlank()) { "Set your server URL first." }
            require(model.isNotBlank()) { "Pick a model your server serves." }
        }

        require(history.isNotEmpty()) { "Ask a question first." }
        require(history.last().role == "user") { "The last message must be your question." }

        // Include the user's data ONLY with explicit consent; otherwise a note, never their numbers.
        val grounded = if (consent) {
            // Merged read, NOT raw days(): a live-strap user's scores live under "my-whoop-noop"
            // and a raw read misses them — the coach then claimed it had no data. (#124)
            val days = runCatching { repo.daysMerged(deviceId) }.getOrDefault(emptyList())
            injectContext(history, buildContext(days))
        } else {
            injectContext(history, NO_CONSENT_NOTE)
        }

        when (provider) {
            AiProvider.OPENAI ->
                callOpenAiCompatible(provider, provider.endpoint, model, key, grounded)
            AiProvider.ANTHROPIC ->
                callAnthropic(provider, model, key!!, grounded)
            AiProvider.CUSTOM ->
                callOpenAiCompatible(provider, customChatUrl(customBaseUrl), model, key, grounded)
        }
    }

    /**
     * Fetch the provider's live list of model ids, using the saved API key.
     *
     * Best-effort: GETs the provider's models endpoint and returns the ids it advertises.
     * On any failure (no key, network, bad key, malformed body) this returns an EMPTY list
     * rather than throwing — the caller simply keeps its curated/static list. The result is
     * filtered to the ids that make sense for chat (OpenAI: ids starting with "gpt" or "o";
     * Anthropic: all returned ids) and de-duplicated.
     *
     * Runs on [Dispatchers.IO].
     */
    suspend fun fetchModels(
        ctx: Context,
        provider: AiProvider,
        customBaseUrl: String = "",
    ): List<String> = withContext(Dispatchers.IO) {
        val key = AiKeyStore.read(ctx)
        // Cloud providers need a key to list models; a local Custom server usually doesn't.
        if (key == null && provider != AiProvider.CUSTOM) return@withContext emptyList()

        val url = when (provider) {
            AiProvider.CUSTOM -> {
                if (customBaseUrl.isBlank()) return@withContext emptyList()
                // Best-effort: a bad/public-cleartext URL just yields no model list here (the chat
                // path surfaces the precise guard error). Never throw out of fetchModels.
                runCatching { customModelsUrl(customBaseUrl) }.getOrNull() ?: return@withContext emptyList()
            }
            else -> provider.modelsEndpoint
        }

        val builder = Request.Builder().url(url).get()
        when (provider) {
            // key is non-null here: the early return above only spares the Custom provider.
            AiProvider.OPENAI -> builder.addHeader("Authorization", "Bearer ${key!!}")
            AiProvider.ANTHROPIC -> {
                builder.addHeader("x-api-key", key!!)
                builder.addHeader("anthropic-version", "2023-06-01")
            }
            AiProvider.CUSTOM -> if (!key.isNullOrBlank()) builder.addHeader("Authorization", "Bearer $key")
        }

        runCatching {
            val (code, text) = execute(builder.build())
            if (code !in 200..299) return@runCatching emptyList<String>()

            // OpenAI-shaped providers (incl. Custom) return {"data": [ { "id": "..." }, ... ]}.
            val data = JSONObject(text).optJSONArray("data") ?: return@runCatching emptyList<String>()
            val ids = ArrayList<String>(data.length())
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id")?.trim().orEmpty()
                if (id.isEmpty()) continue
                val keep = when (provider) {
                    AiProvider.OPENAI -> id.startsWith("gpt") || id.startsWith("o")
                    // Anthropic + a local server name models freely → keep all.
                    AiProvider.ANTHROPIC, AiProvider.CUSTOM -> true
                }
                if (keep) ids.add(id)
            }
            ids.distinct()
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------------------------------
    // Context builder
    // ---------------------------------------------------------------------------------------

    /**
     * Compact plain-text summary of the user's recent data: the last ~14 days of
     * charge / effort / rest-hours / HRV / resting-HR (where present), 30-day averages,
     * and a recent-workouts line derived from logged exercise counts and effort.
     *
     * Kept well under ~1500 tokens. If there is no data at all, says so explicitly so the
     * model doesn't invent numbers.
     */
    fun buildContext(days: List<DailyMetric>): String {
        if (days.isEmpty()) {
            return "USER DATA: No wearable data is available yet (no synced days). " +
                "Do not invent specific numbers; give general guidance and encourage the user " +
                "to sync their strap so future advice can reference their real metrics."
        }

        // daysMerged() returns oldest-first; take the most recent up to 30 for averages, 14 for the table.
        val last30 = days.takeLast(30)
        val last14 = days.takeLast(14)

        val sb = StringBuilder()
        sb.append("USER DATA (most recent first; figures rounded; a dash means not recorded that day).\n\n")

        // --- Recent daily table (newest first for readability) ---
        sb.append("Last ${last14.size} days:\n")
        for (d in last14.reversed()) {
            val recovery = d.recovery?.let { "${it.roundToInt()}%" } ?: "-"
            val strain = d.strain?.let { fmt1(it) } ?: "-"
            val sleepH = d.totalSleepMin?.let { fmt1(it / 60.0) + "h" } ?: "-"
            val hrv = d.avgHrv?.let { "${it.roundToInt()}ms" } ?: "-"
            val rhr = d.restingHr?.let { "${it}bpm" } ?: "-"
            sb.append(
                "  ${d.day}: charge $recovery, effort $strain, rest $sleepH, HRV $hrv, RHR $rhr\n"
            )
        }

        // --- 30-day averages ---
        sb.append("\n30-day averages (over ${last30.size} days):\n")
        sb.append("  charge ${avgInt(last30) { it.recovery }}%, ")
        sb.append("effort ${avg1(last30) { it.strain }}, ")
        sb.append("rest ${avg1(last30) { d -> d.totalSleepMin?.div(60.0) }}h, ")
        sb.append("HRV ${avgInt(last30) { it.avgHrv }}ms, ")
        sb.append("RHR ${avgInt(last30) { d -> d.restingHr?.toDouble() }}bpm\n")
        // Additional vitals when present (#124 — the coach used to see only recovery/strain/sleep/HRV/RHR).
        sb.append("  SpO₂ ${avgInt(last30) { it.spo2Pct }}%, ")
        sb.append("respiration ${avg1(last30) { it.respRateBpm }}/min, ")
        sb.append("skin-temp deviation ${avg1(last30) { it.skinTempDevC }}°C, ")
        sb.append("steps ${avgInt(last30) { d -> d.steps?.toDouble() }}/day, ")
        sb.append("active energy ${avgInt(last30) { it.activeKcalEst }}kcal/day\n")

        // --- Recent workouts (derived from logged exercise counts + day strain) ---
        val workoutDays = last14.filter { (it.exerciseCount ?: 0) > 0 }
        sb.append("\nRecent workouts (last ${last14.size} days):\n")
        if (workoutDays.isEmpty()) {
            sb.append("  None logged.\n")
        } else {
            for (d in workoutDays.reversed()) {
                val n = d.exerciseCount ?: 0
                val label = if (n == 1) "1 workout" else "$n workouts"
                val strain = d.strain?.let { ", effort ${fmt1(it)}" } ?: ""
                sb.append("  ${d.day}: $label$strain\n")
            }
        }

        // Latest snapshot line — handy single reference for the model.
        days.lastOrNull()?.let { latest ->
            val r = latest.recovery?.let { "${it.roundToInt()}%" } ?: "n/a"
            val s = latest.strain?.let { fmt1(it) } ?: "n/a"
            sb.append("\nMost recent day (${latest.day}): charge $r, effort $s.\n")
        }

        return sb.toString().trim()
    }

    /**
     * Prepend [context] to the first user message so the model is grounded in real numbers.
     * Returns a copy of [history]; the original list is not mutated.
     */
    private fun injectContext(history: List<ChatMsg>, context: String): List<ChatMsg> {
        val firstUserIdx = history.indexOfFirst { it.role == "user" }
        if (firstUserIdx < 0) return history
        return history.mapIndexed { i, m ->
            if (i == firstUserIdx) {
                m.copy(text = "$context\n\n---\n\nMy question: ${m.text}")
            } else m
        }
    }

    // ---------------------------------------------------------------------------------------
    // OpenAI-compatible — POST {base}/chat/completions
    //   Used for OpenAI itself and the Custom (local LLM) provider. [key] may be null/blank for a
    //   local server that needs no auth — the Authorization header is then omitted.
    // ---------------------------------------------------------------------------------------

    private fun callOpenAiCompatible(
        provider: AiProvider,
        url: String,
        model: String,
        key: String?,
        history: List<ChatMsg>,
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        for (m in history) {
            // OpenAI roles map 1:1 to "user"/"assistant".
            messages.put(JSONObject().put("role", m.role).put("content", m.text))
        }

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.6)
            .put("max_tokens", 900)
            .toString()

        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
        if (!key.isNullOrBlank()) builder.addHeader("Authorization", "Bearer $key")

        val (code, text) = execute(builder.build())
        if (code !in 200..299) throw httpError(provider, code, text)

        val json = parse(text)
        val content = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()

        if (content.isNullOrEmpty()) throw Exception("The provider returned an empty reply. Please try again.")
        return content
    }

    /** Base for the Custom provider — the user's URL with any trailing slashes trimmed. */
    private fun customBase(url: String): String = url.trim().trimEnd('/')

    private fun customChatUrl(url: String): String {
        val base = customBase(url)
        guardCustomUrl(base)
        return base + "/chat/completions"
    }

    private fun customModelsUrl(url: String): String {
        val base = customBase(url)
        guardCustomUrl(base)
        return base + "/models"
    }

    /**
     * Gatekeeper for the Custom (local LLM) provider. https:// is always fine. Plain http:// is
     * only allowed to a PRIVATE-NETWORK host — loopback, RFC1918, link-local, or *.local — because
     * the app's network-security-config permits cleartext app-wide (Android XML can't scope a
     * cleartext rule to a CIDR), so THIS check is what actually keeps cleartext off the public
     * internet (#187). A public http:// host is rejected with a precise, actionable error.
     */
    private fun guardCustomUrl(base: String) {
        val uri = runCatching { java.net.URI(base) }.getOrNull()
        val host = uri?.host
        val scheme = uri?.scheme?.lowercase()
        require(host != null && !scheme.isNullOrBlank()) {
            "That server URL isn't valid. Use http://<host>:<port> for a local server, or https://… for a remote one."
        }
        if (scheme == "https") return
        require(scheme == "http") {
            "Unsupported URL scheme \"$scheme\". Use http:// for a local server or https:// for a remote one."
        }
        require(isPrivateLanOrLoopback(host)) {
            "Plain http:// is only allowed to a local-network server (localhost, 10.x, 172.16–31.x, " +
                "192.168.x, 169.254.x, or a .local name). Use https:// to reach \"$host\"."
        }
    }

    /**
     * True when [host] is on the device's own machine or its private LAN, so plain http:// to it
     * never crosses the public internet: loopback (localhost / 127.0.0.0/8 / ::1), RFC1918
     * (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16), link-local (169.254.0.0/16 / fe80::/10), the
     * emulator host alias 10.0.2.2, and any *.local mDNS name. Anything else is treated as public.
     */
    private fun isPrivateLanOrLoopback(host: String): Boolean {
        val h = host.trim().trim('[', ']').lowercase()  // strip IPv6 brackets if present
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h == "::1") return true
        if (h.endsWith(".local")) return true            // mDNS / Bonjour LAN names
        if (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return true  // IPv6 link-local / ULA

        // IPv4 dotted-quad: validate and classify by RFC1918 / loopback / link-local.
        val parts = h.split(".")
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: -1 }
        if (octets.any { it < 0 || it > 255 }) return false
        val (a, b) = octets[0] to octets[1]
        return when {
            a == 127 -> true                              // 127.0.0.0/8 loopback
            a == 10 -> true                               // 10.0.0.0/8
            a == 172 && b in 16..31 -> true               // 172.16.0.0/12
            a == 192 && b == 168 -> true                  // 192.168.0.0/16
            a == 169 && b == 254 -> true                  // 169.254.0.0/16 link-local
            else -> false
        }
    }

    // ---------------------------------------------------------------------------------------
    // Anthropic — POST /v1/messages
    // ---------------------------------------------------------------------------------------

    private fun callAnthropic(
        provider: AiProvider,
        model: String,
        key: String,
        history: List<ChatMsg>,
    ): String {
        // Anthropic has no system role inside messages: the system prompt is a top-level field
        // and messages alternate user/assistant.
        val messages = JSONArray()
        for (m in history) {
            messages.put(JSONObject().put("role", m.role).put("content", m.text))
        }

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 900)
            .put("system", SYSTEM_PROMPT)
            .put("messages", messages)
            .toString()

        val request = Request.Builder()
            .url(provider.endpoint)
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()

        val (code, text) = execute(request)
        if (code !in 200..299) throw httpError(provider, code, text)

        val json = parse(text)
        val content = json.optJSONArray("content")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.trim()

        if (content.isNullOrEmpty()) throw Exception("The provider returned an empty reply. Please try again.")
        return content
    }

    // ---------------------------------------------------------------------------------------
    // HTTP / error plumbing
    // ---------------------------------------------------------------------------------------

    /** Execute a request, mapping low-level network failures to a friendly [Exception]. */
    private fun execute(request: Request): Pair<Int, String> {
        try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                return resp.code to text
            }
        } catch (e: java.net.UnknownHostException) {
            throw Exception("No internet connection. The coach needs a connection to reach the provider.")
        } catch (e: java.net.SocketTimeoutException) {
            throw Exception("The request timed out. Please check your connection and try again.")
        } catch (e: javax.net.ssl.SSLException) {
            throw Exception("A secure connection to the provider could not be established.")
        } catch (e: java.io.IOException) {
            // The platform reports a blocked plain-HTTP request as a generic IOException whose
            // message is "Cleartext HTTP traffic to <host> not permitted" (no dedicated exception
            // class exists). Detect it and explain, instead of the opaque generic line. This should
            // be unreachable now that cleartext is permitted app-wide and guardCustomUrl restricts
            // http:// to private hosts — but stays as a clear fallback if a policy re-blocks it.
            val msg = e.message.orEmpty()
            if (msg.contains("Cleartext", ignoreCase = true) && msg.contains("not permitted", ignoreCase = true)) {
                throw Exception(
                    "Plain http:// to a LAN address is blocked — update to the build that allows " +
                        "local-network servers, or use https://."
                )
            }
            throw Exception("Network error reaching the provider: ${e.message ?: "unknown"}.")
        }
    }

    /** Map a non-2xx response to a clear, user-facing message (key, rate-limit, server). */
    private fun httpError(provider: AiProvider, code: Int, body: String): Exception {
        val detail = extractApiErrorMessage(body)
        val base = when (code) {
            401, 403 -> "Your ${provider.displayName} API key was rejected. Check the key and try again."
            429 -> "${provider.displayName} rate limit reached (or quota exhausted). Wait a moment and retry."
            in 500..599 -> "${provider.displayName} had a server error (HTTP $code). Please try again shortly."
            400 -> "The request was rejected by ${provider.displayName} (HTTP 400)."
            else -> "${provider.displayName} returned an error (HTTP $code)."
        }
        return Exception(if (detail != null) "$base ($detail)" else base)
    }

    /** Pull the provider's error message out of an error JSON body, if present. */
    private fun extractApiErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val obj = JSONObject(body)
            // Both providers wrap errors as {"error": {"message": "..."}} (OpenAI) or
            // {"type":"error","error":{"message":"..."}} (Anthropic).
            obj.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** Parse a successful response body, turning malformed JSON into a clear error. */
    private fun parse(text: String): JSONObject =
        runCatching { JSONObject(text) }.getOrElse {
            throw Exception("Could not understand the provider's response.")
        }

    // ---------------------------------------------------------------------------------------
    // Small numeric formatting helpers
    // ---------------------------------------------------------------------------------------

    private fun fmt1(v: Double): String =
        if (v == v.roundToInt().toDouble()) v.roundToInt().toString()
        else String.format("%.1f", v)

    private inline fun avgInt(days: List<DailyMetric>, sel: (DailyMetric) -> Double?): String {
        val vals = days.mapNotNull(sel)
        return if (vals.isEmpty()) "-" else vals.average().roundToInt().toString()
    }

    private inline fun avg1(days: List<DailyMetric>, sel: (DailyMetric) -> Double?): String {
        val vals = days.mapNotNull(sel)
        return if (vals.isEmpty()) "-" else fmt1(vals.average())
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * The coach persona. Anonymous (names no app author or model vendor) and includes the
         * not-a-doctor guardrail.
         */
        const val SYSTEM_PROMPT =
            "You are an elite, supportive recovery and performance coach with a real training " +
                "methodology. You may be given a summary of the user's own wearable data (charge " +
                "0-100, effort 0-100, rest/sleep, HRV, resting heart rate) and recent workouts. " +
                "Charge is the daily recovery/readiness score; effort is the day's cardiovascular " +
                "load. Coach using autoregulation: charge 67-100 = green light to build/push, " +
                "higher effort is fine; 34-66 = maintain, quality over volume, keep it controlled; " +
                "0-33 = active recovery only (Zone 2, mobility, extra sleep) and protect against " +
                "accumulating effort debt. Optimise workouts with progressive overload, polarised ~80/20 " +
                "intensity, spacing hard sessions, deloads/periodisation, and treat sleep as the " +
                "biggest recovery lever. Always cite the user's ACTUAL numbers, give a concrete plan " +
                "(today and the week), and be specific, punchy and motivating. If no data is " +
                "provided, coach generally and invite them to enable data access. You are NOT a " +
                "doctor — never diagnose; suggest a professional for genuine health concerns. " +
                "Format replies in simple Markdown, chat-sized: short paragraphs, **bold** for key " +
                "numbers, bullet or numbered lists for plans, and ### headings only when structure " +
                "genuinely helps. No tables or code blocks."

        /** Used in place of the metrics context when the user has not granted data access. */
        const val NO_CONSENT_NOTE =
            "NOTE: The user has not granted access to their biometric data. Coach generally and " +
                "encourage them to enable \"Let the coach use my data\" for tailored guidance."
    }
}
