package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BUG-16: a Stop during recovery keeps the run 'pending' (recovery still picks it up) but flags
 * it stopped with its real count, so it never reads "Still running". Placeholder data only.
 */
class RecoveryStopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileName = "WhatsApp Chat with Meera Iyer.txt"

    private fun chatText(vararg days: Int): String =
        days.joinToString("\n") { "%02d/03/25, 09:00 - Meera Iyer: message $it".format(it) } + "\n"

    private class Setup(val root: File, val inbox: File, val processed: File)

    private fun setup(): Setup {
        val root = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        return Setup(root, inbox, File(root, "processed"))
    }

    private fun manager(s: Setup, transport: MailTransport, stop: () -> Boolean = { false }) = SyncManager(
        repo = newRepo(s.root, "state.db"), transport = transport, inboxDir = s.inbox,
        processedDir = s.processed, sleeper = { }, shouldStop = stop,
    )

    /** Stops the user's sync once [after] emails of this transport have gone. */
    private class StopAfter(private val after: Int) : MailTransport {
        var inserts = 0
        val stop: () -> Boolean get() = { inserts >= after }
        override fun labelsList(): List<ImapTransport.Label> = emptyList()
        override fun labelsCreate(name: String): String = "Label_WA"
        override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
            inserts++
            return ImapTransport.InsertResult(id = "m$inserts", threadId = threadId?.takeIf { it.isNotEmpty() } ?: "t1")
        }
    }

    /** A crash after one email, then a recovery that the user stops after one more. */
    private fun recoveryStopped(): Pair<StateRepository, Setup> {
        val s = setup()
        File(s.inbox, fileName).writeText(chatText(20, 21, 22, 23, 24))
        try {
            manager(s, CountingTransport(crashAt = 2)).run()
            fail("the simulated process death should escape run()")
        } catch (_: ProcessDeath) {
        }
        val t = StopAfter(1)
        manager(s, t, t.stop).run()
        return newRepo(s.root, "state.db") to s
    }

    @Test
    fun aStopDuringRecoveryKeepsTheRunPendingButFlagsItStoppedWithTheRealCount() {
        val (repo, s) = recoveryStopped()
        val pending = repo.getPendingRuns()
        assertEquals("recovery still picks it up", 1, pending.size)
        val run = pending.single()
        assertTrue(run.stopped)
        assertEquals("pending", run.status)
        // one email before the crash plus one in the stopped recovery
        assertEquals(2, run.messagesSynced)
        assertEquals("stopped by the user after 2 of 5 messages", run.errorMessage)
        assertTrue("the export stays", File(s.inbox, fileName).exists())
        assertEquals("stopped", repo.getSyncSummary().single().lastRunStatus)
    }

    @Test
    fun aStoppedPendingRunIsNotRunningNorFailed() {
        val (repo, _) = recoveryStopped()
        val sum = repo.summarizeRecentRuns(days = 36500)
        assertEquals(0, sum.runningRuns)
        assertEquals(0, sum.failedRuns)
        assertEquals("stopped", sum.lastStatus)
        assertEquals(2, sum.lastMessagesSynced)
        assertNotNull("a time is set so Home can say when", sum.lastCompletedAt)
        // never the last success, never an overlap baseline
        assertNull(repo.getLastSuccessfulRun("meera_iyer"))
        assertNull(repo.getLastSyncedTs("meera_iyer"))
    }

    @Test
    fun theNextRunRecoversItSendsOnlyTheMissingMessagesAndEndsCompleteNotStopped() {
        val (repo, s) = recoveryStopped()
        val next = CountingTransport()
        manager(s, next).run()
        assertEquals("only the 3 still missing", 3, next.insertCalls)
        val first = repo.getRecentRuns(days = 36500).single { it.runId == 1L }
        assertEquals("complete", first.status)
        assertFalse("no stale stopped flag on a finished run", first.stopped)
        assertNull("the stop note is cleared", first.errorMessage)
        assertEquals(5, first.messagesSynced)
        assertEquals(0, repo.getPendingRuns().size)
        assertEquals(5, repo.countArchivedMessages("meera_iyer"))
        val sum = repo.summarizeRecentRuns(days = 36500)
        assertEquals(0, sum.failedRuns)
        assertEquals(0, sum.runningRuns)
        assertEquals("complete", sum.lastStatus)
        assertFalse(File(s.inbox, fileName).exists())
    }

    @Test
    fun aRecoveryThatThenFailsClearsTheStoppedFlag() {
        val (repo, s) = recoveryStopped()
        manager(s, CountingTransport(failAt = 1, failText = "simulated server refusal")).run()
        val first = repo.getRecentRuns(days = 36500).single { it.runId == 1L }
        assertEquals("failed", first.status)
        assertFalse("a failed run is not a stopped one", first.stopped)
        assertTrue(first.errorMessage!!.contains("simulated server refusal"))
        assertEquals(1, repo.summarizeRecentRuns(days = 36500).failedRuns)
    }

    @Test
    fun aLivePendingRunStillReadsRunning() {
        val s = setup()
        val repo = newRepo(s.root, "state.db")
        repo.upsertChat("meera_iyer", "Meera Iyer", fileName)
        repo.startSyncRun("meera_iyer")
        val sum = repo.summarizeRecentRuns(days = 36500)
        assertEquals(1, sum.runningRuns)
        assertEquals(0, sum.failedRuns)
        assertEquals("pending", repo.getSyncSummary().single().lastRunStatus)
        assertFalse(repo.getPendingRuns().single().stopped)
    }

    @Test
    fun aUserStopRunClosedByBug15IsNeverReopened() {
        // BUG-15 rows are 'failed' + stopped; nothing sets a run back to 'pending', so
        // recovery (which reads only 'pending') cannot touch them.
        val s = setup()
        File(s.inbox, fileName).writeText(chatText(20, 21, 22, 23, 24))
        val t = StopAfter(2)
        manager(s, t, t.stop).run()
        val repo = newRepo(s.root, "state.db")
        assertEquals(0, repo.getPendingRuns().size)
        manager(s, CountingTransport()).run()
        val first = repo.getRecentRuns(days = 36500).single { it.runId == 1L }
        assertEquals("failed", first.status)
        assertTrue(first.stopped)
        assertEquals(2, first.messagesSynced)
    }

    @Test
    fun theStoppedPendingFlagSurvivesABackupRoundTrip() {
        val (_, s) = recoveryStopped()
        val openDb: OpenDb = { f -> SqliteJdbcStateDb(f.absolutePath) }
        val src = tmp.newFolder()
        bundleDbPath(src).parentFile.mkdirs()
        File(s.root, "state.db").copyTo(bundleDbPath(src), overwrite = true)
        val bundle = File(tmp.newFolder(), "x.cmsbackup")
        exportBundle(src, bundle, openDb, appVersion = "2.9.0")
        val dst = tmp.newFolder()
        bundleDbPath(dst).parentFile.mkdirs()
        val imported = importBundle(dst, bundle, openDb)
        assertTrue(imported.toString(), imported.ok)
        val repo = StateRepository { openDb(bundleDbPath(dst)) }
        repo.initDb()
        val run = repo.getPendingRuns().single()
        assertTrue(run.stopped)
        assertEquals(2, run.messagesSynced)
        assertEquals("pending", run.status)
    }
}
