package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BUG-12: an export that reads as zero messages is kept in inbox/, counted as a failed file and
 * reported. Deliberate difference from Python (frozen, D38), which moves it to processed/ and
 * completes the run. Temp dirs only, placeholder names only.
 */
class ZeroMessageExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileName = "WhatsApp Chat with Meera Iyer.txt"

    private class Setup(val root: File, val inbox: File, val db: String)

    private fun setup(text: String?): Setup {
        val root = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        if (text != null) File(inbox, fileName).writeText(text)
        return Setup(root, inbox, File(root, "state.db").absolutePath)
    }

    private fun manager(s: Setup, dryRun: Boolean = false) = SyncManager(
        repo = newRepo(s.root, "state.db"), transport = CountingTransport(), inboxDir = s.inbox,
        processedDir = File(s.root, "processed"), sleeper = { }, dryRun = dryRun,
    )

    private fun statuses(db: String) =
        SyncRunSupport.rows(db, "SELECT status FROM sync_runs ORDER BY run_id", listOf("status")).map { it[0] }

    private fun chatText(vararg days: Int) =
        days.joinToString("\n") { "%02d/03/25, 09:00 - Meera Iyer: message $it".format(it) } + "\n"

    private fun assertKeptAndFailed(s: Setup, text: String) {
        val stats = manager(s).run()
        assertTrue("the export stays in inbox/", File(s.inbox, fileName).exists())
        assertEquals(text, File(s.inbox, fileName).readText())
        assertEquals(1, stats.filesFailed)
        assertEquals("not counted as skipped", 0, stats.filesSkipped)
        assertEquals("not counted as synced", 0, stats.filesSynced)
        assertEquals(0, stats.messagesSynced)
        assertEquals(1, stats.errors.size)
        assertEquals("$fileName: no messages found - kept in the inbox", stats.errors[0])
        assertEquals(listOf("failed"), statuses(s.db))
        val reason = SyncRunSupport.rows(s.db, "SELECT error_message FROM sync_runs", listOf("error_message"))
        assertEquals("no messages found in this export", reason[0][0])
        assertFalse(File(s.root, "processed").listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun anEmptyTextExportIsKeptAndFailed() = assertKeptAndFailed(setup(""), "")

    @Test
    fun anExportWithOnlySystemLinesIsKeptAndFailed() {
        val text = "Messages and calls are end-to-end encrypted. No one outside of this chat can read them.\n"
        val s = setup(text)
        val stats = manager(s).run()
        // Whatever the parser yields for header-only text, it must never be a quiet success.
        assertTrue(File(s.inbox, fileName).exists())
        assertEquals(1, stats.filesFailed)
        assertEquals(0, stats.filesSkipped)
    }

    @Test
    fun aZeroMessageExportFailsAgainOnEveryRunAddingOneRunRowEach() {
        val s = setup("")
        manager(s).run()
        manager(s).run()
        assertEquals(listOf("failed", "failed"), statuses(s.db))
        assertTrue(File(s.inbox, fileName).exists())
    }

    @Test
    fun aDryRunCountsAndReportsTheSameButWritesNothing() {
        val s = setup("")
        val stats = manager(s, dryRun = true).run()
        assertEquals(1, stats.filesFailed)
        assertEquals(1, stats.errors.size)
        assertTrue(File(s.inbox, fileName).exists())
        assertEquals(emptyList<String>(), statuses(s.db))
    }

    @Test
    fun anExportWhoseMessagesWereAllSentIsStillDeletedAndCompleted() {
        val s = setup(chatText(20, 21))
        manager(s).run()
        File(s.inbox, fileName).writeText(chatText(20, 21))
        val stats = manager(s).run()
        assertEquals(1, stats.filesSkipped)
        assertEquals(0, stats.filesFailed)
        assertTrue(stats.errors.isEmpty())
        assertFalse(File(s.inbox, fileName).exists())
        assertEquals(listOf("complete", "complete"), statuses(s.db))
    }

    @Test
    fun aNormalExportIsStillDeleted() {
        val s = setup(chatText(20, 21))
        val stats = manager(s).run()
        assertEquals(1, stats.filesSynced)
        assertEquals(0, stats.filesFailed)
        assertFalse(File(s.inbox, fileName).exists())
    }

    @Test
    fun aParseErrorExportIsStillKept() {
        val s = setup(null)
        // A zip with no .txt entry is a read error, not a zero-message export.
        SyncRunSupport.writeZip(File(s.inbox, "WhatsApp Chat with Meera Iyer.zip"), mapOf("IMG-1.jpg" to "x"))
        val stats = manager(s).run()
        assertEquals(1, stats.filesFailed)
        assertTrue(stats.errors[0].contains("parse error"))
        assertTrue(File(s.inbox, "WhatsApp Chat with Meera Iyer.zip").exists())
    }

    @Test
    fun aZipWithAnEmptyTextEntryIsKeptAndFailed() {
        val s = setup(null)
        val zip = File(s.inbox, "WhatsApp Chat with Meera Iyer.zip")
        SyncRunSupport.writeZip(zip, mapOf("_chat.txt" to ""))
        val stats = manager(s).run()
        assertEquals(1, stats.filesFailed)
        assertEquals("WhatsApp Chat with Meera Iyer.zip: no messages found - kept in the inbox", stats.errors[0])
        assertTrue(zip.exists())
    }
}
