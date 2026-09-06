package com.chatmailsync.app

/**
 * The app-wide cutoff date, as text: reading it, and saying it out loud.
 *
 * The cutoff is a floor -- "never send me anything from before this date" --
 * and it is stored as a bare "YYYY-MM-DD" day in [AppPrefs]. Python owns what
 * it *means*: src/state.py's normalise_cutoff turns the day into local
 * midnight and SyncManager compares message timestamps against that. Nothing
 * here duplicates that rule; this is the Kotlin side of the same two jobs the
 * Windows client does in gui.py -- deciding whether what the user typed can be
 * read at all, and turning it into a sentence a person would say.
 *
 * Deliberately not java.time. The parsing is a handful of digits and a
 * calendar rule, the class has a plain JVM unit test with no Android runtime
 * behind it, and minSdk is 24 -- below java.time's API 26 without library
 * desugaring, which this module does not enable.
 */
object CutoffDate {

    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    /** Blank, in every spelling, is "no cutoff" -- never the string "". */
    private fun day(value: String?): String? =
        value?.trim()?.take(10)?.takeIf { it.isNotEmpty() }

    /**
     * The day the cutoff names, as (year, month, dayOfMonth), or null if the
     * text is not a date this app can compare.
     *
     * Strict on purpose: "2026-13-40" parses as three integers perfectly well
     * and is still not a day, and a floor that sorts wrong against every
     * message timestamp fails silently -- as a sync that sends nothing, or one
     * that sends everything.
     */
    private fun parse(text: String): Triple<Int, Int, Int>? {
        if (text.length != 10 || text[4] != '-' || text[7] != '-') return null
        val year = text.substring(0, 4).toIntOrNull() ?: return null
        val month = text.substring(5, 7).toIntOrNull() ?: return null
        val dayOfMonth = text.substring(8, 10).toIntOrNull() ?: return null
        if (month !in 1..12) return null
        if (dayOfMonth !in 1..daysIn(year, month)) return null
        return Triple(year, month, dayOfMonth)
    }

    private fun daysIn(year: Int, month: Int): Int = when (month) {
        4, 6, 9, 11 -> 30
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        else -> 31
    }

    /**
     * The cutoff as a person would say it out loud: "1 January 2026".
     *
     * Takes either the stored "YYYY-MM-DD" or the full ISO instant the filter
     * compares against, and returns "" for anything it cannot read -- a
     * corrupted preference should cost a card, not a crash on the home screen.
     * "" is already the value that means "no cutoff" to every caller here.
     * The Windows twin is gui._format_cutoff_day.
     */
    fun format(value: String?): String {
        val text = day(value) ?: return ""
        val (year, month, dayOfMonth) = parse(text) ?: return ""
        return "$dayOfMonth ${MONTHS[month - 1]} $year"
    }

    /**
     * Whether this is something the app can use as a floor. Blank counts:
     * leaving the field empty is a valid answer and means no cutoff at all.
     *
     * This is the check Settings makes before saving, so that an unreadable
     * date is refused while the user is still looking at it, rather than
     * stored and discovered later as a sync that quietly did the wrong thing.
     */
    fun isReadable(value: String?): Boolean {
        val text = day(value) ?: return true
        return parse(text) != null
    }

    /**
     * What the line under a chat's own cutoff field says.
     *
     * Three states, and the middle one is the one that matters. A chat with
     * no override of its own is not "no cutoff" -- it is still standing
     * behind the app-wide floor, and a field sitting empty while a floor
     * quietly applies is exactly how someone concludes the app is losing
     * their messages. So the empty field names whose date is in force.
     *
     * Both arguments are already-formatted days ("1 January 2026") or "":
     * nothing is parsed here, [format] does that. The Windows twin is
     * gui._chat_cutoff_hint, and tests/test_chat_detail.py holds the two to
     * the same words.
     */
    fun chatHint(ownDay: String, appDay: String): String = when {
        ownDay.isNotEmpty() ->
            "This chat stops at $ownDay. The app-wide cutoff does not apply to it."
        appDay.isNotEmpty() ->
            "Using the app-wide cutoff, $appDay. A date here applies to this chat only."
        else ->
            "No cutoff, so every message in this chat is sent. A date here " +
                "applies to this chat only."
    }
}
