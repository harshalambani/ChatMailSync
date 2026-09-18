package com.chatmailsync.core.mail

/**
 * Kotlin port of `src/self_sender.py` (Phase 2 "self_sender" of the Kotlin
 * core port — see `2026-09-17-kotlin-core-fdroid-plan-and-windows-audit.md`,
 * sections D/E, which lists `self_sender` as a pure-Kotlin, no-I/O direct
 * port right after `progress`, both consumed together in the dependency
 * order config -> progress -> self_sender -> parser -> ...). A faithful
 * behavioural twin of the Python module's public functions, except where a
 * section below explicitly says otherwise.
 *
 * NOT wired into `:app` yet — `:app` still calls `android_api.get_self_sender()`
 * / `set_self_sender()` through Chaquopy (`MainActivity.kt:897,921-922`),
 * which are thin wrappers around this module's `resolve`/`describe`. This
 * file exists so a later phase can swap those Chaquopy calls for direct
 * `SelfSender.resolve(...)`/`SelfSender.describe(...)` calls without a
 * behavioural surprise.
 *
 * Work out which sender in an export is the account owner.
 *
 * A WhatsApp export does not mark your own messages in any way. It writes
 * your profile name in the sender position exactly as it writes everybody
 * else's:
 *
 * ```
 * 18/11/25, 16:18 - Rohan Mehta: on my way
 * ```
 *
 * For a long time the renderer assumed that name was the literal string
 * "You", which is what the fictional demo exports used and what the test
 * fixtures were built from. Against a real export that assumption is
 * silently false for every single message: nothing ever matches, so every
 * bubble renders as incoming and the whole conversation collapses to one
 * side. Three surfaces agreeing with each other -- renderer, fixtures, demo
 * generator -- is not three pieces of evidence.
 *
 * The rules below are deliberately conservative. Guessing wrong is worse
 * than not guessing: an under-detected chat renders the way it always did
 * and can be fixed by setting the name, whereas a wrong guess silently
 * attributes somebody else's messages to you, in an archive you are keeping
 * precisely because you trust it.
 *
 * Resolution order, first match wins:
 *
 *  1. An explicit name the user has set. Always wins.
 *  2. The one-to-one rule ([deriveFromOneToOne]) applied to *this* export,
 *     which is provable rather than probabilistic.
 *  3. A name already learned from an earlier one-to-one chat. A fresh
 *     derivation outranks it because a profile-name change is the ordinary
 *     reason the two disagree, and the export in hand is the newer evidence.
 *  4. The literal "You", which is both the historical behaviour and the
 *     correct answer for exports that really do use it.
 *
 * Note what is *not* here: anything based on how often a name appears. In a
 * group chat the most frequent sender is very often not you, and an identity
 * inferred from message counts is exactly the silent mis-attribution
 * described above. Self is only ever *derived* from a one-to-one chat. Group
 * chats consume a name that a one-to-one already established, or the user's
 * explicit setting, and otherwise fall back.
 */

/** The historical sender string, and a real one: some exports genuinely use it. */
const val SELF_SENDER_FALLBACK = "You"

/**
 * Fold a sender for comparison. Twin of `self_sender.py:_normalise`.
 *
 * WhatsApp sometimes prepends U+200E (LEFT-TO-RIGHT MARK) to a field, and
 * casing of a profile name is not stable across exports.
 *
 * Two Python-specific things happen here that are worth being explicit
 * about rather than reaching for the nearest-looking Kotlin call:
 *
 * - `str.strip()` removes any Unicode code point with the `White_Space`
 *   property, not just ASCII space/tab/newline -- notably U+00A0 (NBSP) and
 *   U+202F (NARROW NO-BREAK SPACE), which real exports contain (iOS inserts
 *   U+202F before AM/PM in timestamps, and NBSP shows up in pasted names).
 *   Kotlin's [CharSequence.trim] with no predicate uses [Char.isWhitespace],
 *   whose JVM implementation is `Character.isWhitespace(c) ||
 *   Character.isSpaceChar(c)` -- the first excludes NBSP-family characters
 *   by design (they must not line-break), but the second (`isSpaceChar`,
 *   true for any Unicode `Zs` code point) includes them, so the `||`
 *   recovers Python's behaviour. See [aNbspAndNarrowNbspAreStrippedLikePython]
 *   in the test twin.
 * - `str.casefold()` is a strictly more aggressive, context-independent fold
 *   than `str.lower()`: German "ß" casefolds to "ss" (a length-changing
 *   fold), the archaic "ſ" (LATIN SMALL LETTER LONG S) folds to "s", and
 *   ligatures such as "ﬁ" fold to "fi" -- none of which `.lowercase()` alone
 *   does. [pythonCasefold] below approximates this with `.uppercase(Locale.ROOT)`
 *   followed by `.lowercase(Locale.ROOT)`, which recovers all of the above
 *   (uppercasing expands "ß"/"ſ"/"ﬁ" to their multi-character forms, which
 *   then lowercase cleanly) and was verified directly against a running JVM
 *   for exactly those three cases. It has one known remaining divergence:
 *   Java's `lowercase()`, unlike Python's context-independent `casefold()`,
 *   applies Unicode's `Final_Sigma` rule, so a capital sigma ("Σ") ending a
 *   run of cased letters folds to the Greek *final* sigma "ς" in Kotlin
 *   rather than the plain "σ" Python's `casefold()` always produces. See
 *   [greekFinalSigmaAtWordEndDivergesFromPythonsCasefold] in the test twin,
 *   which pins this gap rather than hiding it.
 */
internal fun normaliseSender(name: String): String =
    pythonCasefold(name.replace("\u200E", "").trim { it.isWhitespaceLikePython() })

/**
 * Twin of Python's `str.isspace()` character set, which is what `str.strip()`
 * uses to decide what to trim. Deliberately **not** Kotlin's
 * [Char.isWhitespace] alone: on the JVM that delegates to
 * `java.lang.Character.isWhitespace`, whose Javadoc explicitly *excludes*
 * the no-break-space family (U+00A0, U+2007, U+202F) because they must not
 * be used as line-break points in text layout -- a font-rendering concern
 * Python's `str.isspace()` does not share (it is true for any code point
 * with the Unicode `White_Space` property, which does include that family).
 * OR-ing in `java.lang.Character.isSpaceChar` (true for any Unicode `Zs`
 * category code point, which covers NBSP and NARROW NO-BREAK SPACE) closes
 * that gap. See [aNbspAndNarrowNbspAreStrippedLikePython] in the test twin --
 * this was caught by that test failing against a first version of this
 * function that used bare `Char.isWhitespace()`.
 */
private fun Char.isWhitespaceLikePython(): Boolean =
    Character.isWhitespace(this) || Character.isSpaceChar(this)

/**
 * Twin of Python's `str.casefold()`. See [normaliseSender]'s KDoc for what
 * is and is not covered, including the one known divergence (Greek
 * word-final sigma).
 */
internal fun pythonCasefold(name: String): String =
    name.uppercase(java.util.Locale.ROOT).lowercase(java.util.Locale.ROOT)

/**
 * Twin of Python's `str.strip()`, using the same Unicode whitespace set as
 * [normaliseSender] (see its KDoc) rather than Kotlin's default
 * [CharSequence.trim], which under-strips the NBSP family.
 */
internal fun pythonStrip(value: String): String = value.trim { it.isWhitespaceLikePython() }

/**
 * Twin of the `if x and x.strip():` idiom `self_sender.py:resolve`/`describe`
 * use to treat `None`, `""` and whitespace-only strings alike as "nothing
 * set". Named rather than inlined as `value.isNullOrBlank()` because Kotlin's
 * [CharSequence.isBlank] has the same NBSP under-detection gap as `trim()`
 * does (see [pythonStrip]).
 */
internal fun isBlankLikePython(value: String?): Boolean =
    value == null || value.all { it.isWhitespaceLikePython() }

/**
 * Return the owner's name, or `null` when this export cannot prove it.
 * Twin of `self_sender.py:derive_from_one_to_one`.
 *
 * A one-to-one export is the only shape that identifies the owner without
 * guesswork. Its filename names the other party -- "WhatsApp Chat with
 * Priya Nair.txt" -- and exactly two people ever speak in it. Match the
 * filename against the senders and whoever is left is, necessarily, you.
 *
 * Any other shape returns `null`:
 *
 * - A group chat's filename is the group's name and matches no sender.
 * - A one-to-one where only one person ever spoke has nobody left over.
 * - A chat where the filename matches neither sender (a file renamed by
 *   hand, say) proves nothing and is not guessed at.
 */
fun deriveFromOneToOne(displayName: String, senders: Iterable<String>): String? {
    val unique = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    for (sender in senders) {
        val key = normaliseSender(sender)
        if (key.isNotEmpty() && seen.add(key)) {
            unique.add(sender)
        }
    }

    if (unique.size != 2) return null

    val target = normaliseSender(displayName)
    if (target.isEmpty()) return null

    val matched = unique.filter { normaliseSender(it) == target }
    if (matched.size != 1) {
        // Neither matched, or -- pathologically -- both did. Either way the
        // export has not identified anybody, so say so.
        return null
    }

    return unique.first { normaliseSender(it) != target }
}

/**
 * Result of [resolve]. Twin of the `(name, newly_derived)` tuple
 * `self_sender.py:resolve` returns.
 *
 * `name` is what the renderer should treat as outgoing. `newlyDerived` is a
 * name proven by *this* export that the caller should persist, or `null`
 * when nothing new was learned. `newlyDerived` is populated even when an
 * override is in force, so that the learned value stays current underneath
 * it; the override still wins for rendering. It is deliberately not
 * populated when it merely confirms what was already stored.
 */
data class SelfSenderResolution(val name: String, val newlyDerived: String?)

/**
 * Resolve the owner's name for one chat. Twin of `self_sender.py:resolve`.
 *
 * @param senders `null` means "this export's sender list was not supplied"
 *   (Python's default `senders=None`), which is distinct from an empty
 *   list: an empty list is a real (if degenerate) export with zero
 *   speakers, and still runs [deriveFromOneToOne] on it (which returns
 *   `null`, since `unique.size` will be 0, not 2). `null` skips derivation
 *   entirely. See [aNullSenderListSkipsDerivationWhileAnEmptyListStillRuns].
 */
fun resolve(
    override: String? = null,
    learned: String? = null,
    displayName: String = "",
    senders: Iterable<String>? = null,
): SelfSenderResolution {
    val derived = if (senders != null) deriveFromOneToOne(displayName, senders) else null

    var newlyDerived: String? = null
    if (derived != null && normaliseSender(derived) != normaliseSender(learned ?: "")) {
        // A later one-to-one disagreeing with the stored name is the
        // ordinary consequence of changing your WhatsApp profile name, so
        // the newer derivation wins rather than being discarded as a
        // conflict.
        newlyDerived = derived
    }

    if (!isBlankLikePython(override)) {
        return SelfSenderResolution(pythonStrip(override!!), newlyDerived)
    }
    if (derived != null) {
        return SelfSenderResolution(derived, newlyDerived)
    }
    if (!isBlankLikePython(learned)) {
        return SelfSenderResolution(pythonStrip(learned!!), null)
    }
    return SelfSenderResolution(SELF_SENDER_FALLBACK, null)
}

/**
 * Return `true` when `sender` is the account owner. Twin of
 * `self_sender.py:is_outgoing`.
 *
 * `selfName` of `null` means nothing has been established yet, which is the
 * fallback case rather than an error: it matches the literal "You" and
 * nothing else, preserving the behaviour exports of that shape rely on.
 */
fun isOutgoing(sender: String, selfName: String?): Boolean =
    normaliseSender(sender) == normaliseSender(selfName ?: SELF_SENDER_FALLBACK)

// Kept here rather than in either front-end so both say exactly the same
// thing. The wording is deliberately about *messages*, not about identity:
// what the user is being told is which bubbles will be drawn as theirs.
private const val UNKNOWN_SUMMARY = "Not worked out yet"
private const val UNKNOWN_DETAIL =
    "Until the app knows which name is yours, every message is shown as if " +
        "somebody else sent it. Sync any one-to-one chat and the app works it out " +
        "on its own -- the export names the other person, so the only other name " +
        "in it is yours. Or type your WhatsApp profile name here."
private const val LEARNED_DETAIL =
    "Worked out from a one-to-one chat, where the export named the other " +
        "person and left exactly one name over. Messages from this name are shown " +
        "as yours. If it is wrong, type the right one here and the app will stop " +
        "working it out."
private const val OVERRIDE_DETAIL =
    "You set this name, so the app will not change it. Messages from this " +
        "name are shown as yours. Clear the box to let the app work it out from a " +
        "one-to-one chat again."

/**
 * Describe who the app currently thinks the owner is, for display. Twin of
 * `self_sender.py:describe`.
 *
 * A name inferred from the user's own files decides which side every bubble
 * is drawn on, and that is too consequential to happen silently -- if the
 * app has got it wrong, the archive is wrong, and the user is the only one
 * who can say so. So both front-ends show this, and both show the same
 * words.
 *
 * `source` is one of `"override"`, `"learned"` or `"unknown"`. `name` is
 * `null` only when unknown.
 */
data class SelfSenderDescription(
    val name: String?,
    val source: String,
    val summary: String,
    val detail: String,
)

fun describe(override: String? = null, learned: String? = null): SelfSenderDescription {
    if (!isBlankLikePython(override)) {
        val name = pythonStrip(override!!)
        return SelfSenderDescription(
            name = name,
            source = "override",
            summary = "Your messages are the ones from $name",
            detail = OVERRIDE_DETAIL,
        )
    }
    if (!isBlankLikePython(learned)) {
        val name = pythonStrip(learned!!)
        return SelfSenderDescription(
            name = name,
            source = "learned",
            summary = "Your messages are the ones from $name",
            detail = LEARNED_DETAIL,
        )
    }
    return SelfSenderDescription(
        name = null,
        source = "unknown",
        summary = UNKNOWN_SUMMARY,
        detail = UNKNOWN_DETAIL,
    )
}
