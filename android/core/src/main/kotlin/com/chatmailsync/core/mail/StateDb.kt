package com.chatmailsync.core.mail

/**
 * The tiny SQL surface [StateRepository] needs from an actual database
 * connection.
 *
 * `state.py` opens a fresh `sqlite3.connect()` per call
 * (`_connect`/`init_db`), commits on success and rolls back on any exception,
 * then closes -- see that module's `_connect` context manager. This
 * interface exists so [StateRepository] can reproduce exactly that shape
 * (the DDL text, the `user_version` dance, every query) once, in pure
 * Kotlin, without committing to *how* a SQLite connection is obtained on
 * whatever platform runs it:
 *
 *  - JVM unit tests implement it with the xerial `sqlite-jdbc` driver
 *    (`SqliteJdbcStateDb`, `src/test/kotlin` -- a `testImplementation`-only
 *    dependency, never shipped in the app) so `StateRepository` can be
 *    exercised against a real `.db` file, including one Python actually
 *    wrote.
 *  - Android (Phase 4, not part of this PR) will implement it with
 *    `android.database.sqlite.SQLiteDatabase`/`SQLiteOpenHelper`, per the
 *    plan document's recommendation (section D: "raw `SQLiteOpenHelper`")
 *    -- `:core` stays Android-free (see `build.gradle.kts`'s module
 *    comment), so that implementation lives in `:app`, not here.
 *
 * Every method is a single round trip; [StateRepository] is the only thing
 * that sequences several of them inside one connection's lifetime.
 */
interface StateDb : AutoCloseable {
    /**
     * One-time connection setup, called by [StateRepository] on a fresh connection
     * OUTSIDE any transaction and before [beginTransaction] (SQLite refuses
     * `PRAGMA journal_mode = WAL` inside a transaction). Switches the database file to
     * write-ahead logging (Android: `enableWriteAheadLogging()`).
     *
     * Foreign keys are deliberately left OFF, by parity: the tables declare
     * `REFERENCES` but `state.py` only ever switched them on inside `init_db`'s own
     * connection, every other connection (and every existing database's writes) ran
     * with them off, so enforcing them now could reject rows the shipped app accepts.
     */
    fun configure()

    // VALUE TYPES (the contract every implementation must meet): a row's values come back
    // as String for TEXT, Long for INTEGER (never Int or Short), Double for REAL, and null
    // for NULL. BLOBs are not used. Bound parameters are String, Long, Int, Double or null.
    //
    // TRANSACTIONS: [beginTransaction] really begins one (nothing is written for good until
    // [commit]); [rollback] discards it. Statements run outside a transaction autocommit.
    // Each statement is ONE statement; only [execScript] takes several.

    /** Runs SQL that returns no rows (DDL, INSERT/UPDATE/DELETE). */
    fun exec(sql: String, params: List<Any?> = emptyList())

    /**
     * Runs a script of one or more `;`-separated statements with no bound
     * parameters -- the twin of `sqlite3.Connection.executescript`, used
     * only for [StateRepository]'s verbatim copy of `state.py`'s `_DDL`.
     */
    fun execScript(sql: String)

    /** Runs a SELECT and returns each row as a column-name-to-value map. */
    fun query(sql: String, params: List<Any?> = emptyList()): List<Map<String, Any?>>

    /** `PRAGMA user_version` read, as an integer. */
    fun userVersion(): Int

    /** `PRAGMA user_version = [version]`. */
    fun setUserVersion(version: Int)

    /** The rowid of the most recently completed `INSERT` on this connection. */
    fun lastInsertRowId(): Long

    /** Begins a transaction. [StateRepository] always pairs this with
     * exactly one of [commit]/[rollback] before [close]. */
    fun beginTransaction()

    fun commit()

    fun rollback()
}

/**
 * Wraps whatever the underlying driver throws (`java.sql.SQLException` on
 * the JVM, `android.database.sqlite.SQLiteException` on Android) so
 * [StateRepository] can catch one type regardless of platform -- mirroring
 * how `state.py:init_db`'s `ALTER TABLE ... ADD COLUMN` retry only ever
 * needs to catch `sqlite3.OperationalError`, never every exception type
 * SQLite can raise.
 */
class StateDbException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
