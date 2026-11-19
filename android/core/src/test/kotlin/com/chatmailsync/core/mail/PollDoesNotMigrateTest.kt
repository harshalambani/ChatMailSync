package com.chatmailsync.core.mail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BUG-25: the app polls getSelfSender every 250 ms during a sync. Each call used to re-run the
 * schema migrations, and every ALTER TABLE for a column that already existed made SQLite log a
 * "duplicate column" error. The migrations now run once per database file.
 */
class PollDoesNotMigrateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Wraps the real database and records every statement that changes the schema. */
    private class Counting(private val inner: StateDb, private val log: MutableList<String>) : StateDb by inner {
        override fun exec(sql: String, params: List<Any?>) {
            if (sql.trimStart().startsWith("ALTER TABLE", ignoreCase = true)) log.add(sql)
            inner.exec(sql, params)
        }
        override fun execScript(sql: String) {
            log.add("SCRIPT")
            inner.execScript(sql)
        }
    }

    private fun api(root: File, log: MutableList<String>) =
        CoreApi(root, { Counting(SqliteJdbcStateDb(it.absolutePath), log) }, { null })

    private fun alters(log: List<String>) = log.count { it.startsWith("ALTER") }

    @Test
    fun theProgressPollDoesNotRunTheMigrationsAgain() {
        val log = mutableListOf<String>()
        val a = api(tmp.newFolder("r1"), log)
        a.getSelfSender()
        val afterFirst = log.toList()
        assertTrue("the first open ran the schema script", afterFirst.contains("SCRIPT"))
        val alterAfterFirst = alters(afterFirst)
        assertTrue("the first open tried the ALTER TABLE migrations", alterAfterFirst >= 3)

        repeat(200) {
            a.progressState()
            a.getSelfSender()
        }
        assertEquals("no ALTER TABLE after the first open", alterAfterFirst, alters(log))
        assertEquals("the schema script ran exactly once", 1, log.count { it == "SCRIPT" })
    }

    @Test
    fun anOldDatabaseIsMigratedExactlyOnce() {
        val root = tmp.newFolder("r2")
        // A database as an old version made it: no trigger, messages_cutoff or stopped column.
        val dbFile = File(File(root, "data").also { it.mkdirs() }, "sync_state.db")
        SqliteJdbcStateDb(dbFile.absolutePath).use { db ->
            db.beginTransaction()
            db.execScript(
                """
                CREATE TABLE sync_runs (
                    run_id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id          TEXT    NOT NULL,
                    status           TEXT    NOT NULL CHECK(status IN ('pending', 'complete', 'failed')),
                    last_synced_ts   TEXT,
                    last_synced_hash TEXT,
                    messages_parsed  INTEGER NOT NULL DEFAULT 0,
                    messages_synced  INTEGER NOT NULL DEFAULT 0,
                    messages_skipped INTEGER NOT NULL DEFAULT 0,
                    error_message    TEXT,
                    started_at       TEXT    NOT NULL,
                    completed_at     TEXT
                );
                """.trimIndent(),
            )
            db.commit()
        }
        val log = mutableListOf<String>()
        val a = api(root, log)
        a.getSelfSender()
        val cols = SqliteJdbcStateDb(a.paths.stateDbPath.absolutePath).use { db ->
            db.query("PRAGMA table_info(sync_runs)").map { it["name"].toString() }
        }
        assertTrue("trigger added", "trigger" in cols)
        assertTrue("messages_cutoff added", "messages_cutoff" in cols)
        assertTrue("stopped added", "stopped" in cols)
        val firstAlters = alters(log)

        repeat(50) { a.getSelfSender() }
        assertEquals("not migrated a second time", firstAlters, alters(log))
    }

    @Test
    fun aDatabaseThatWasWipedIsBroughtUpAgain() {
        val log = mutableListOf<String>()
        val a = api(tmp.newFolder("r3"), log)
        a.getSelfSender()
        val before = log.count { it == "SCRIPT" }
        assertTrue(a.paths.stateDbPath.delete())
        a.getSelfSender()
        assertEquals("a missing file is initialised again", before + 1, log.count { it == "SCRIPT" })
        assertFalse(!a.paths.stateDbPath.exists())
    }
}
