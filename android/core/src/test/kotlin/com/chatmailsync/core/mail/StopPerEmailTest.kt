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
 * BUG-01: Cancel is honoured at the boundary between two emails of one file, not
 * only between files. Python reads its stop event once per file; this is a
 * deliberate difference.
 */
class StopPerEmailTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileName = "WhatsApp Chat with Meera Iyer.txt"

    private fun chatText(vararg days: Int): String =
        days.joinToString("\n") { "%02d/03/25, 09:00 - Meera Iyer: message $it".format(it) } + "\n"

    private class Setup(val root: File, val inbox: File, val processed: File, val db: String)

    private fun setup(text: String): Setup {
        val root = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        File(inbox, fileName).writeText(text)
        return Setup(root, inbox, File(root, "processed"), File(root, "state.db").absolutePath)
    }

    /** Records every insert; [onInsert] runs INSIDE the insert, after the mail is accepted. */
    private class Recorder(val onInsert: (Int) -> Unit = { }) : MailTransport {
        var inserts = 0
        val threadsSeen = mutableListOf<String?>()
        override fun labelsList(): List<ImapTransport.Label> = emptyList()
        override fun labelsCreate(name: String): String = "Label_WA"
        override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
            inserts++
            threadsSeen.add(threadId)
            onInsert(inserts)
            return ImapTransport.InsertResult(id = "m$inserts", threadId = threadId?.takeIf { it.isNotEmpty() } ?: "t1")
        }
    }

    private fun manager(
        s: Setup,
        transport: MailTransport,
        stop: () -> Boolean = { false },
        onProgress: ((Map<String, Any?>) -> Unit)? = null,
    ) = SyncManager(
        repo = newRepo(s.root, "state.db"), transport = transport, inboxDir = s.inbox,
        processedDir = s.processed, sleeper = { }, shouldStop = stop, onProgress = onProgress,
    )

    private fun hashCount(db: String) = SyncRunSupport.rows(db, "SELECT hash FROM message_hashes", listOf("hash")).size

    private fun distinctHashCount(db: String) =
        SyncRunSupport.rows(db, "SELECT DISTINCT hash FROM message_hashes", listOf("hash")).size

    private fun statuses(db: String) =
        SyncRunSupport.rows(db, "SELECT status FROM sync_runs ORDER BY run_id", listOf("status")).map { it[0] }

    @Test
    fun stopAfterChunkTwoSendsNothingMoreAndLeavesTheRestForLater() {
        val s = setup(chatText(20, 21, 22, 23, 24))
        var stop = false
        val first = Recorder()
        val stats = manager(
            s, first, stop = { stop },
            onProgress = { if (it["type"] == "chunk" && it["chunk"] == 2) stop = true },
        ).run()

        assertEquals("no APPEND after the stop", 2, first.inserts)
        assertEquals("hashes for chunks 1..2 only", 2, hashCount(s.db))
        assertEquals(2, stats.messagesSynced)
        assertEquals(0, stats.filesSynced)
        assertEquals("a stop is not a failure", 0, stats.filesFailed)
        assertTrue(stats.errors.isEmpty())
        assertTrue("the file stays for the next run", File(s.inbox, fileName).exists())
        assertFalse(File(s.processed, fileName).exists())
        assertEquals(listOf("failed"), statuses(s.db))

        // A later run sends exactly the remaining three, none twice.
        val second = Recorder()
        val stats2 = manager(s, second).run()
        assertEquals(3, second.inserts)
        assertEquals(3, stats2.messagesSynced)
        assertEquals(5, hashCount(s.db))
        assertEquals("every message once", 5, distinctHashCount(s.db))
        assertFalse(File(s.inbox, fileName).exists())
        assertTrue(File(s.processed, fileName).exists())
        assertEquals("the resumed emails join the first thread", listOf("t1", "t1", "t1"), second.threadsSeen)
    }

    @Test
    fun noStopSendsTheWholeFile() {
        val s = setup(chatText(20, 21, 22, 23, 24))
        val t = Recorder()
        val stats = manager(s, t).run()
        assertEquals(5, t.inserts)
        assertEquals(5, stats.messagesSynced)
        assertEquals(1, stats.filesSynced)
        assertEquals(5, hashCount(s.db))
        assertTrue(File(s.processed, fileName).exists())
        assertEquals(listOf("complete"), statuses(s.db))
    }

    @Test
    fun aStopDuringAnAppendNeverLeavesAPartialChunk() {
        val s = setup(chatText(20, 21, 22, 23, 24))
        var stop = false
        // The request lands while email 2 is being inserted; that email still completes.
        val t = Recorder { if (it == 2) stop = true }
        manager(s, t, stop = { stop }).run()
        assertEquals(2, t.inserts)
        assertEquals("the accepted email is fully recorded", 2, hashCount(s.db))
        assertEquals(2, distinctHashCount(s.db))
    }

    @Test
    fun aStopBeforeTheFirstEmailSendsAndRecordsNothing() {
        val s = setup(chatText(20, 21))
        val t = Recorder()
        val stats = manager(s, t, stop = { true }).run()
        assertEquals(0, t.inserts)
        assertEquals(0, hashCount(s.db))
        assertEquals(0, stats.messagesSynced)
        assertTrue(File(s.inbox, fileName).exists())
    }

    @Test
    fun aStopAfterTheLastEmailStillCompletesTheFile() {
        val s = setup(chatText(20, 21, 22))
        var stop = false
        val t = Recorder()
        val stats = manager(
            s, t, stop = { stop },
            onProgress = { if (it["type"] == "chunk" && it["chunk"] == 3) stop = true },
        ).run()
        assertEquals(3, t.inserts)
        assertEquals(1, stats.filesSynced)
        assertTrue(File(s.processed, fileName).exists())
        assertEquals(listOf("complete"), statuses(s.db))
    }

    @Test
    fun aDryRunSendsNothingEvenWithoutAStop() {
        val s = setup(chatText(20, 21, 22))
        val t = Recorder()
        val stats = SyncManager(
            repo = newRepo(s.root, "state.db"), transport = t, inboxDir = s.inbox,
            processedDir = s.processed, dryRun = true, sleeper = { },
        ).run()
        assertEquals(0, t.inserts)
        assertEquals(3, stats.messagesSynced)
    }

    @Test
    fun aStopDuringRecoveryLeavesTheRunPendingAndTheNextRunFinishesIt() {
        val s = setup(chatText(20, 21, 22, 23))
        // Leave a pending run behind: the second insert kills the "process".
        try {
            manager(s, CountingTransport(crashAt = 2)).run()
        } catch (_: ProcessDeath) {
            // expected
        }
        var stop = false
        val r1 = Recorder { stop = true }
        manager(s, r1, stop = { stop }).run()
        assertEquals("recovery stops after one email", 1, r1.inserts)
        assertEquals(listOf("pending"), statuses(s.db))
        assertTrue(File(s.inbox, fileName).exists())

        val r2 = Recorder()
        manager(s, r2).run()
        assertEquals(2, r2.inserts)
        assertEquals(4, distinctHashCount(s.db))
        assertTrue(File(s.processed, fileName).exists())
    }
}
