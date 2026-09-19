package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager

/**
 * JUnit twin of `tests/test_state.py`. One test per Python test (same
 * intent, Kotlin naming conventions), plus a handful of explicit negative
 * tests called out in the porting brief that the Python suite does not
 * already cover verbatim as their own named test.
 *
 * Every test opens its own fresh SQLite file inside [tmp]'s managed
 * directory, mirroring how `tests/conftest.py`'s `db_path` fixture gives
 * each Python test an isolated database. No file here is ever written
 * under the repository -- see the porting brief's hard constraint on
 * temporary database files.
 */
class StateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun newRepo(): StateRepository {
        val path = File(tmp.newFolder(), "sync_state.db").absolutePath
        val repo = StateRepository { SqliteJdbcStateDb(path) }
        repo.initDb()
        return repo
    }

    // -----------------------------------------------------------------
    // init_db
    // -----------------------------------------------------------------

    @Test
    fun `init db is idempotent`() {
        val repo = newRepo()
        repo.initDb() // second call, same db — must not throw
    }

    // -----------------------------------------------------------------
    // chats
    // -----------------------------------------------------------------

    @Test
    fun `upsert chat round trips and updates`() {
        val repo = newRepo()
        repo.upsertChat("chat1", "Chat One", "chat1.txt")
        val row = repo.getChat("chat1")!!
        assertEquals("Chat One", row.displayName)
        assertNull(row.gmailThreadId)

        repo.upsertChat("chat1", "Chat One", "chat1.txt", gmailThreadId = "thread-1")
        val rows = repo.listChats()
        assertEquals(1, rows.size)
        assertEquals("thread-1", rows[0].gmailThreadId)
    }

    // -----------------------------------------------------------------
    // compute_message_hash
    // -----------------------------------------------------------------

    @Test
    fun `compute message hash is deterministic and sensitive`() {
        val h1 = StateRepository.computeMessageHash("chat1", "2025-03-14T09:41:00", "Alice", "Hello")
        val h2 = StateRepository.computeMessageHash("chat1", "2025-03-14T09:41:00", "Alice", "Hello")
        assertEquals(h1, h2)

        val changed = listOf(
            listOf("chat2", "2025-03-14T09:41:00", "Alice", "Hello"),
            listOf("chat1", "2025-03-14T09:41:01", "Alice", "Hello"),
            listOf("chat1", "2025-03-14T09:41:00", "Bob", "Hello"),
            listOf("chat1", "2025-03-14T09:41:00", "Alice", "Hello!"),
        )
        for ((chatId, ts, sender, body) in changed) {
            assertNotEquals(h1, StateRepository.computeMessageHash(chatId, ts, sender, body))
        }
    }

    @Test
    fun `negative an edited message body produces a different hash`() {
        val original = StateRepository.computeMessageHash("chat1", "2025-03-14T09:41:00", "Meera Iyer", "See you at 6pm")
        val edited = StateRepository.computeMessageHash("chat1", "2025-03-14T09:41:00", "Meera Iyer", "See you at 6:30pm")
        assertNotEquals(original, edited)
    }

    // -----------------------------------------------------------------
    // message hashes
    // -----------------------------------------------------------------

    @Test
    fun `hash exists and insert round trips`() {
        val repo = newRepo()
        repo.upsertChat("chat1", "Chat One", "chat1.txt")
        val runId = repo.startSyncRun("chat1")
        val h = StateRepository.computeMessageHash("chat1", "2025-03-14T09:41:00", "Alice", "Hello")

        assertFalse(repo.hashExists(h))
        repo.insertMessageHashes(listOf(StateRepository.HashEntry(h, "chat1", "2025-03-14T09:41:00", runId)))
        assertTrue(repo.hashExists(h))
    }

    // -----------------------------------------------------------------
    // sync run lifecycle
    // -----------------------------------------------------------------

    @Test
    fun `sync run lifecycle complete`() {
        val repo = newRepo()
        repo.upsertChat("chat1", "Chat One", "chat1.txt")
        val runId = repo.startSyncRun("chat1")
        repo.completeSyncRun(runId, "2025-03-14T09:41:00", "deadbeef", 3, 3, 0)

        val lastRun = repo.getLastSuccessfulRun("chat1")!!
        assertEquals(runId, lastRun.runId)
        assertEquals("complete", lastRun.status)
    }

    @Test
    fun `sync run lifecycle failed is not pending`() {
        val repo = newRepo()
        repo.upsertChat("chat1", "Chat One", "chat1.txt")
        val runId = repo.startSyncRun("chat1")
        repo.failSyncRun(runId, "network error")

        assertNull(repo.getLastSuccessfulRun("chat1"))
        assertFalse(repo.getPendingRuns().any { it.runId == runId })
    }

    @Test
    fun `get pending runs finds crashed run`() {
        val repo = newRepo()
        repo.upsertChat("chat1", "Chat One", "chat1.txt")
        val runId = repo.startSyncRun("chat1")

        assertTrue(repo.getPendingRuns().any { it.runId == runId })
    }

    @Test
    fun `get recent runs excludes runs outside window`() {
        val repo = newRepo()
        repo.upsertChat("chat1", "Chat One", "chat1.txt")
        val runId = repo.startSyncRun("chat1", trigger = "watched_folder")

        val recent = repo.getRecentRuns(90)
        assertEquals(1, recent.size)
        assertEquals(runId, recent[0].runId)
        assertEquals("watched_folder", recent[0].trigger)
        assertEquals("Chat One", recent[0].displayName)

        assertTrue(repo.getRecentRuns(-1).isEmpty())
    }

    // -----------------------------------------------------------------
    // is_uneventful_run (pure)
    // -----------------------------------------------------------------

    @Test
    fun `is uneventful run never hides a failure`() {
        val repo = newRepo()
        assertTrue(repo.isUneventfulRun("complete", 0))
        assertTrue(repo.isUneventfulRun("complete", null))
        assertFalse(repo.isUneventfulRun("complete", 3))
        assertFalse(repo.isUneventfulRun("failed", 0))
        assertFalse(repo.isUneventfulRun("pending", 0))
    }

    // -----------------------------------------------------------------
    // summarize_recent_runs
    // -----------------------------------------------------------------

    @Test
    fun `summarize recent runs is empty before anything runs`() {
        val repo = newRepo()
        val summary = repo.summarizeRecentRuns()
        assertEquals(0, summary.totalRuns)
        assertEquals(0, summary.failedRuns)
        assertNull(summary.lastStatus)
        assertEquals(90, summary.windowDays)
    }

    @Test
    fun `summarize recent runs reports the last finished run`() {
        val repo = newRepo()
        repo.upsertChat("chat1", "Chat One", "chat1.txt")
        val runId = repo.startSyncRun("chat1")
        repo.completeSyncRun(runId, "2025-03-14T09:41:00", "deadbeef", 5, 3, 2)

        val summary = repo.summarizeRecentRuns()
        assertEquals("complete", summary.lastStatus)
        assertEquals("Chat One", summary.lastDisplayName)
        assertEquals(3, summary.lastMessagesSynced)
        assertEquals(2, summary.lastMessagesSkipped)
        assertEquals(0, summary.failedRuns)
        assertEquals(0, summary.runningRuns)
    }

    @Test
    fun `summarize recent runs counts failures and keeps the last outcome`() {
        val repo = newRepo()
        repo.upsertChat("chat1", "Chat One", "chat1.txt")
        val failed = repo.startSyncRun("chat1")
        repo.failSyncRun(failed, "network error")
        repo.startSyncRun("chat1") // still pending

        val summary = repo.summarizeRecentRuns()
        assertEquals(2, summary.totalRuns)
        assertEquals(1, summary.failedRuns)
        assertEquals(1, summary.runningRuns)
        assertEquals("failed", summary.lastStatus)
    }

    // -----------------------------------------------------------------
    // reset_chat
    // -----------------------------------------------------------------

    @Test
    fun `reset chat isolates other chats`() {
        val repo = newRepo()
        repo.upsertChat("chat1", "Chat One", "chat1.txt")
        repo.upsertChat("chat2", "Chat Two", "chat2.txt")
        repo.updateChatGmailIds("chat1", gmailThreadId = "t1", gmailLabelId = "l1")
        repo.updateChatGmailIds("chat2", gmailThreadId = "t2", gmailLabelId = "l2")
        val runId = repo.startSyncRun("chat1")
        val h = StateRepository.computeMessageHash("chat1", "2025-03-14T09:41:00", "Alice", "Hello")
        repo.insertMessageHashes(listOf(StateRepository.HashEntry(h, "chat1", "2025-03-14T09:41:00", runId)))

        repo.resetChat("chat1", confirmedMailboxCleared = true)

        val chat1 = repo.getChat("chat1")!!
        assertNull(chat1.gmailThreadId)
        assertFalse(repo.hashExists(h))

        val chat2 = repo.getChat("chat2")!!
        assertEquals("t2", chat2.gmailThreadId)
    }

    @Test
    fun `reset chat refuses while mail is archived`() {
        val repo = newRepo()
        repo.upsertChat("chat1", "Chat One", "chat1.txt")
        repo.updateChatGmailIds("chat1", gmailThreadId = "t1", gmailLabelId = "l1")
        val runId = repo.startSyncRun("chat1")
        val h = StateRepository.computeMessageHash("chat1", "2025-03-14T09:41:00", "Alice", "Hello")
        repo.insertMessageHashes(listOf(StateRepository.HashEntry(h, "chat1", "2025-03-14T09:41:00", runId)))

        assertEquals(1, repo.countArchivedMessages("chat1"))

        try {
            repo.resetChat("chat1")
            fail("expected MailboxNotClearedError")
        } catch (e: StateRepository.MailboxNotClearedError) {
            assertEquals(1, e.archivedCount)
        }

        assertTrue(repo.hashExists(h))
        assertEquals("t1", repo.getChat("chat1")!!.gmailThreadId)
    }

    @Test
    fun `reset chat allows reset when nothing archived`() {
        val repo = newRepo()
        repo.upsertChat("chat1", "Chat One", "chat1.txt")
        repo.updateChatGmailIds("chat1", gmailThreadId = "t1", gmailLabelId = "l1")

        assertEquals(0, repo.countArchivedMessages("chat1"))
        repo.resetChat("chat1")

        assertNull(repo.getChat("chat1")!!.gmailThreadId)
    }

    // -----------------------------------------------------------------
    // the dedupe sweep
    // -----------------------------------------------------------------

    /** Winds a database back to look like an install made before the sweep
     * existed -- the Kotlin twin of `_pretend_db_predates_the_sweep`. */
    private fun pretendDbPredatesTheSweep(path: String) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { it.execute("PRAGMA user_version = 0") }
        }
    }

    /** Appends a byte-identical copy of an existing run -- the Kotlin twin
     * of `_clone_run`. */
    private fun cloneRun(path: String, runId: Long): Long {
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            val cols = StateRepository.RUN_NATURAL_KEY.joinToString(", ")
            val row: MutableMap<String, Any?> = LinkedHashMap()
            conn.createStatement().executeQuery("SELECT $cols FROM sync_runs WHERE run_id = $runId").use { rs ->
                rs.next()
                for (c in StateRepository.RUN_NATURAL_KEY) row[c] = rs.getObject(c)
            }
            val placeholders = StateRepository.RUN_NATURAL_KEY.joinToString(", ") { "?" }
            val stmt = conn.prepareStatement(
                "INSERT INTO sync_runs ($cols) VALUES ($placeholders)",
                java.sql.Statement.RETURN_GENERATED_KEYS,
            )
            StateRepository.RUN_NATURAL_KEY.forEachIndexed { i, c -> stmt.setObject(i + 1, row[c]) }
            stmt.executeUpdate()
            stmt.generatedKeys.use { keys ->
                keys.next()
                return keys.getLong(1)
            }
        }
    }

    @Test
    fun `init db sweeps runs an old restore duplicated`() {
        val dbFile = File(tmp.newFolder(), "sync_state.db")
        val path = dbFile.absolutePath
        val repo = StateRepository { SqliteJdbcStateDb(path) }
        repo.initDb()

        repo.upsertChat("chat1", "Chat One", "chat1.txt")
        val runId = repo.startSyncRun("chat1")
        val h = StateRepository.computeMessageHash("chat1", "2025-03-14T09:41:00", "Alice", "Hello")
        repo.insertMessageHashes(listOf(StateRepository.HashEntry(h, "chat1", "2025-03-14T09:41:00", runId)))
        repo.completeSyncRun(runId, "2025-03-14T09:41:00", h, 1, 1, 0)

        val copyId = cloneRun(path, runId)
        val h2 = StateRepository.computeMessageHash("chat1", "2025-03-14T09:42:00", "Alice", "Again")
        repo.insertMessageHashes(listOf(StateRepository.HashEntry(h2, "chat1", "2025-03-14T09:42:00", copyId)))
        assertEquals(2, repo.getRecentRuns().size)

        pretendDbPredatesTheSweep(path)
        repo.initDb()

        val runs = repo.getRecentRuns()
        assertEquals(1, runs.size)
        assertEquals(runId, runs[0].runId)
        assertTrue(repo.hashExists(h))
        assertTrue(repo.hashExists(h2))
        assertEquals(setOf(h, h2), repo.getHashesForRun(runId))
    }

    @Test
    fun `a real second run is not mistaken for a duplicate`() {
        val dbFile = File(tmp.newFolder(), "sync_state.db")
        val path = dbFile.absolutePath
        val repo = StateRepository { SqliteJdbcStateDb(path) }
        repo.initDb()

        repo.upsertChat("chat1", "Chat One", "chat1.txt")
        val first = repo.startSyncRun("chat1")
        repo.completeSyncRun(first, "2025-03-14T09:41:00", "a", 1, 1, 0)
        val second = repo.startSyncRun("chat1")
        repo.completeSyncRun(second, "2025-03-15T09:41:00", "b", 2, 1, 1)

        pretendDbPredatesTheSweep(path)
        repo.initDb()

        assertEquals(2, repo.getRecentRuns().size)
    }

    @Test
    fun `the sweep runs once not on every start`() {
        val dbFile = File(tmp.newFolder(), "sync_state.db")
        val path = dbFile.absolutePath
        val repo = StateRepository { SqliteJdbcStateDb(path) }
        repo.initDb()

        repo.upsertChat("chat1", "Chat One", "chat1.txt")
        val runId = repo.startSyncRun("chat1")
        repo.completeSyncRun(runId, "2025-03-14T09:41:00", "a", 1, 1, 0)
        repo.initDb() // sweep happens here

        cloneRun(path, runId)
        repo.initDb()

        assertEquals(2, repo.getRecentRuns().size)
    }

    // -----------------------------------------------------------------
    // v1 -> current migration
    // -----------------------------------------------------------------

    private val v1Ddl = """
        PRAGMA journal_mode = WAL;

        CREATE TABLE chats (
            chat_id          TEXT PRIMARY KEY,
            display_name     TEXT NOT NULL,
            source_filename  TEXT,
            gmail_thread_id  TEXT,
            gmail_label_id   TEXT,
            created_at       TEXT NOT NULL,
            updated_at       TEXT NOT NULL
        );

        CREATE TABLE sync_runs (
            run_id           INTEGER PRIMARY KEY AUTOINCREMENT,
            chat_id          TEXT NOT NULL REFERENCES chats(chat_id),
            status           TEXT NOT NULL CHECK(status IN ('pending','complete','failed')),
            trigger          TEXT NOT NULL DEFAULT 'manual',
            last_synced_ts   TEXT,
            last_synced_hash TEXT,
            messages_parsed  INTEGER NOT NULL DEFAULT 0,
            messages_synced  INTEGER NOT NULL DEFAULT 0,
            messages_skipped INTEGER NOT NULL DEFAULT 0,
            error_message    TEXT,
            started_at       TEXT NOT NULL,
            completed_at     TEXT
        );

        CREATE TABLE message_hashes (
            hash        TEXT PRIMARY KEY,
            chat_id     TEXT NOT NULL,
            message_ts  TEXT NOT NULL,
            run_id      INTEGER NOT NULL,
            created_at  TEXT NOT NULL DEFAULT (datetime('now'))
        );
    """.trimIndent()

    /** Builds a database shaped the way version 1 shipped it -- written out
     * here rather than derived from [StateRepository.DDL], on purpose (see
     * `_build_a_v1_database`'s Python docstring: deriving it from today's
     * schema would make the migration test vacuous the moment the DDL
     * changes again). */
    private fun buildAV1Database(path: String): Long {
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { stmt ->
                v1Ddl.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { stmt.execute(it) }
            }
            conn.createStatement().use {
                it.execute(
                    "INSERT INTO chats (chat_id, display_name, source_filename, created_at, updated_at) " +
                        "VALUES ('chat1', 'Chat One', 'chat1.txt', '2025-03-01T00:00:00', '2025-03-01T00:00:00')",
                )
            }
            val stmt = conn.prepareStatement(
                "INSERT INTO sync_runs (chat_id, status, trigger, last_synced_ts, last_synced_hash, " +
                    "messages_parsed, messages_synced, messages_skipped, started_at, completed_at) " +
                    "VALUES ('chat1', 'complete', 'manual', '2025-03-14T09:41:00', 'deadbeef', " +
                    "3, 3, 0, '2025-03-14T09:40:00', '2025-03-14T09:42:00')",
                java.sql.Statement.RETURN_GENERATED_KEYS,
            )
            stmt.executeUpdate()
            val runId = stmt.generatedKeys.use { it.next(); it.getLong(1) }
            conn.createStatement().use { it.execute("PRAGMA user_version = 1") }
            return runId
        }
    }

    @Test
    fun `a version 1 database gains the cutoff table and column`() {
        val dbFile = File(tmp.newFolder(), "v1.db")
        val path = dbFile.absolutePath
        val runId = buildAV1Database(path)

        val repo = StateRepository { SqliteJdbcStateDb(path) }
        repo.initDb()

        val run = repo.getRun(runId)!!
        assertEquals("2025-03-14T09:41:00", run.lastSyncedTs)
        assertEquals(3, run.messagesParsed)
        assertEquals(0, run.messagesCutoff)

        repo.setChatCutoff("chat1", "2026-01-01")
        assertEquals("2026-01-01T00:00:00", repo.getChatCutoff("chat1"))

        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().executeQuery("PRAGMA user_version").use { rs ->
                rs.next()
                assertEquals(StateRepository.SCHEMA_VERSION, rs.getInt(1))
            }
        }
    }

    // -----------------------------------------------------------------
    // negative: user_version is never silently downgraded
    // -----------------------------------------------------------------

    @Test
    fun `negative a newer user_version than code knows is not silently downgraded`() {
        val dbFile = File(tmp.newFolder(), "future.db")
        val path = dbFile.absolutePath
        val repo = StateRepository { SqliteJdbcStateDb(path) }
        repo.initDb()

        // Simulate a DB stamped by a future version of the app.
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { it.execute("PRAGMA user_version = 99") }
        }

        // Re-running today's init_db twin must not touch a version number it
        // does not recognise -- it only ever raises version < _SCHEMA_VERSION
        // up to _SCHEMA_VERSION, and version 99 is not less than that. This
        // matches state.py's init_db literally: `if version < _SCHEMA_VERSION`.
        repo.initDb()

        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().executeQuery("PRAGMA user_version").use { rs ->
                rs.next()
                assertEquals(99, rs.getInt(1))
            }
        }
    }

    // -----------------------------------------------------------------
    // cutoff
    // -----------------------------------------------------------------

    @Test
    fun `a cutoff normalises to midnight on the day asked for`() {
        assertEquals("2026-01-01T00:00:00", normaliseCutoff("2026-01-01"))
        assertEquals("2026-01-01T00:00:00", normaliseCutoff("2026-01-01T17:30:00"))
        assertEquals("2026-01-01T00:00:00", normaliseCutoff("  2026-01-01  "))
    }

    @Test
    fun `blank and missing mean the same thing`() {
        assertNull(normaliseCutoff(null))
        assertNull(normaliseCutoff(""))
        assertNull(normaliseCutoff("   "))

        val repo = newRepo()
        repo.setChatCutoff("chat1", "2026-01-01")
        repo.setChatCutoff("chat1", "")
        assertNull(repo.getChatCutoff("chat1"))
    }

    @Test
    fun `a date the app cannot compare is refused`() {
        try {
            normaliseCutoff("01/01/2026")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        val repo = newRepo()
        try {
            repo.setChatCutoff("chat1", "next tuesday")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // -----------------------------------------------------------------
    // Negative tests (held fix (b)) -- the wrong behaviour (silently
    // accepting or clamping an invalid cutoff instead of rejecting it)
    // must NOT happen. See NormaliseCutoffGoldenParityTest for the full
    // Python-parity sweep; these are the hand-written, explicit-intent
    // twin covering the specific bugs this PR fixed.
    // -----------------------------------------------------------------

    @Test
    fun `an unpadded month or day is accepted, not rejected as malformed`() {
        // Python's strptime("%Y-%m-%d") accepts 1-2 digit month/day; the
        // previous Kotlin implementation (a bare DateTimeFormatter pattern
        // parse) demanded exactly 2 digits and rejected these. Python does
        // NOT zero-pad the output either -- it appends "T00:00:00" to the
        // original (possibly unpadded) substring verbatim, so Kotlin must
        // match that, not reformat to a padded date.
        assertEquals("2024-1-5T00:00:00", normaliseCutoff("2024-1-5"))
        assertEquals("2024-01-5T00:00:00", normaliseCutoff("2024-01-5"))
        assertEquals("2024-1-05T00:00:00", normaliseCutoff("2024-1-05"))
    }

    @Test
    fun `a calendar-invalid date is rejected, not silently accepted or clamped`() {
        // The previous Kotlin implementation only checked the pattern shape
        // via DateTimeFormatter.parse(), which never resolves into a real
        // LocalDate, so it happily "accepted" 30 February and 29 February
        // on a non-leap year instead of throwing -- exactly the silent
        // acceptance this test exists to catch a regression back into.
        for (bad in listOf("2024-02-30", "2023-02-29", "2024-13-01", "2024-00-10", "2024-04-31")) {
            try {
                val result = normaliseCutoff(bad)
                fail("expected '$bad' to be REJECTED, but it was silently accepted as '$result'")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        // A leap-year Feb 29 must still be accepted -- the fix must reject
        // calendar-invalid dates without becoming over-strict.
        assertEquals("2024-02-29T00:00:00", normaliseCutoff("2024-02-29"))
    }

    @Test
    fun `computeMessageHash for an empty body differs from a non-empty body`() {
        // Guards against a degenerate implementation that ignores the body
        // field (or collapses "" and any other value to the same hash).
        val emptyBody = StateRepository.computeMessageHash("chat1", "2025-03-14T09:41:00", "Alice", "")
        val nonEmptyBody = StateRepository.computeMessageHash("chat1", "2025-03-14T09:41:00", "Alice", "Hello")
        assertNotEquals(emptyBody, nonEmptyBody)
        // And is still deterministic for the same empty input.
        assertEquals(emptyBody, StateRepository.computeMessageHash("chat1", "2025-03-14T09:41:00", "Alice", ""))
    }

    @Test
    fun `a cutoff can be set on a chat that has never synced`() {
        val repo = newRepo()
        repo.setChatCutoff("never-synced", "2026-01-01")

        assertEquals("2026-01-01T00:00:00", repo.getChatCutoff("never-synced"))
        assertTrue(repo.listChats().isEmpty())
    }

    @Test
    fun `setting a cutoff twice replaces rather than duplicates`() {
        val repo = newRepo()
        repo.setChatCutoff("chat1", "2026-01-01")
        repo.setChatCutoff("chat1", "2026-06-01")

        assertEquals("2026-06-01T00:00:00", repo.getChatCutoff("chat1"))
        assertEquals(mapOf("chat1" to "2026-06-01T00:00:00"), repo.listChatCutoffs())
    }

    @Test
    fun `deleting a chat takes its cutoff with it`() {
        val repo = newRepo()
        repo.upsertChat("chat1", "Chat One", "chat1.txt")
        repo.setChatCutoff("chat1", "2026-01-01")

        repo.deleteChat("chat1")

        assertNull(repo.getChatCutoff("chat1"))
    }

    @Test
    fun `the cutoff count is stored apart from the skipped count`() {
        val repo = newRepo()
        repo.upsertChat("chat1", "Chat One", "chat1.txt")
        val runId = repo.startSyncRun("chat1")
        repo.completeSyncRun(
            runId,
            lastSyncedTs = "2026-03-14T09:41:00",
            lastSyncedHash = "deadbeef",
            messagesParsed = 10,
            messagesSynced = 3,
            messagesSkipped = 2,
            messagesCutoff = 5,
        )

        val run = repo.getRun(runId)!!
        assertEquals(2, run.messagesSkipped)
        assertEquals(5, run.messagesCutoff)
    }

    @Test
    fun `the cutoff count stays out of the run natural key`() {
        assertFalse(StateRepository.RUN_NATURAL_KEY.contains("messages_cutoff"))
    }

    // -----------------------------------------------------------------
    // app_state
    // -----------------------------------------------------------------

    @Test
    fun `app state round trips`() {
        val repo = newRepo()
        assertNull(repo.getAppState(StateRepository.SELF_SENDER_LEARNED))
        repo.setAppState(StateRepository.SELF_SENDER_LEARNED, "Priya Nair")
        assertEquals("Priya Nair", repo.getAppState(StateRepository.SELF_SENDER_LEARNED))
    }

    @Test
    fun `app state writes replace rather than duplicate`() {
        val repo = newRepo()
        repo.setAppState(StateRepository.SELF_SENDER_LEARNED, "Priya Nair")
        repo.setAppState(StateRepository.SELF_SENDER_LEARNED, "Priyanka Nair")
        assertEquals("Priyanka Nair", repo.getAppState(StateRepository.SELF_SENDER_LEARNED))
    }

    @Test
    fun `a blank value clears the key - empty string`() = blankValueClearsKey("")

    @Test
    fun `a blank value clears the key - spaces`() = blankValueClearsKey("   ")

    @Test
    fun `a blank value clears the key - null`() = blankValueClearsKey(null)

    private fun blankValueClearsKey(blank: String?) {
        val repo = newRepo()
        repo.setAppState(StateRepository.SELF_SENDER_OVERRIDE, "Priya Nair")
        repo.setAppState(StateRepository.SELF_SENDER_OVERRIDE, blank)
        assertNull(repo.getAppState(StateRepository.SELF_SENDER_OVERRIDE))
    }

    @Test
    fun `app state keys do not collide`() {
        val repo = newRepo()
        repo.setAppState(StateRepository.SELF_SENDER_OVERRIDE, "Priya")
        repo.setAppState(StateRepository.SELF_SENDER_LEARNED, "Priya Nair")
        assertEquals("Priya", repo.getAppState(StateRepository.SELF_SENDER_OVERRIDE))
        assertEquals("Priya Nair", repo.getAppState(StateRepository.SELF_SENDER_LEARNED))
    }

    // -----------------------------------------------------------------
    // chat_senders
    // -----------------------------------------------------------------

    @Test
    fun `record and list chat senders`() {
        val repo = newRepo()
        repo.recordChatSenders("chat1", mapOf("Meera Iyer" to 3, "Rohan Mehta" to 1), seenTs = "2026-01-01T00:00:00")

        val rows = repo.listChatSenders("chat1")
        assertEquals(listOf("Meera Iyer", "Rohan Mehta"), rows.map { it.sender })
        assertEquals(3, rows[0].msgCount)
        assertEquals("2026-01-01T00:00:00", rows[0].firstSeen)
        assertEquals("2026-01-01T00:00:00", rows[0].lastSeen)
    }

    @Test
    fun `recording chat senders twice increments rather than replaces`() {
        val repo = newRepo()
        repo.recordChatSenders("chat1", mapOf("Meera Iyer" to 3), seenTs = "2026-01-01T00:00:00")
        repo.recordChatSenders("chat1", mapOf("Meera Iyer" to 2), seenTs = "2026-02-01T00:00:00")

        val rows = repo.listChatSenders("chat1")
        assertEquals(1, rows.size)
        assertEquals(5, rows[0].msgCount)
        assertEquals("2026-01-01T00:00:00", rows[0].firstSeen)
        assertEquals("2026-02-01T00:00:00", rows[0].lastSeen)
    }

    @Test
    fun `chat senders are ordered by msg count descending`() {
        val repo = newRepo()
        repo.recordChatSenders("chat1", mapOf("Rohan Mehta" to 1, "Priya Nair" to 9, "Meera Iyer" to 4))

        val rows = repo.listChatSenders("chat1")
        assertEquals(listOf("Priya Nair", "Meera Iyer", "Rohan Mehta"), rows.map { it.sender })
    }

    @Test
    fun `list chat senders with no chat id covers every chat`() {
        val repo = newRepo()
        repo.recordChatSenders("chat1", mapOf("Meera Iyer" to 2))
        repo.recordChatSenders("chat2", mapOf("Rohan Mehta" to 5))

        val rows = repo.listChatSenders()
        assertEquals(
            setOf("chat1" to "Meera Iyer", "chat2" to "Rohan Mehta"),
            rows.map { it.chatId to it.sender }.toSet(),
        )
    }

    @Test
    fun `a zero count still records a name never seen before`() {
        val repo = newRepo()
        repo.recordChatSenders("chat1", mapOf("Meera Iyer" to 0), seenTs = "2026-01-01T00:00:00")

        val rows = repo.listChatSenders("chat1")
        assertEquals(1, rows.size)
        assertEquals("Meera Iyer", rows[0].sender)
        assertEquals(0, rows[0].msgCount)
        assertEquals("2026-01-01T00:00:00", rows[0].firstSeen)
        assertEquals("2026-01-01T00:00:00", rows[0].lastSeen)
    }

    @Test
    fun `a zero count never touches an existing row`() {
        val repo = newRepo()
        repo.recordChatSenders("chat1", mapOf("Meera Iyer" to 3), seenTs = "2026-01-01T00:00:00")
        repo.recordChatSenders("chat1", mapOf("Meera Iyer" to 0), seenTs = "2026-03-01T00:00:00")

        val rows = repo.listChatSenders("chat1")
        assertEquals(1, rows.size)
        assertEquals(3, rows[0].msgCount)
        assertEquals("2026-01-01T00:00:00", rows[0].firstSeen)
        assertEquals("2026-01-01T00:00:00", rows[0].lastSeen)
    }

    @Test
    fun `deleting a chat takes its senders with it`() {
        val repo = newRepo()
        repo.upsertChat("chat1", "Chat One", "chat1.txt")
        repo.recordChatSenders("chat1", mapOf("Meera Iyer" to 1))

        repo.deleteChat("chat1")

        assertTrue(repo.listChatSenders("chat1").isEmpty())
    }
}
