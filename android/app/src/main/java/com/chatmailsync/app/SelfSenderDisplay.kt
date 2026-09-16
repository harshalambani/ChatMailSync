package com.chatmailsync.app

import androidx.compose.ui.graphics.Color

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
