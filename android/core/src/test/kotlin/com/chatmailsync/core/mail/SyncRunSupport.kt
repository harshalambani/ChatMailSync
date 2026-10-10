package com.chatmailsync.core.mail

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pieces shared by the sync-run tests: a transport whose ids come from a call
 * counter (the same one the Python generator uses), a scenario runner, and the
 * database dump that is compared with the Python golden.
 */
internal class CountingTransport(private val failAt: Int? = null, private val failText: String = "simulated crash mid-push") :
    MailTransport {
    var insertCalls = 0
        private set

    override fun labelsList(): List<ImapTransport.Label> = emptyList()

    override fun labelsCreate(name: String): String = "Label_WA"

    override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
        insertCalls++
        if (failAt != null && insertCalls == failAt) throw RuntimeException(failText)
        return ImapTransport.InsertResult(
            id = "m$insertCalls",
            threadId = threadId?.takeIf { it.isNotEmpty() } ?: "t$insertCalls",
        )
    }
}

internal object SyncRunSupport {

    fun writeZip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
    }

    /** One value of a golden event or row as comparable text. */
    fun render(node: JsonNode): String = when (node) {
        is JsonNode.Str -> node.value
        is JsonNode.Num -> node.value.toString()
        is JsonNode.Bool -> node.value.toString()
        JsonNode.Null -> "null"
        else -> error("unexpected node $node")
    }

    fun eventText(event: Map<String, Any?>): String =
        event.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value ?: "null"}" }

    fun eventText(node: JsonNode): String =
        node.asObj().fields.toSortedMap().entries.joinToString(",") { "${it.key}=${render(it.value)}" }

    /** Rows of [sql] as lists of text (null stays null), the same shape the generator dumps. */
    fun rows(dbPath: String, sql: String, columns: List<String>): List<List<String?>> =
        SqliteJdbcStateDb(dbPath).use { db ->
            db.query(sql, emptyList()).map { row -> columns.map { row[it]?.toString() } }
        }

    class Dump(
        val runs: List<List<String?>>,
        val hashes: List<List<String?>>,
        val chats: List<List<String?>>,
        val senders: List<List<String?>>,
        val appState: List<List<String?>>,
        val inbox: List<String>,
        val processed: List<String>,
    )

    fun dump(dbPath: String, inbox: File, processed: File): Dump = Dump(
        runs = rows(
            dbPath,
            "SELECT run_id, chat_id, status, trigger, last_synced_ts, last_synced_hash, messages_parsed, " +
                "messages_synced, messages_skipped, messages_cutoff, error_message FROM sync_runs ORDER BY run_id",
            listOf(
                "run_id", "chat_id", "status", "trigger", "last_synced_ts", "last_synced_hash", "messages_parsed",
                "messages_synced", "messages_skipped", "messages_cutoff", "error_message",
            ),
        ),
        hashes = rows(
            dbPath,
            "SELECT hash, chat_id, message_ts, run_id FROM message_hashes ORDER BY run_id, message_ts, hash",
            listOf("hash", "chat_id", "message_ts", "run_id"),
        ),
        chats = rows(
            dbPath,
            "SELECT chat_id, display_name, gmail_thread_id, gmail_label_id, " +
                "CASE WHEN anchor_message_id LIKE '<wa-sync-%@local>' THEN '<anchor>' ELSE anchor_message_id END " +
                "AS anchor_message_id, source_filename FROM chats ORDER BY chat_id",
            listOf("chat_id", "display_name", "gmail_thread_id", "gmail_label_id", "anchor_message_id", "source_filename"),
        ),
        senders = rows(
            dbPath,
            "SELECT chat_id, sender, msg_count FROM chat_senders ORDER BY chat_id, sender",
            listOf("chat_id", "sender", "msg_count"),
        ),
        appState = rows(
            dbPath,
            "SELECT key, value FROM app_state WHERE key LIKE 'self_sender%' ORDER BY key",
            listOf("key", "value"),
        ),
        inbox = (inbox.list() ?: emptyArray()).sortedWith(CodePointOrder),
        processed = (processed.list() ?: emptyArray()).sortedWith(CodePointOrder),
    )

    fun asRows(node: JsonNode): List<List<String?>> =
        node.asArr().items.map { r -> r.asArr().items.map { it.asStringOrNull() } }
}
