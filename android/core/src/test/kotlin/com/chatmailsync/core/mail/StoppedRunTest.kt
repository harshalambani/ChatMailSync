package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BUG-15: a run the user stopped is stored with its real delivered count and reads as stopped,
 * never as failed. Placeholder data only (Meera Iyer).
 */
class StoppedRunTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileName = "WhatsApp Chat with Meera Iyer.txt"

    private fun chatText(vararg days: Int): String =
        days.joinToString("\n") { "%02d/03/25, 09:00 - Meera Iyer: message $it".format(it) } + "\n"

    private class Setup(val root: File, val inbox: File, val processed: File)

    private fun setup(text: String): Setup {
        val root = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        File(inbox, fileName).writeText(text)
        return Setup(root, inbox, File(root, "processed"))
    }

    private class Recorder : MailTransport {
        var inserts = 0
        override fun labelsList(): List<ImapTransport.Label> = emptyList()
        override fun labelsCreate(name: String): String = "Label_WA"
        override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
            inserts++
            return ImapTransport.InsertResult(id = "m$inserts", threadId = threadId?.takeIf { it.isNotEmpty() } ?: "t1")
        }
    }

    private class Failing : MailTransport {
        override fun labelsList(): List<ImapTransport.Label> = emptyList()
        override fun labelsCreate(name: String): String = "Label_WA"
        override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult =
            throw IllegalStateException("simulated server refusal")
    }

    /** Runs a 5-message file, stopping after email 2. Returns the repo and the setup. */
    private fun stoppedAfterTwo(): Pair<StateRepository, Setup> {
        val s = setup(chatText(20, 21, 22, 23, 24))
        val repo = newRepo(s.root, "state.db")
        var stop = false
        SyncManager(
            repo = repo, transport = Recorder(), inboxDir = s.inbox, processedDir = s.processed,
            sleeper = { }, shouldStop = { stop },
            onProgress = { if (it["type"] == "chunk" && it["chunk"] == 2) stop = true },
        ).run()
        return repo to s
    }

    @Test
    fun aStoppedRunKeepsItsRealCountAndReadsAsStopped() {
        val (repo, _) = stoppedAfterTwo()
        val run = repo.getRecentRuns(days = 36500).single()
        assertTrue(run.stopped)
        assertEquals(2, run.messagesSynced)
        assertEquals("failed", run.status) // the CHECK allows nothing else; callers show it as stopped
        assertEquals("stopped by the user after 2 of 5 messages", run.errorMessage)
        assertEquals("stopped", repo.getSyncSummary().single().lastRunStatus)
    }

    @Test
    fun aStoppedRunIsNotCountedAsFailedAndSummarisesAsStopped() {
        val (repo, _) = stoppedAfterTwo()
        val sum = repo.summarizeRecentRuns(days = 36500)
        assertEquals("a stop is not a failed run", 0, sum.failedRuns)
        assertEquals("stopped", sum.lastStatus)
        assertNotEquals("failed", sum.lastStatus)
        assertEquals(2, sum.lastMessagesSynced)
        assertEquals(1, sum.totalRuns)
    }

    @Test
    fun aStoppedRunNeverFeedsTheOverlapBaselineOrBecomesTheLastSuccess() {
        val (repo, _) = stoppedAfterTwo()
        assertNull(repo.getLastSuccessfulRun("meera_iyer"))
        assertNull(repo.getLastSyncedTs("meera_iyer"))
        val run = repo.getRecentRuns(days = 36500).single()
        assertNull(run.lastSyncedTs)
        assertNull(run.lastSyncedHash)
    }

    @Test
    fun theApiShowsStoppedInTheLogNotFailed() {
        val (repo, _) = stoppedAfterTwo()
        val run = repo.getRecentRuns(days = 36500).single()
        val shown = if (run.stopped) "stopped" else run.status
        assertEquals("stopped", shown)
        // And a stopped run is not "uneventful": it uploaded something and is not complete.
        assertFalse(repo.isUneventfulRun(run.status, run.messagesSynced))
    }

    @Test
    fun aRealPushFailureStillReadsAsFailedWithItsError() {
        val s = setup(chatText(20, 21, 22))
        val repo = newRepo(s.root, "state.db")
        SyncManager(
            repo = repo, transport = Failing(), inboxDir = s.inbox, processedDir = s.processed, sleeper = { },
        ).run()
        val run = repo.getRecentRuns(days = 36500).single()
        assertEquals("failed", run.status)
        assertFalse("a real failure is never marked stopped", run.stopped)
        assertTrue(run.errorMessage!!.contains("simulated server refusal"))
        val sum = repo.summarizeRecentRuns(days = 36500)
        assertEquals(1, sum.failedRuns)
        assertEquals("failed", sum.lastStatus)
        assertEquals("failed", repo.getSyncSummary().single().lastRunStatus)
    }

    @Test
    fun theNextRunAfterAStopSendsOnlyTheMissingMessagesAndCompletes() {
        val (repo, s) = stoppedAfterTwo()
        assertTrue("the export stays", File(s.inbox, fileName).exists())
        val second = Recorder()
        SyncManager(
            repo = repo, transport = second, inboxDir = s.inbox, processedDir = s.processed, sleeper = { },
        ).run()
        assertEquals(3, second.inserts)
        val runs = repo.getRecentRuns(days = 36500).sortedBy { it.runId }
        assertEquals(2, runs.size)
        assertTrue(runs[0].stopped)
        assertEquals("complete", runs[1].status)
        assertFalse(runs[1].stopped)
        assertEquals(5, repo.countArchivedMessages("meera_iyer"))
        assertNotEquals(null, repo.getLastSuccessfulRun("meera_iyer"))
        assertEquals(0, repo.summarizeRecentRuns(days = 36500).failedRuns)
    }

    @Test
    fun anExistingDatabaseKeepsItsRowsAndOldStopsAreRecovered() {
        val dir = tmp.newFolder()
        val path = File(dir, "old.db").absolutePath
        // A database as 2.2.0 / early 2.9.0 left it: no `stopped` column.
        SqliteJdbcStateDb(path).use { db ->
            db.execScript(
                """
                CREATE TABLE chats (chat_id TEXT PRIMARY KEY, display_name TEXT NOT NULL, gmail_thread_id TEXT,
                    gmail_label_id TEXT, anchor_message_id TEXT, source_filename TEXT NOT NULL,
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
                CREATE TABLE sync_runs (run_id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('pending','complete','failed')),
                    trigger TEXT NOT NULL DEFAULT 'manual', last_synced_ts TEXT, last_synced_hash TEXT,
                    messages_parsed INTEGER NOT NULL DEFAULT 0, messages_synced INTEGER NOT NULL DEFAULT 0,
                    messages_skipped INTEGER NOT NULL DEFAULT 0, messages_cutoff INTEGER NOT NULL DEFAULT 0,
                    error_message TEXT, started_at TEXT NOT NULL, completed_at TEXT);
                INSERT INTO chats VALUES ('meera_iyer','Meera Iyer',NULL,NULL,NULL,'a.txt','2026-10-01T09:00:00','2026-10-01T09:00:00');
                INSERT INTO sync_runs (chat_id,status,messages_synced,started_at,completed_at)
                    VALUES ('meera_iyer','complete',7,'2026-10-01T09:01:00','2026-10-01T09:02:00');
                INSERT INTO sync_runs (chat_id,status,error_message,started_at,completed_at)
                    VALUES ('meera_iyer','failed','stopped by the user after 54 of 120 messages','2026-10-02T09:01:00','2026-10-02T09:02:00');
                INSERT INTO sync_runs (chat_id,status,error_message,started_at,completed_at)
                    VALUES ('meera_iyer','failed','Mail push failed','2026-10-03T09:01:00','2026-10-03T09:02:00');
                """.trimIndent(),
            )
        }
        val repo = StateRepository { SqliteJdbcStateDb(path) }
        repo.initDb()
        repo.initDb() // idempotent
        val runs = repo.getRecentRuns(days = 36500).sortedBy { it.runId }
        assertEquals(3, runs.size)
        assertEquals("complete", runs[0].status)
        assertEquals(7, runs[0].messagesSynced)
        assertFalse(runs[0].stopped)
        assertTrue(runs[1].stopped)
        assertEquals(54, runs[1].messagesSynced)
        assertEquals("a genuine failure stays a failure", "failed", runs[2].status)
        assertFalse(runs[2].stopped)
        assertEquals(0, runs[2].messagesSynced)
    }

    @Test
    fun theStoppedFlagSurvivesABackupRoundTrip() {
        val (_, s) = stoppedAfterTwo()
        val openDb: OpenDb = { f -> SqliteJdbcStateDb(f.absolutePath) }
        // The seeded state.db is at s.root/state.db; the bundle code wants root/data/<db>.
        val src = tmp.newFolder()
        bundleDbPath(src).parentFile.mkdirs()
        File(s.root, "state.db").copyTo(bundleDbPath(src), overwrite = true)
        val bundle = File(tmp.newFolder(), "x.cmsbackup")
        val exported = exportBundle(src, bundle, openDb, appVersion = "2.9.0")
        assertEquals(1, exported.counts.runs)

        val dst = tmp.newFolder()
        bundleDbPath(dst).parentFile.mkdirs()
        val imported = importBundle(dst, bundle, openDb)
        assertTrue(imported.toString(), imported.ok)

        val repo = StateRepository { openDb(bundleDbPath(dst)) }
        repo.initDb()
        val run = repo.getRecentRuns(days = 36500).single()
        assertTrue("the stopped flag came across", run.stopped)
        assertEquals(2, run.messagesSynced)
        assertEquals(0, repo.summarizeRecentRuns(days = 36500).failedRuns)
        assertEquals("stopped", repo.summarizeRecentRuns(days = 36500).lastStatus)
    }

    @Test
    fun anOldBundleWithoutTheColumnMergesAsNotStopped() {
        val openDb: OpenDb = { f -> SqliteJdbcStateDb(f.absolutePath) }
        val dir = tmp.newFolder()
        val incoming = File(dir, "incoming.db")
        SqliteJdbcStateDb(incoming.absolutePath).use { db ->
            db.execScript(
                """
                CREATE TABLE chats (chat_id TEXT PRIMARY KEY, display_name TEXT NOT NULL, gmail_thread_id TEXT,
                    gmail_label_id TEXT, anchor_message_id TEXT, source_filename TEXT NOT NULL,
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
                CREATE TABLE sync_runs (run_id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('pending','complete','failed')),
                    trigger TEXT NOT NULL DEFAULT 'manual', last_synced_ts TEXT, last_synced_hash TEXT,
                    messages_parsed INTEGER NOT NULL DEFAULT 0, messages_synced INTEGER NOT NULL DEFAULT 0,
                    messages_skipped INTEGER NOT NULL DEFAULT 0, error_message TEXT,
                    started_at TEXT NOT NULL, completed_at TEXT);
                INSERT INTO chats VALUES ('meera_iyer','Meera Iyer',NULL,NULL,NULL,'a.txt','2026-10-01T09:00:00','2026-10-01T09:00:00');
                INSERT INTO sync_runs (chat_id,status,error_message,started_at,completed_at)
                    VALUES ('meera_iyer','failed','Mail push failed','2026-10-03T09:01:00','2026-10-03T09:02:00');
                """.trimIndent(),
            )
        }
        // importBundle upgrades the incoming file with initDb before merging; do the same here.
        StateRepository { openDb(incoming) }.initDb()
        val target = File(dir, "target.db")
        StateRepository { openDb(target) }.initDb()
        mergeDb(target, incoming, openDb)
        val repo = StateRepository { openDb(target) }
        val run = repo.getRecentRuns(days = 36500).single()
        assertEquals("failed", run.status)
        assertFalse(run.stopped)
    }
}
