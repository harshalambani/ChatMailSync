package com.chatmailsync.app

import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.chatmailsync.core.mail.StateDb
import com.chatmailsync.core.mail.StateDbException
import java.io.File

/**
 * CUT-02: the Android [StateDb], backed by [SQLiteDatabase]. It opens the SAME `sync_state.db`
 * file the Python engine wrote, in place, with no copy.
 *
 * The contract is the one `:core` documents on [StateDb] and tests against its JDBC double
 * (`StateDoubleContractTest`); [AndroidStateDbContractTest] runs those cases against this class:
 *  - [configure] switches the file to WAL (outside any transaction) and leaves foreign keys OFF,
 *    as `state.py`'s non-init connections did;
 *  - INTEGER comes back as Long, REAL as Double, TEXT as String, NULL as null;
 *  - every driver failure surfaces as [StateDbException], so `initDb`'s duplicate-column
 *    `ALTER TABLE` try/catch still works;
 *  - [execScript] runs one statement at a time, on the current transaction state;
 *  - transactions are real: nothing is kept until [commit].
 */
class AndroidStateDb(path: File) : StateDb {
    private val db: SQLiteDatabase = try {
        // WAL is asked for on EVERY open, not only in configure(): on Android a connection opened
        // without it puts a WAL file back to a rollback journal, which the Python engine never did.
        SQLiteDatabase.openDatabase(
            path.absolutePath,
            null,
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
        )
    } catch (e: SQLiteException) {
        throw StateDbException(e.message ?: "SQLite error", e)
    }

    private inline fun <T> wrap(block: () -> T): T =
        try {
            block()
        } catch (e: StateDbException) {
            throw e
        } catch (e: RuntimeException) {
            // SQLiteException, IllegalStateException ("no transaction is active") and friends.
            throw StateDbException(e.message ?: "SQLite error", e)
        }

    override fun configure() = wrap {
        // Refused by SQLite inside a transaction; StateRepository calls this before beginning one.
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(false)
    }

    override fun exec(sql: String, params: List<Any?>) = wrap {
        if (params.isEmpty()) db.execSQL(sql) else db.execSQL(sql, bindable(params))
    }

    override fun execScript(sql: String) = wrap {
        // execSQL runs ONE statement. None of the DDL has a ';' inside a literal.
        sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.execSQL(it) }
    }

    override fun query(sql: String, params: List<Any?>): List<Map<String, Any?>> = wrap {
        // rawQuery binds every argument as TEXT, which would change how `LIMIT ?` or a numeric
        // comparison behaves. A cursor factory binds each value with its real type instead.
        val bound = bindable(params)
        val cursor = db.rawQueryWithFactory(
            { _, driver, editTable, query ->
                bound.forEachIndexed { i, v ->
                    when (v) {
                        null -> query.bindNull(i + 1)
                        is Long -> query.bindLong(i + 1, v)
                        is Double -> query.bindDouble(i + 1, v)
                        else -> query.bindString(i + 1, v.toString())
                    }
                }
                SQLiteCursor(driver, editTable, query)
            },
            sql, null, "",
        )
        cursor.use { readRows(it) }
    }

    private fun readRows(c: Cursor): List<Map<String, Any?>> {
        val rows = mutableListOf<Map<String, Any?>>()
        val names = c.columnNames
        while (c.moveToNext()) {
            val row = LinkedHashMap<String, Any?>()
            for (i in names.indices) {
                row[names[i]] = when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                    else -> c.getString(i)
                }
            }
            rows.add(row)
        }
        return rows
    }

    /** Int and the other integral types become Long; Boolean is refused (the contract has none). */
    private fun bindable(params: List<Any?>): Array<Any?> =
        params.map {
            when (it) {
                null, is Long, is Double, is String -> it
                is Int -> it.toLong()
                is Short -> it.toLong()
                is Byte -> it.toLong()
                is Float -> it.toDouble()
                else -> throw StateDbException("unsupported parameter type ${it.javaClass.simpleName}")
            }
        }.toTypedArray()

    override fun userVersion(): Int = wrap { db.version }

    override fun setUserVersion(version: Int) = wrap { db.version = version }

    /** Call inside the transaction that did the insert: in WAL mode a read outside one may be
     * served by a different pooled connection, which would answer 0. */
    override fun lastInsertRowId(): Long =
        wrap { DatabaseUtils.longForQuery(db, "SELECT last_insert_rowid()", null) }

    override fun beginTransaction() = wrap { db.beginTransactionNonExclusive() }

    override fun commit() = wrap {
        try {
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun rollback() = wrap { db.endTransaction() }

    override fun close() {
        db.close()
    }
}
