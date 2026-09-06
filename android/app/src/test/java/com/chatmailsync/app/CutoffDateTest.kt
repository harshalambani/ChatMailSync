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
