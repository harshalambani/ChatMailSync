package com.chatmailsync.core.mail

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

/**
 * JVM unit-test-only [StateDb] implementation, backed by the xerial
 * `sqlite-jdbc` driver (`testImplementation` in `build.gradle.kts` -- never
 * shipped in the app). Exists purely so [StateRepository] (`State.kt`) can
 * be exercised against a real on-disk `.db` file in JUnit, including ones
 * `src/state.py` actually wrote (the cross-language golden fixtures).
 *
 * Android's own [StateDb] implementation (Phase 4, not part of this PR)
 * will use `android.database.sqlite.SQLiteDatabase` instead -- see
 * `StateDb.kt`'s doc comment.
 */
class SqliteJdbcStateDb(path: String) : StateDb {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path").also {
        it.autoCommit = false
    }

    override fun exec(sql: String, params: List<Any?>) {
        try {
            connection.prepareStatement(sql).use { stmt ->
                bind(stmt, params)
                stmt.executeUpdate()
            }
        } catch (e: SQLException) {
            throw StateDbException(e.message ?: "SQLite error", e)
        }
    }

    override fun execScript(sql: String) {
        // Python's sqlite3.Connection.executescript implicitly COMMITs any
        // pending transaction before it runs, so every statement in
        // state.py's _DDL -- including `PRAGMA journal_mode = WAL`, which
        // SQLite refuses mid-transaction ("cannot change into wal mode from
        // within a transaction") -- executes with no transaction open. This
        // connection's autoCommit is false for the ordinary exec()/query()
        // path (see beginTransaction/commit/rollback below), so it has to be
        // flipped on for the duration of the script to reproduce that same
        // "no active transaction" shape, then restored.
        val previousAutoCommit = connection.autoCommit
        try {
            connection.autoCommit = true
            connection.createStatement().use { stmt: Statement ->
                // sqlite-jdbc's Statement.executeUpdate does not support
                // multiple ';'-separated statements in one call the way
                // Python's sqlite3.executescript does, so split and run each
                // non-blank statement individually. None of state.py's DDL
                // statements contain a semicolon inside a string literal, so
                // a naive split is safe here.
                sql.split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { statement -> stmt.execute(statement) }
            }
        } catch (e: SQLException) {
            throw StateDbException(e.message ?: "SQLite error", e)
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    override fun query(sql: String, params: List<Any?>): List<Map<String, Any?>> {
        try {
            connection.prepareStatement(sql).use { stmt ->
                bind(stmt, params)
                stmt.executeQuery().use { rs: ResultSet ->
                    val meta = rs.metaData
                    val colCount = meta.columnCount
                    val rows = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) {
                        val row = LinkedHashMap<String, Any?>()
                        for (i in 1..colCount) {
                            row[meta.getColumnLabel(i)] = rs.getObject(i)
                        }
                        rows.add(row)
                    }
                    return rows
                }
            }
        } catch (e: SQLException) {
            throw StateDbException(e.message ?: "SQLite error", e)
        }
    }

    override fun userVersion(): Int =
        query("PRAGMA user_version").first().values.first().let { (it as Number).toInt() }

    override fun setUserVersion(version: Int) {
        // PRAGMA statements cannot be parameterised.
        connection.createStatement().use { it.execute("PRAGMA user_version = $version") }
    }

    override fun lastInsertRowId(): Long =
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT last_insert_rowid()").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    override fun beginTransaction() {
        // autoCommit is already false; nothing further to do -- the JDBC
        // driver starts an implicit transaction on the first statement.
    }

    override fun commit() {
        connection.commit()
    }

    override fun rollback() {
        connection.rollback()
    }

    override fun close() {
        connection.close()
    }

    private fun bind(stmt: java.sql.PreparedStatement, params: List<Any?>) {
        params.forEachIndexed { index, value ->
            stmt.setObject(index + 1, value)
        }
    }
}
