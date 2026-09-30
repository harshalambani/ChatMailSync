package com.chatmailsync.core.mail

import java.io.File
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ST-02: the test double must behave like Android's SQLiteDatabase in the ways that matter,
 * or a green JVM run proves nothing. These pin the contract, and the WAL fix itself.
 */
class StateDoubleContractTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun path() = File(tmp.newFolder(), "sync_state.db").absolutePath

    private fun journalMode(path: String): String =
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().executeQuery("PRAGMA journal_mode").use { it.next(); it.getString(1) }
        }

    // The fix: WAL really is on after initDb (it never was -- the PRAGMA ran inside a transaction).
    @Test
    fun initDbLeavesANewDatabaseInWalMode() {
        val p = path()
        StateRepository { SqliteJdbcStateDb(p) }.initDb()
        assertEquals("wal", journalMode(p).lowercase())
    }

    // NEGATIVE: the double must refuse a WAL switch inside a transaction, as real SQLite does.
    @Test
    fun theDoubleRefusesAWalSwitchInsideATransaction() {
        SqliteJdbcStateDb(path()).use { db ->
            db.beginTransaction()
            try {
                db.exec("PRAGMA journal_mode = WAL")
                fail("a WAL switch inside a transaction must fail")
            } catch (e: StateDbException) {
                assertNotNull(e.message)
            } finally {
                db.rollback()
            }
        }
    }

    // NEGATIVE: the DDL carries no PRAGMA, so it can never be run inside withDb's transaction again.
    @Test
    fun theSchemaScriptHasNoPragma() {
        assertTrue(!StateRepository.DDL.contains("PRAGMA", ignoreCase = true))
    }

    // beginTransaction is real: a rollback discards, a commit keeps.
    @Test
    fun beginTransactionIsRealRollbackDiscardsCommitKeeps() {
        SqliteJdbcStateDb(path()).use { db ->
            db.exec("CREATE TABLE t (k INTEGER)")
            db.beginTransaction()
            db.exec("INSERT INTO t (k) VALUES (1)")
            db.rollback()
            assertEquals(0, db.query("SELECT COUNT(*) AS n FROM t").first()["n"].let { (it as Number).toInt() })
            db.beginTransaction()
            db.exec("INSERT INTO t (k) VALUES (2)")
            db.commit()
            assertEquals(1, db.query("SELECT COUNT(*) AS n FROM t").first()["n"].let { (it as Number).toInt() })
        }
    }

    // NEGATIVE: a commit with no transaction open fails loudly, as a real database does.
    @Test
    fun commitWithNoTransactionIsAnError() {
        SqliteJdbcStateDb(path()).use { db ->
            try {
                db.commit()
                fail("expected StateDbException")
            } catch (e: StateDbException) {
                // expected
            }
        }
    }

    // Every INTEGER comes back as Long, like Android's cursor.getLong path.
    @Test
    fun integersComeBackAsLong() {
        SqliteJdbcStateDb(path()).use { db ->
            val v = db.query("SELECT 7 AS a, 9000000000 AS b").first()
            assertEquals(java.lang.Long::class.java, v["a"]!!.javaClass)
            assertEquals(java.lang.Long::class.java, v["b"]!!.javaClass)
        }
    }

    // NEGATIVE: a failing rollback must not replace the error that caused it.
    @Test
    fun aFailingRollbackKeepsTheOriginalError() {
        val p = path()
        val repo = StateRepository {
            object : StateDb by SqliteJdbcStateDb(p) {
                override fun rollback() = throw StateDbException("rollback broke")
            }
        }
        // initDb's first statement fails inside the transaction: break the schema script.
        val broken = StateRepository {
            object : StateDb by SqliteJdbcStateDb(p) {
                override fun execScript(sql: String) = throw StateDbException("original failure")
                override fun rollback() = throw StateDbException("rollback broke")
            }
        }
        repo.initDb()
        try {
            broken.initDb()
            fail("expected StateDbException")
        } catch (e: StateDbException) {
            assertEquals("original failure", e.message)
            assertTrue(e.suppressed.any { it.message == "rollback broke" })
        }
    }

    // NEGATIVE: a failure before the transaction began (configure) does not try to roll back.
    @Test
    fun aConfigureFailureDoesNotRollBack() {
        var rolledBack = false
        val repo = StateRepository {
            object : StateDb by SqliteJdbcStateDb(path()) {
                override fun configure() = throw StateDbException("cannot configure")
                override fun rollback() { rolledBack = true }
            }
        }
        try {
            repo.initDb()
            fail("expected StateDbException")
        } catch (e: StateDbException) {
            assertEquals("cannot configure", e.message)
        }
        assertTrue("rollback ran with no transaction", !rolledBack)
    }
}
