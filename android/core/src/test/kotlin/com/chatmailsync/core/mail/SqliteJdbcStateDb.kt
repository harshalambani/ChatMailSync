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
        // Like Android's SQLiteDatabase: autocommit until beginTransaction() really begins.
        it.autoCommit = true
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

    override fun configure() {
        // Same as Android's enableWriteAheadLogging(): must run with no transaction open,
        // and SQLite itself refuses it otherwise (see StateDoubleContractTest).
        connection.createStatement().use { it.execute("PRAGMA journal_mode = WAL") }
    }

    override fun execScript(sql: String) {
        // Android's execSQL runs ONE statement, so a script is split and run statement by
        // statement on the current transaction state -- it is NOT implicitly committed the
        // way Python's executescript is. None of the DDL contains a ';' inside a literal.
        try {
            connection.createStatement().use { stmt: Statement ->
                sql.split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { statement -> stmt.execute(statement) }
            }
        } catch (e: SQLException) {
            throw StateDbException(e.message ?: "SQLite error", e)
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
                            row[meta.getColumnLabel(i)] = when (val v = rs.getObject(i)) {
                                is Int, is Short, is Byte -> (v as Number).toLong()
                                else -> v
                            }
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
        try {
            connection.createStatement().use { it.execute("PRAGMA user_version = $version") }
        } catch (e: SQLException) {
            throw StateDbException(e.message ?: "SQLite error", e)
        }
    }

    override fun lastInsertRowId(): Long =
        try {
            connection.createStatement().use { stmt ->
                stmt.executeQuery("SELECT last_insert_rowid()").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        } catch (e: SQLException) {
            throw StateDbException(e.message ?: "SQLite error", e)
        }

    // Real transaction statements, as Android's beginTransaction/setTransactionSuccessful+
    // endTransaction do. A failing COMMIT/ROLLBACK surfaces as StateDbException.
    override fun beginTransaction() = run("BEGIN")

    override fun commit() = run("COMMIT")

    override fun rollback() = run("ROLLBACK")

    private fun run(sql: String) {
        try {
            connection.createStatement().use { it.execute(sql) }
        } catch (e: SQLException) {
            throw StateDbException(e.message ?: "SQLite error", e)
        }
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
