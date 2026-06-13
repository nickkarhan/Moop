package com.noop.ui

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// MARK: - ScoringGuideScreen (ported from Strand/Screens/ScoringGuideView.swift)
//
// "How your scores work" — the one honest explainer for NOOP's three daily scores
// (Charge, Effort, Rest) and the confidence labels. Presented as a sheet, mirroring
// WhatsNewSheet's presentation + dismiss + layout idiom: a fixed header with a close
// button, a scrollable column of cards, and a "Got it" footer. Reachable from
// Settings → About, the ⓘ on each Today score, and the one-time first-run card.
//
// All copy here is the single approved source of truth, shared verbatim across
// macOS / iOS / Android. Each score section is tinted with the SAME accent the rest
// of the app uses for that metric (Charge = recovery green, Effort = strain rose,
// Rest = sleep purple), so a glance maps a section to its tile.

/**
 * The three score sections the guide can deep-link to. Case names mirror the macOS/iOS
 * `ScoreSection` exactly (charge/effort/rest) so the three platforms stay in lockstep; the
 * Android enum keeps Kotlin's UPPER_SNAKE convention. Used as the scroll anchor key by the
 * ⓘ affordances on the Today screen so each opens at its own score.
 */
enum class ScoreSection {
    CHARGE,
    EFFORT,
    REST;

    /** The accent each section uses — matched to the Today tile / ring for continuity. */
    val accent: Color
        get() = when (this) {
            CHARGE -> Palette.accent        // recovery ring spark
            EFFORT -> Palette.strain066     // strain spark
            REST -> Palette.metricPurple    // sleep spark
        }

    /** The header glyph (heart/spark · flame · moon). */
    val icon: ImageVector
        get() = when (this) {
            CHARGE -> Icons.Filled.Favorite
            EFFORT -> Icons.Filled.Whatshot
            REST -> Icons.Filled.Bedtime
        }

    val label: String
        get() = when (this) {
            CHARGE -> "Charge"
            EFFORT -> "Effort"
            REST -> "Rest"
        }
}

/**
 * One-time first-run flag for the Today "New here?" scoring-guide card. Plain-prefs persistence
 * mirroring [DonationNudgePrefs] — a tiny self-contained store, so the card's seen-state lives next
 * to the screen that owns it and never touches the unrelated onboarding/changelog prefs.
 */
object ScoringGuidePrefs {
    private const val FILE = "noop_scoring_guide_prefs"
    private const val KEY_CARD_SEEN = "scoringGuideCardSeen"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether the one-time first-run card has been seen/dismissed (default false = show it once). */
    fun cardSeen(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CARD_SEEN, false)

    /** Set once the user opens the guide from the card OR dismisses it — either way it never returns. */
    fun setCardSeen(ctx: Context) =
        prefs(ctx).edit().putBoolean(KEY_CARD_SEEN, true).apply()
}

/**
 * The scoring-guide sheet. [initialSection], when set, scrolls to and briefly highlights that
 * score's card on appear — the deep-link used by the Today ⓘ affordances. [onClose] dismisses.
 */
@Composable
fun ScoringGuideScreen(
    onClose: () -> Unit,
    initialSection: ScoreSection? = null,
) {
    val scroll = rememberScrollState()
    // Section → its Y offset within the scroll column, captured as each card lays out, so the
    // deep-link can animate-scroll to it. Plain scroll positions (not the experimental
    // BringIntoViewRequester) keep this on stable foundation APIs.
    val anchors = remember { mutableStateMapOf<ScoreSection, Int>() }
    var highlighted by remember { mutableStateOf<ScoreSection?>(null) }
    // Root-space top of the scroll viewport, captured once at first layout (scroll == 0) so each
    // card's captured root-Y can be converted to a content offset for animateScrollTo.
    var viewportTop by remember { mutableStateOf<Int?>(null) }

    // Deep-link: once the target card has measured, scroll to it and pulse its accent ring, then
    // fade it. Keyed on the anchor becoming available so it fires after layout, not before.
    val targetY = initialSection?.let { anchors[it] }
    LaunchedEffect(initialSection, targetY) {
        val section = initialSection ?: return@LaunchedEffect
        val y = anchors[section] ?: return@LaunchedEffect
        delay(50)
        scroll.animateScrollTo((y - (viewportTop ?: 0)).coerceAtLeast(0))
        highlighted = section
        delay(1600)
        if (highlighted == section) highlighted = null
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Palette.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(onClose = onClose)
            Hairline()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        if (viewportTop == null) viewportTop = it.positionInRoot().y.toInt()
                    }
                    .verticalScroll(scroll)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap),
            ) {
                IntroCard()
                ScoreCard(
                    section = ScoreSection.CHARGE,
                    headline = "Charge — how recovered are you?",
                    body = "Led by your heart-rate variability (HRV) measured against your own " +
                        "personal baseline, plus resting heart rate, last night's Rest, breathing " +
                        "rate, and a skin-temperature signal (an early illness or overreach flag). " +
                        "Higher HRV versus your baseline means more Charge. NOOP needs a few nights " +
                        "to learn your baseline first — until then you'll see “Calibrating”.",
                    vsWhoop = "Same core idea as WHOOP's Recovery % (HRV-led recovery), but our " +
                        "weighting and baseline maths are our own, and openly documented.",
                    highlighted = highlighted == ScoreSection.CHARGE,
                    onPositioned = { if (ScoreSection.CHARGE !in anchors) anchors[ScoreSection.CHARGE] = it },
                )
                ScoreCard(
                    section = ScoreSection.EFFORT,
                    headline = "Effort — how hard did your heart work?",
                    body = "Your cardiovascular load. NOOP turns every second of heart rate into a " +
                        "training-impulse using heart-rate-reserve zones (Karvonen), weights time in " +
                        "harder zones more heavily (Edwards / Banister), and places it on a " +
                        "logarithmic 0–100 scale — so easy days sit low and an all-out day approaches " +
                        "100, which stays genuinely rare. A long walk with little cardio still counts, " +
                        "through a steps / active-energy floor.",
                    vsWhoop = "Same cardiovascular-load idea as WHOOP's Day Strain (0–21). We " +
                        "rescaled the top of the ladder from 21 to 100 so all three scores share one " +
                        "scale — the rungs didn't move, so a 100 is as rare as a 21.0 was.",
                    highlighted = highlighted == ScoreSection.EFFORT,
                    onPositioned = { if (ScoreSection.EFFORT !in anchors) anchors[ScoreSection.EFFORT] = it },
                )
                ScoreCard(
                    section = ScoreSection.REST,
                    headline = "Rest — how restorative was your sleep?",
                    body = "A blend of how long you slept versus your personal need (the biggest " +
                        "factor), how efficiently (asleep versus in bed), how much was restorative " +
                        "(deep + REM sleep), and how consistent your sleep and wake timing is.",
                    vsWhoop = "Similar in spirit to WHOOP's Sleep Performance %; our composite is our own.",
                    highlighted = highlighted == ScoreSection.REST,
                    onPositioned = { if (ScoreSection.REST !in anchors) anchors[ScoreSection.REST] = it },
                )
                ConfidenceCard()
                FooterNote()
            }

            Hairline()
            Footer(onClose = onClose)
        }
    }
}

// MARK: - Header ("How your scores work" + "Charge · Effort · Rest" + close X)

@Composable
private fun Header(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("How your scores work", style = NoopType.title2, color = Palette.textPrimary)
            Text(
                "Charge · Effort · Rest",
                style = NoopType.caption,
                color = Palette.textTertiary,
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close",
                tint = Palette.textTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// MARK: - Intro card (THE THREE SCORES + paragraph + accent legend)

@Composable
private fun IntroCard() {
    NoopCard(padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Overline("The three scores")
            Text(
                "NOOP gives you three daily scores — Charge, Effort and Rest — each on a 0–100 " +
                    "scale. They're built from your strap's raw signals using published, " +
                    "peer-reviewed sport science, and computed entirely on your device. They are " +
                    "NOT WHOOP's scores: we don't have WHOOP's private algorithms and don't pretend " +
                    "to. They aim at the same three questions using open science, so they'll usually " +
                    "track WHOOP's in direction, but won't match number-for-number — and that's the " +
                    "point.",
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(ScoreSection.CHARGE)
                LegendDot(ScoreSection.EFFORT)
                LegendDot(ScoreSection.REST)
            }
        }
    }
}

@Composable
private fun LegendDot(section: ScoreSection) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(section.accent),
        )
        Text(section.label, style = NoopType.caption, color = Palette.textSecondary)
    }
}

// MARK: - Score card (accent strip + tinted icon/headline + body + "vs WHOOP" line)

@Composable
private fun ScoreCard(
    section: ScoreSection,
    headline: String,
    body: String,
    vsWhoop: String,
    highlighted: Boolean,
    onPositioned: (Int) -> Unit,
) {
    // Deep-link highlight: a brief accent ring when arrived at via an ⓘ, fading back to the hairline.
    val ringColor by animateColorAsState(
        targetValue = if (highlighted) section.accent else Color.Transparent,
        label = "scoreCardHighlight",
    )
    val shape = RoundedCornerShape(Metrics.cornerSm)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onPositioned(it.positionInRoot().y.toInt()) }
            .clip(shape)
            .background(Palette.surfaceRaised)
            .border(2.dp, if (highlighted) ringColor else Palette.hairline, shape)
            .padding(20.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // A left accent strip ties the card to its score colour without a hard fill.
            Box(
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(section.accent.copy(alpha = 0.9f)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        section.icon,
                        contentDescription = null,
                        tint = section.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(headline, style = NoopType.headline, color = Palette.textPrimary)
                }
                Text(body, style = NoopType.subhead, color = Palette.textSecondary)
                Hairline()
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "VS WHOOP",
                        style = NoopType.overline,
                        color = section.accent,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                    Text(
                        vsWhoop,
                        style = NoopType.footnote.copy(fontStyle = FontStyle.Italic),
                        color = Palette.textTertiary,
                    )
                }
            }
        }
    }
}

// MARK: - Confidence card (Solid / Building / Calibrating pills + explainer)

@Composable
private fun ConfidenceCard() {
    NoopCard(padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "How sure is NOOP?  ·  Solid · Building · Calibrating",
                style = NoopType.headline,
                color = Palette.textPrimary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatePill("Solid", tone = StrandTone.Positive, showsDot = true)
                StatePill("Building", tone = StrandTone.Warning, showsDot = true)
                StatePill("Calibrating", tone = StrandTone.Neutral, showsDot = true)
            }
            Text(
                "Every score carries a small honesty label. Calibrating means NOOP is still " +
                    "learning your baseline, or doesn't have enough data yet. Building means there's " +
                    "enough to show, but it's thin. Solid means full inputs are present. When NOOP " +
                    "can't compute a score honestly, it shows nothing rather than a fake number.",
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
        }
    }
}

// MARK: - Footer note (muted disclaimer) + footer bar ("Got it")

@Composable
private fun FooterNote() {
    Text(
        "These are independent approximations from a consumer strap, built on open science — not " +
            "medical advice, and not WHOOP's official scores.",
        style = NoopType.footnote,
        color = Palette.textTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    )
}

@Composable
private fun Footer(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Button(
            onClick = onClose,
            colors = ButtonDefaults.buttonColors(
                containerColor = Palette.accent,
                contentColor = Palette.surfaceBase,
            ),
        ) {
            Text("Got it", style = NoopType.captionNumber)
        }
    }
}

// MARK: - Hairline divider (mirrors WhatsNewSheet's Hairline)

@Composable
private fun Hairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Palette.hairline),
    )
}
