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
 * BUG-19: an export whose chat text cannot be read moves out of the queue into its own folder,
 * after one failed run. Only a read failure moves a file. Temp dirs only, placeholder names only.
 */
class UnreadableExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val textName = "WhatsApp Chat with Meera Iyer.txt"
    private val zipName = "WhatsApp Chat with Rohan Mehta.zip"

    private class Setup(val root: File, val inbox: File, val unreadable: File, val db: String)

    private fun setup(): Setup {
        val root = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        return Setup(root, inbox, File(root, "unreadable"), File(root, "state.db").absolutePath)
    }

    private fun chatText(vararg days: Int) =
        days.joinToString("\n") { "%02d/03/25, 09:00 - Meera Iyer: message $it".format(it) } + "\n"

    private fun manager(
        s: Setup,
        transport: MailTransport = CountingTransport(),
        dryRun: Boolean = false,
        useFolder: Boolean = true,
    ) = SyncManager(
        repo = newRepo(s.root, "state.db"), transport = transport, inboxDir = s.inbox,
        processedDir = File(s.root, "processed"), sleeper = { }, dryRun = dryRun,
        unreadableDir = if (useFolder) s.unreadable else null,
    )

    private fun runRows(db: String) =
        SyncRunSupport.rows(db, "SELECT status FROM sync_runs ORDER BY run_id", listOf("status")).map { it[0] }

    private fun chatCount(db: String) =
        SyncRunSupport.rows(db, "SELECT COUNT(*) AS n FROM chats", listOf("n"))[0][0].toString().toInt()

    private fun badZip(s: Setup): File =
        File(s.inbox, zipName).also { SyncRunSupport.writeZip(it, mapOf("IMG-1.jpg" to "x")) }

    @Test
    fun aZipWithNoChatTextMovesOutOfTheQueueAfterOneFailedRun() {
        val s = setup()
        val zip = badZip(s)
        val stats = manager(s).run()
        assertFalse("gone from the queue", zip.exists())
        assertTrue("waits in its own folder", File(s.unreadable, zipName).isFile)
        assertEquals(1, stats.filesFailed)
        assertEquals(1, stats.filesUnreadable)
        assertEquals(listOf("failed"), runRows(s.db))
        assertEquals(listOf(zipName), listUnreadableFiles(s.unreadable).map { it.name })
    }

    @Test
    fun aZeroMessageExportMovesOutOfTheQueue() {
        val s = setup()
        File(s.inbox, textName).writeText("")
        val stats = manager(s).run()
        assertFalse(File(s.inbox, textName).exists())
        assertTrue(File(s.unreadable, textName).isFile)
        assertEquals(1, stats.filesFailed)
        assertEquals(1, stats.filesUnreadable)
        assertEquals(listOf("failed"), runRows(s.db))
    }

    @Test
    fun laterSyncsAddNoFailedRunsAndHaveNothingToDoForTheMovedFile() {
        val s = setup()
        badZip(s)
        manager(s).run()
        val again = manager(s).run()
        val third = manager(s).run()
        assertEquals("one failed run in total", listOf("failed"), runRows(s.db))
        assertEquals(0, again.filesFound)
        assertEquals(0, third.filesFailed)
        assertEquals(0, again.filesUnreadable)
        assertTrue(File(s.unreadable, zipName).isFile)
    }

    @Test
    fun theMovedFileDoesNotCountAsPendingInTheQueueListing() {
        val s = setup()
        badZip(s)
        manager(s).run()
        assertEquals(emptyList<String>(), listInboxFiles(s.inbox).map { it.name })
    }

    @Test
    fun aGoodExportIsNeverMovedAndIsDeliveredAsBefore() {
        val s = setup()
        File(s.inbox, textName).writeText(chatText(20, 21))
        val stats = manager(s).run()
        assertEquals(1, stats.filesSynced)
        assertEquals(0, stats.filesUnreadable)
        assertFalse(s.unreadable.exists() && listUnreadableFiles(s.unreadable).isNotEmpty())
    }

    @Test
    fun aMailFailureNeverMovesTheFile() {
        val s = setup()
        File(s.inbox, textName).writeText(chatText(20, 21))
        val stats = manager(s, transport = CountingTransport(failAt = 1)).run()
        assertTrue("still queued after a mail failure", File(s.inbox, textName).exists())
        assertEquals(0, stats.filesUnreadable)
        assertTrue(listUnreadableFiles(s.unreadable).isEmpty())
    }

    @Test
    fun aMailFailureOnEveryEmailNeverMovesTheFileAcrossRuns() {
        val s = setup()
        File(s.inbox, textName).writeText(chatText(20, 21))
        repeat(3) { manager(s, transport = CountingTransport(failAt = 1)).run() }
        assertTrue(File(s.inbox, textName).exists())
        assertTrue(listUnreadableFiles(s.unreadable).isEmpty())
    }

    @Test
    fun aDryRunNeverMovesAnything() {
        val s = setup()
        val zip = badZip(s)
        val stats = manager(s, dryRun = true).run()
        assertTrue(zip.exists())
        assertEquals(0, stats.filesUnreadable)
        assertTrue(listUnreadableFiles(s.unreadable).isEmpty())
        assertEquals(emptyList<String>(), runRows(s.db))
    }

    @Test
    fun withoutAnUnreadableFolderTheFileIsKeptAsBefore() {
        val s = setup()
        val zip = badZip(s)
        val stats = manager(s, useFolder = false).run()
        assertTrue(zip.exists())
        assertEquals(1, stats.filesFailed)
        assertEquals(0, stats.filesUnreadable)
    }

    @Test
    fun anUnreadableExportLeavesNoEmptyChatRowAndStillShowsInTheSyncLog() {
        val s = setup()
        badZip(s)
        val repo = newRepo(s.root, "state.db")
        SyncManager(
            repo = repo, transport = CountingTransport(), inboxDir = s.inbox,
            processedDir = File(s.root, "processed"), sleeper = { }, unreadableDir = s.unreadable,
        ).run()
        assertEquals("no empty chat row", 0, chatCount(s.db))
        val runs = repo.getRecentRuns(days = 100000)
        assertEquals(1, runs.size)
        assertEquals("failed", runs[0].status)
        assertTrue("name falls back to the chat id", !runs[0].displayName.isNullOrEmpty())
    }

    @Test
    fun aChatWithMessagesIsNeverRemovedByAnUnreadableFile() {
        val s = setup()
        File(s.inbox, textName).writeText(chatText(20, 21))
        manager(s).run()
        val before = chatCount(s.db)
        assertEquals(1, before)
        badZip(s)
        File(s.inbox, "WhatsApp Chat with Rohan Mehta.txt").writeText("")
        manager(s).run()
        assertEquals("the synced chat is still there, and no empty ones were added", before, chatCount(s.db))
        assertTrue(runRows(s.db).contains("complete"))
    }

    @Test
    fun moveRefusesAFileOutsideTheQueueAndALinkedOrMissingOne() {
        val s = setup()
        val outside = File(s.root, "elsewhere.txt").also { it.writeText("x") }
        assertFalse(moveUnreadableExport(outside, s.inbox, s.unreadable))
        assertTrue(outside.exists())
        assertFalse(moveUnreadableExport(File(s.inbox, "missing.txt"), s.inbox, s.unreadable))
        val inQueue = File(s.inbox, "a.txt").also { it.writeText("x") }
        assertFalse("same folder is refused", moveUnreadableExport(inQueue, s.inbox, s.inbox))
        assertTrue(inQueue.exists())
        assertTrue(moveUnreadableExport(inQueue, s.inbox, s.unreadable))
    }

    @Test
    fun aGoodCopyWithTheSameNameQueuesFreshAndSyncs() {
        val s = setup()
        File(s.inbox, textName).writeText("")
        manager(s).run()
        assertTrue(File(s.unreadable, textName).isFile)
        // The user shares a good copy under the same name: the queue has no such file.
        assertFalse(File(s.inbox, textName).exists())
        File(s.inbox, textName).writeText(chatText(20, 21))
        val stats = manager(s).run()
        assertEquals(1, stats.filesSynced)
        assertEquals(0, stats.filesUnreadable)
    }
}
