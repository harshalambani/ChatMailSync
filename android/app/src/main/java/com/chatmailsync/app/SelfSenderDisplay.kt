package com.chatmailsync.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The three things `src.android_api.get_self_sender()["source"]` can say, as
 * a type instead of a string compared in three places. Mirrors
 * `src/self_sender.py::describe()` — see that docstring for what puts the
 * app in each state.
 */
enum class SelfSenderSource { LEARNED, UNKNOWN, OVERRIDE }

/** Parses the raw `source` string the Python core returns. Anything not
 *  recognised falls to [SelfSenderSource.UNKNOWN] rather than throwing --
 *  the honest state to show if the two sides of the bridge ever disagree. */
fun selfSenderSourceOf(source: String?): SelfSenderSource = when (source) {
    "learned" -> SelfSenderSource.LEARNED
    "override" -> SelfSenderSource.OVERRIDE
    else -> SelfSenderSource.UNKNOWN
}

/**
 * Label and colour for wherever "Me" is shown -- the masthead row, and every
 * later screen that repeats it. One place, so a fourth spot never re-derives
 * this slightly differently from the other three.
 */
data class SelfSenderDisplay(val label: String, val color: Color)

/**
 * The colour half of [SelfSenderDisplay], on its own because a chip that only
 * needs the colour (the state pill on the Me screen, its own label already
 * fixed) would otherwise have to build a label it throws away.
 *
 * Fixed values, not MaterialTheme roles: this reads the same on the navy
 * masthead band and on a light screen surface, which no single theme colour
 * does in both places at once.
 *  - [SelfSenderSource.LEARNED] -- an off-white that reads as white on the
 *    navy band and as a muted grey on a light surface. Calm: the app worked
 *    this out on its own and it is happy with the answer.
 *  - [SelfSenderSource.UNKNOWN] -- the same red as [ConnectionStatus.FAILED]'s
 *    dot. Every bubble in every export is drawn on a guess until this is
 *    resolved, which is worth the same alarm colour as "can't reach the
 *    mailbox".
 *  - [SelfSenderSource.OVERRIDE] -- the same amber as [ConnectionStatus]'s
 *    "not yet verified" state. Not wrong, just a fact stated by hand rather
 *    than derived, and worth noticing as such.
 */
val SelfSenderSource.color: Color
    get() = when (this) {
        SelfSenderSource.LEARNED -> Color(0xFFD9D6CE)
        SelfSenderSource.UNKNOWN -> Color(0xFFF2B8B2)
        SelfSenderSource.OVERRIDE -> Color(0xFFE3B872)
    }

/**
 * Builds the full [SelfSenderDisplay] from the raw fields
 * `get_self_sender()`/`android_api.get_self_sender()` returns.
 *
 * The "Me: " prefix is deliberately only on the unknown state: once a name is
 * known (learned or overridden) it is confident enough to stand alone, and
 * repeating "Me: " in front of a proper name reads as the app hedging on
 * something it just said it knows.
 */
fun selfSenderDisplay(source: String?, name: String?): SelfSenderDisplay {
    val resolved = resolveSelfSenderSource(source, name)
    val label = when (resolved) {
        SelfSenderSource.LEARNED, SelfSenderSource.OVERRIDE -> name ?: ""
        SelfSenderSource.UNKNOWN -> "Me: not known yet"
    }
    return SelfSenderDisplay(label, resolved.color)
}

// A LEARNED/OVERRIDE source that names no one isn't actually a known state --
// shared by [selfSenderDisplay] and [selfSenderContentDescription] so both
// treat it as UNKNOWN, rather than one patching its own text and leaving the
// other mismatched.
private fun resolveSelfSenderSource(source: String?, name: String?): SelfSenderSource {
    val parsed = selfSenderSourceOf(source)
    return if (parsed != SelfSenderSource.UNKNOWN && name.isNullOrBlank()) {
        SelfSenderSource.UNKNOWN
    } else {
        parsed
    }
}

/**
 * The spoken counterpart of [selfSenderDisplay]: the label there carries its
 * state by colour alone (a bare name for LEARNED and OVERRIDE, distinguished
 * only by an off-white vs. an amber), which a screen reader can't voice and a
 * colour-blind reader can't see. This spells the state out in words instead,
 * e.g. "Your messages: Meera Iyer, worked out automatically",
 * "Your messages: Meera Iyer, set by you", or "Your messages: not known yet".
 *
 * Lives here rather than at each call site so the masthead row and the
 * Settings row never drift into saying this two different ways -- the same
 * reason [selfSenderDisplay] and its colour are single-sourced in this file.
 */
fun selfSenderContentDescription(source: String?, name: String?): String {
    val resolved = resolveSelfSenderSource(source, name)
    return when (resolved) {
        SelfSenderSource.LEARNED -> "Your messages: ${name ?: ""}, worked out automatically"
        SelfSenderSource.OVERRIDE -> "Your messages: ${name ?: ""}, set by you"
        SelfSenderSource.UNKNOWN -> "Your messages: not known yet"
    }
}

/**
 * The full strip shown on the chat detail screen: text, the trailing action
 * word, and the colours to paint it with.
 */
data class SelfSenderStripDisplay(
    val text: String,
    val actionWord: String,
    val background: Color,
    val textColor: Color,
)

/**
 * Fixed light-surface values, like [SelfSenderSource.color] above -- the chat
 * screen's strip sits on a light surface, never the navy masthead band, so
 * these don't need to double as both like that colour does. Each pairs a
 * background with a text colour chosen to read on it, taken straight from
 * the approved mockups rather than derived from a MaterialTheme role, so the
 * strip matches the mockup exactly regardless of theme.
 *  - [SelfSenderSource.LEARNED] -- neutral grey: a calm, resolved state.
 *  - [SelfSenderSource.OVERRIDE] -- amber: the same "stated by hand, not
 *    derived" note as [SelfSenderSource.color]'s amber.
 *  - [SelfSenderSource.UNKNOWN] -- red: the same alarm as [SelfSenderSource
 *    .color]'s red, every bubble drawn on a guess until this is resolved.
 */
private val SelfSenderSource.stripBackground: Color
    get() = when (this) {
        SelfSenderSource.LEARNED -> Color(0xFFECEEE9)
        SelfSenderSource.OVERRIDE -> Color(0xFFFBF1DF)
        SelfSenderSource.UNKNOWN -> Color(0xFFF6DEDA)
    }

private val SelfSenderSource.stripTextColor: Color
    get() = when (this) {
        SelfSenderSource.LEARNED -> Color(0xFF20242B)
        SelfSenderSource.OVERRIDE -> Color(0xFF6D470B)
        SelfSenderSource.UNKNOWN -> Color(0xFF6E241F)
    }

/**
 * Builds the [SelfSenderStripDisplay] for the chat detail screen's strip.
 * Reuses [resolveSelfSenderSource] so a LEARNED/OVERRIDE source with a
 * null-or-blank name falls through to UNKNOWN here too, exactly as
 * [selfSenderDisplay] and [selfSenderContentDescription] do.
 */
fun selfSenderStrip(source: String?, name: String?): SelfSenderStripDisplay {
    val resolved = resolveSelfSenderSource(source, name)
    val text = when (resolved) {
        SelfSenderSource.LEARNED -> "Your messages: from ${name ?: ""}"
        SelfSenderSource.OVERRIDE -> "Your messages: from ${name ?: ""} (set by you)"
        SelfSenderSource.UNKNOWN -> "Me not known yet: every message will show as someone else's"
    }
    val actionWord = if (resolved == SelfSenderSource.UNKNOWN) "Pick" else "Change"
    return SelfSenderStripDisplay(text, actionWord, resolved.stripBackground, resolved.stripTextColor)
}

/**
 * The chat detail screen's full-width strip: who "Me" resolves to, since
 * that decides which side every bubble in this chat is drawn on. The whole
 * row is the tap target, not just the action word, and opens the Me screen.
 */
@Composable
fun SelfSenderStrip(source: String?, name: String?, onClick: () -> Unit) {
    val display = selfSenderStrip(source, name)
    val description = selfSenderContentDescription(source, name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(display.background)
            .clickable(onClickLabel = "Open Me", onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(display.text, color = display.textColor)
        Text(display.actionWord, color = display.textColor)
    }
}
