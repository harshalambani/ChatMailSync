package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The cutoff card's whole vocabulary, and the check Settings makes before it
 * saves. The Windows twins are gui._format_cutoff_day and the normalise_cutoff
 * call in _SettingsPanel._on_save; these tests mirror the four in
 * tests/test_gui_backend.py that cover them, plus the strict-date cases the
 * Python side gets for free from strptime.
 */
class CutoffDateTest {

    @Test
    fun `names the day the way a person would`() {
        // Not "2026-01-01". The card exists to be read at a glance by someone
        // wondering why a chat is short, and an ISO date is the app talking to
        // itself.
        assertEquals("1 January 2026", CutoffDate.format("2026-01-01"))
        assertEquals("31 December 2024", CutoffDate.format("2024-12-31"))
    }

    @Test
    fun `reads the stored instant as well as the day`() {
        // The filter compares against "...T00:00:00" and the preference holds
        // the bare day. The card must not care which one reaches it.
        assertEquals("1 January 2026", CutoffDate.format("2026-01-01T00:00:00"))
    }

    @Test
    fun `no cutoff means no card at all`() {
        // "" is what the whole screen treats as "no floor", and null is what a
        // preference that was never written answers with.
        assertEquals("", CutoffDate.format(""))
        assertEquals("", CutoffDate.format(null))
        assertEquals("", CutoffDate.format("   "))
    }

    @Test
    fun `a date the app cannot read costs a card not a crash`() {
        // A preference restored from a bundle written by hand. The card is
        // cosmetic; taking the home screen down over it would not be.
        assertEquals("", CutoffDate.format("first of January"))
        assertEquals("", CutoffDate.format("2026-13-40"))
        assertEquals("", CutoffDate.format("2026/01/01"))
    }

    @Test
    fun `blank is a valid answer`() {
        // Leaving the field empty is not a mistake to be flagged -- it is how
        // the user says "send me everything".
        assertTrue(CutoffDate.isReadable(""))
        assertTrue(CutoffDate.isReadable(null))
        assertTrue(CutoffDate.isReadable("   "))
    }

    @Test
    fun `a real day is accepted and a plausible non-day is not`() {
        // Strict on purpose. "2026-13-40" is three perfectly good integers and
        // still not a date, and a floor that sorts wrong against every message
        // timestamp fails silently -- as a sync that sends nothing, or one
        // that sends everything.
        assertTrue(CutoffDate.isReadable("2026-01-01"))
        assertFalse(CutoffDate.isReadable("2026-13-01"))
        assertFalse(CutoffDate.isReadable("2026-00-01"))
        assertFalse(CutoffDate.isReadable("2026-01-32"))
        assertFalse(CutoffDate.isReadable("2026-01-00"))
        assertFalse(CutoffDate.isReadable("2026-1-1"))
        assertFalse(CutoffDate.isReadable("20260101"))
    }

    @Test
    fun `February knows which years are leap years`() {
        // The one month where a naive 1..31 check quietly accepts a day that
        // does not exist, on a boundary that recurs every four years.
        assertTrue(CutoffDate.isReadable("2024-02-29"))
        assertFalse(CutoffDate.isReadable("2025-02-29"))
        assertTrue(CutoffDate.isReadable("2000-02-29"))
        assertFalse(CutoffDate.isReadable("1900-02-29"))
        assertFalse(CutoffDate.isReadable("2024-02-30"))
    }

    @Test
    fun `the short months stop at thirty`() {
        assertTrue(CutoffDate.isReadable("2026-04-30"))
        assertFalse(CutoffDate.isReadable("2026-04-31"))
        assertTrue(CutoffDate.isReadable("2026-03-31"))
    }

    // -----------------------------------------------------------------
    // The wiring no JVM test can reach
    //
    // SyncWorker needs a Context, WorkManager and a live Chaquopy runtime, so
    // the two lines that make the cutoff actually do something on this
    // platform cannot be exercised here. They are read as text instead --
    // the same approach FrozenIdentifiersTest takes, and for the same reason:
    // the thing worth guarding is the literal a future edit would remove.
    //
    // Both failures are silent. Drop the argument and every sync mails out
    // the messages the user set the floor to prevent; drop the field and the
    // run reports a large "skipped" count with no explanation anywhere for
    // where those messages went.
    // -----------------------------------------------------------------

    @Test
    fun `an empty field names the floor that is still in force`() {
        // The state that matters. A chat with no override of its own still
        // stands behind the app-wide cutoff, and a blank field beside a floor
        // that is quietly applying is how someone decides the app is losing
        // their messages.
        assertEquals(
            "Using the app-wide cutoff, 1 January 2026. A date here applies to this chat only.",
            CutoffDate.chatHint("", "1 January 2026"),
        )
    }

    @Test
    fun `an override says the app-wide date no longer applies`() {
        assertEquals(
            "This chat stops at 1 March 2026. The app-wide cutoff does not apply to it.",
            CutoffDate.chatHint("1 March 2026", "1 January 2026"),
        )
    }

    @Test
    fun `with no floor anywhere it says so rather than staying silent`() {
        assertEquals(
            "No cutoff, so every message in this chat is sent. A date here " +
                "applies to this chat only.",
            CutoffDate.chatHint("", ""),
        )
    }

    @Test
    fun `the chat screen both reads and writes the per-chat floor`() {
        // Two halves of one control, and either missing is silent: without
        // the read the field shows blank over a floor that is applying,
        // without the write the date the user typed is never stored.
        val source = source("src/main/java/com/chatmailsync/app/ChatDetailScreen.kt")
        assertTrue(
            "the screen must load this chat's own cutoff",
            source.contains("\"get_cutoff\", chatId"),
        )
        assertTrue(
            "and store what was typed",
            source.contains("\"set_cutoff\", chatId, it.trim()"),
        )
        assertTrue(
            "and store the clearing of it, which is a write of its own and not"
                + " the absence of one",
            source.contains("\"set_cutoff\", chatId, \"\""),
        )
    }

    @Test
    fun `the run detail keeps the held-back count off the skipped line`() {
        val source = source("src/main/java/com/chatmailsync/app/SyncLogScreen.kt")
        assertTrue(
            "messages_cutoff must be read off the run row",
            source.contains("messagesCutoff = getStr(row, \"messages_cutoff\")"),
        )
        assertTrue(
            "and shown as its own field, not added to the skipped count",
            source.contains("DetailField(\"Held back by your cutoff date\""),
        )
    }

    private fun source(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("could not locate " + relative)
    }

    private fun syncWorkerSource(): String {
        // Gradle runs unit tests with the module directory as the working
        // directory, but that is a default rather than a promise, so walk up
        // until the path resolves rather than trusting it. Same as
        // FrozenIdentifiersTest, and for the same reason.
        val relative = "src/main/java/com/chatmailsync/app/SyncWorker.kt"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("could not locate " + relative)
    }

    @Test
    fun `the cutoff reaches Python on every run`() {
        val source = syncWorkerSource()
        assertTrue(
            "SyncWorker must read the saved cutoff itself",
            source.contains("AppPrefs.getCutoffDate(applicationContext)"),
        )
        assertTrue(
            "the cutoff must be the 8th argument to android_api.sync()",
            source.contains(
                "\"sync\", transport, chunkSize, dryRun, chatFilter, null, trigger, cutoff,"
            ),
        )
    }

    @Test
    fun `what the cutoff held back is reported as its own number`() {
        val source = syncWorkerSource()
        assertTrue(
            "messages_cutoff must be read off the Python result",
            source.contains("messagesCutoff = intOf(\"messages_cutoff\")"),
        )
        assertTrue(
            "and said out loud, mirroring SyncStats.__str__",
            source.contains("Held back \$messagesCutoff message(s) from before your cutoff date"),
        )
    }
}
