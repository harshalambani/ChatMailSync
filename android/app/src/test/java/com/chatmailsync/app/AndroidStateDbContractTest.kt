package com.chatmailsync.app

import com.chatmailsync.core.mail.StateDb
import com.chatmailsync.core.mail.StateDbException
import com.chatmailsync.core.mail.StateRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode

/**
 * CUT-02: the StateDb contract that :core's StateDoubleContractTest pins on its JDBC double,
 * run against the REAL Android adapter (Robolectric supplies SQLiteDatabase on the JVM).
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AndroidStateDbContractTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file() = File(tmp.newFolder(), "sync_state.db")

    private fun n(db: StateDb, table: String): Int =
        (db.query("SELECT COUNT(*) AS n FROM $table").first()["n"] as Number).toInt()

    @Test
    fun initDbLeavesANewDatabaseInWalMode() {
        val f = file()
        StateRepository { AndroidStateDb(f) }.initDb()
        AndroidStateDb(f).use { db ->
            assertEquals("wal", db.query("PRAGMA journal_mode").first().values.first().toString().lowercase())
        }
    }

    // NEGATIVE: opening again WITHOUT configure() must not put a WAL file back to a rollback journal.
    @Test
    fun aPlainReopenKeepsWal() {
        val f = file()
        StateRepository { AndroidStateDb(f) }.initDb()
        repeat(2) {
            AndroidStateDb(f).use { db -> db.query("SELECT 1 AS one") }
        }
        AndroidStateDb(f).use { db ->
            assertEquals("wal", db.query("PRAGMA journal_mode").first().values.first().toString().lowercase())
        }
    }

    @Test
    fun foreignKeysAreOffAfterConfigure() {
        AndroidStateDb(file()).use { db ->
            db.configure()
            assertEquals(0L, db.query("PRAGMA foreign_keys").first().values.first())
        }
    }

    // NEGATIVE: a WAL switch inside a transaction never escapes as anything but StateDbException.
    @Test
    fun aWalSwitchInsideATransactionIsOnlyEverAStateDbException() {
        AndroidStateDb(file()).use { db ->
            db.beginTransaction()
            try {
                db.exec("PRAGMA journal_mode = WAL")
            } catch (e: StateDbException) {
                assertNotNull(e.message)
            } finally {
                db.rollback()
            }
        }
    }

    @Test
    fun valuesKeepTheirTypes() {
        AndroidStateDb(file()).use { db ->
            db.exec("CREATE TABLE t (i INTEGER, r REAL, s TEXT, z TEXT)")
            db.exec("INSERT INTO t VALUES (?, ?, ?, ?)", listOf(9000000000L, 1.5, "x", null))
            val row = db.query("SELECT * FROM t").first()
            assertEquals(9000000000L, row["i"])
            assertEquals(1.5, row["r"])
            assertEquals("x", row["s"])
            assertNull(row["z"])
            assertEquals(java.lang.Long::class.java, row["i"]!!.javaClass)
        }
    }

    // A numeric parameter must be bound as a number, not text (LIMIT ? would otherwise misbehave).
    @Test
    fun aNumericParameterIsBoundAsANumber() {
        AndroidStateDb(file()).use { db ->
            db.exec("CREATE TABLE t (k INTEGER)")
            for (i in 1..5) db.exec("INSERT INTO t VALUES (?)", listOf(i))
            assertEquals(2, db.query("SELECT k FROM t ORDER BY k LIMIT ?", listOf(2)).size)
            assertEquals(2, db.query("SELECT k FROM t WHERE k > ?", listOf(3L)).size)
        }
    }

    // NEGATIVE: an unsupported parameter type is refused, not stringified.
    @Test
    fun aBooleanParameterIsRefused() {
        AndroidStateDb(file()).use { db ->
            db.exec("CREATE TABLE t (k INTEGER)")
            try {
                db.exec("INSERT INTO t VALUES (?)", listOf(true))
                fail("expected StateDbException")
            } catch (e: StateDbException) {
                // expected
            }
        }
    }

    @Test
    fun execScriptRunsEveryStatement() {
        AndroidStateDb(file()).use { db ->
            db.execScript("CREATE TABLE a (k INTEGER); CREATE TABLE b (k INTEGER); INSERT INTO a VALUES (1);")
            assertEquals(1, n(db, "a"))
            assertEquals(0, n(db, "b"))
        }
    }

    @Test
    fun beginTransactionIsRealRollbackDiscardsCommitKeeps() {
        AndroidStateDb(file()).use { db ->
            db.exec("CREATE TABLE t (k INTEGER)")
            db.beginTransaction()
            db.exec("INSERT INTO t VALUES (1)")
            db.rollback()
            assertEquals(0, n(db, "t"))
            db.beginTransaction()
            db.exec("INSERT INTO t VALUES (2)")
            db.commit()
            assertEquals(1, n(db, "t"))
        }
    }

    // NEGATIVE: a transaction that throws leaves NO rows behind.
    @Test
    fun aTransactionThatThrowsLeavesNoRowsBehind() {
        val f = file()
        StateRepository { AndroidStateDb(f) }.initDb()
        val repo = StateRepository {
            object : StateDb by AndroidStateDb(f) {
                override fun commit() = throw StateDbException("commit broke")
            }
        }
        try {
            repo.upsertChat("c1", "Meera Iyer", "chat.txt")
            fail("expected StateDbException")
        } catch (e: StateDbException) {
            // expected
        }
        assertNull(StateRepository { AndroidStateDb(f) }.getChat("c1"))
    }

    // NEGATIVE: a commit with no transaction open fails loudly.
    @Test
    fun commitWithNoTransactionIsAStateDbException() {
        AndroidStateDb(file()).use { db ->
            try {
                db.commit()
                fail("expected StateDbException")
            } catch (e: StateDbException) {
                // expected
            }
        }
    }

    // NEGATIVE: a duplicate-column ALTER does NOT throw out of initDb (second run on the same file).
    @Test
    fun aDuplicateColumnAlterDoesNotThrowOutOfInitDb() {
        val f = file()
        val repo = StateRepository { AndroidStateDb(f) }
        repo.initDb()
        repo.initDb()
        AndroidStateDb(f).use { db ->
            val cols = db.query("PRAGMA table_info(sync_runs)").map { it["name"] }
            assertTrue("trigger" in cols)
            assertTrue("messages_cutoff" in cols)
        }
    }

    @Test
    fun theRepositoryRoundTripsAChat() {
        val f = file()
        val r = StateRepository { AndroidStateDb(f) }
        r.initDb()
        r.upsertChat("c1", "Meera Iyer", "chat.txt")
        assertEquals("Meera Iyer", r.getChat("c1")!!.displayName)
    }

    @Test
    fun userVersionAndLastInsertRowId() {
        AndroidStateDb(file()).use { db ->
            db.setUserVersion(7)
            assertEquals(7, db.userVersion())
            db.exec("CREATE TABLE t (id INTEGER PRIMARY KEY, k INTEGER)")
            // As StateRepository uses it: inside the transaction, so insert and read share one
            // connection (in WAL mode a read outside a transaction may use another connection).
            db.beginTransaction()
            db.exec("INSERT INTO t (k) VALUES (5)")
            assertEquals(1L, db.lastInsertRowId())
            db.exec("INSERT INTO t (k) VALUES (6)")
            assertEquals(2L, db.lastInsertRowId())
            db.commit()
        }
    }

    // NEGATIVE: a driver failure is always a StateDbException, never a raw SQLite exception.
    @Test
    fun aBadStatementIsAStateDbException() {
        AndroidStateDb(file()).use { db ->
            try {
                db.exec("THIS IS NOT SQL")
                fail("expected StateDbException")
            } catch (e: StateDbException) {
                // expected
            }
        }
    }
}
