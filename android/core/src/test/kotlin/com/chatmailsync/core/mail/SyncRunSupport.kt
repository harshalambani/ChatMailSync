package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.rules.TemporaryFolder

/**
 * Pieces shared by the sync-run tests: a transport whose ids come from a call
 * counter (the same one the Python generator uses), a scenario runner, and the
 * database dump that is compared with the Python golden.
 */
internal class CountingTransport(
    private val failAt: Int? = null,
    private val failText: String = "simulated crash mid-push",
    private val crashAt: Int? = null,
) : MailTransport {
    var insertCalls = 0
        private set

    override fun labelsList(): List<ImapTransport.Label> = emptyList()

    override fun labelsCreate(name: String): String = "Label_WA"

    override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
        insertCalls++
        if (crashAt != null && insertCalls == crashAt) throw ProcessDeath()
        if (failAt != null && insertCalls == failAt) throw RuntimeException(failText)
        return ImapTransport.InsertResult(
            id = "m$insertCalls",
            threadId = threadId?.takeIf { it.isNotEmpty() } ?: "t$insertCalls",
        )
    }
}

/**
 * A process kill. It is an Error, not an Exception, so (like Python's
 * KeyboardInterrupt) no handler in the manager sees it and the run row stays
 * pending.
 */
internal class ProcessDeath : Error("simulated process death")

internal object SyncRunSupport {

    /** Run every scenario of [g] and compare each step with the Python result. */
    fun checkScenarios(tmp: TemporaryFolder, g: JsonNode) {
        for (sc in g["scenarios"].asArr().items) {
            val name = sc["name"].asString()
            val root = tmp.newFolder(name)
            val inbox = File(root, "inbox").also { it.mkdirs() }
            val processed = File(root, "processed")
            val dbPath = File(root, "state.db").absolutePath
            val repo = newRepo(root, "state.db")

            val steps = sc["steps"].asArr().items
            val specSteps = sc["spec"]["steps"].asArr().items
            for ((n, step) in steps.withIndex()) {
                val label = "$name step ${n + 1}"
                val spec = specSteps[n]
                spec.asObj().fields["remove"]?.asArr()?.items?.forEach { File(inbox, it.asString()).delete() }
                spec.asObj().fields["sql"]?.asArr()?.items?.let { stmts ->
                    SqliteJdbcStateDb(dbPath).use { db -> stmts.forEach { db.exec(it.asString(), emptyList()) } }
                }
                for ((fname, fileSpec) in spec["write"].asObj().fields) {
                    val f = File(inbox, fname)
                    val z = fileSpec.asObj().fields["zip"]
                    if (z != null) {
                        writeZip(f, z.asObj().fields.mapValues { it.value.asString() })
                    } else {
                        f.writeText(fileSpec["text"].asString())
                    }
                }
                val opts = spec["options"].asObj().fields
                opts["chat_cutoffs"]?.asObj()?.fields?.forEach { (chat, cutoff) -> repo.setChatCutoff(chat, cutoff.asString()) }

                val events = mutableListOf<Map<String, Any?>>()
                val stopAfter = opts["stop_after_files"]?.asInt()
                val transport = CountingTransport(opts["fail_at"]?.asInt(), crashAt = opts["crash_at"]?.asInt())
                val mgr = SyncManager(
                    repo = repo,
                    transport = transport,
                    inboxDir = inbox,
                    processedDir = processed,
                    chunkSize = opts["chunk_size"]?.let { c ->
                        when (c) {
                            is JsonNode.Num -> ChunkSize.Count(c.value.toInt())
                            else -> ChunkSize.Day
                        }
                    } ?: ChunkSize.Day,
                    dryRun = (opts["dry_run"] as? JsonNode.Bool)?.value ?: false,
                    trigger = opts["trigger"]?.asString() ?: "manual",
                    cutoffDate = opts["cutoff_date"]?.asString(),
                    onProgress = { events.add(it) },
                    shouldStop = {
                        stopAfter != null && events.count { it["type"] == "file_done" } >= stopAfter
                    },
                    sleeper = { },
                )
                var stats: SyncStats? = null
                var crashed = false
                try {
                    stats = mgr.run(opts["chat_filter"]?.asString())
                } catch (_: ProcessDeath) {
                    crashed = true
                }
                assertEquals("$label crashed", step.asObj().fields["crashed"] != null, crashed)

                if (!crashed) {
                    val want = step["stats"].asObj().fields
                    val got = stats!!.asMap()
                    for ((k, v) in want) {
                        val actual = got[k]
                        when (v) {
                            is JsonNode.Arr -> assertEquals("$label $k", v.items.map { it.asString() }, actual)
                            else -> assertEquals("$label $k", v.asInt(), actual)
                        }
                    }
                    assertEquals("$label stats keys", want.keys, got.keys)
                    assertEquals("$label text", step["statsText"].asString(), stats.toString())
                }

                assertEquals(
                    "$label events",
                    step["events"].asArr().items.map { eventText(it) },
                    events.map { eventText(it) },
                )
                assertEquals("$label inserts", step["inserts"].asInt(), transport.insertCalls)

                val d = dump(dbPath, inbox, processed)
                val ws = step["state"]
                assertEquals("$label runs", asRows(ws["runs"]), d.runs)
                assertEquals("$label hashes", asRows(ws["hashes"]), d.hashes)
                assertEquals("$label chats", asRows(ws["chats"]), d.chats)
                assertEquals("$label senders", asRows(ws["senders"]), d.senders)
                assertEquals("$label appState", asRows(ws["appState"]), d.appState)
                assertEquals("$label inbox", ws["inbox"].asArr().items.map { it.asString() }, d.inbox)
                assertEquals("$label processed", ws["processed"].asArr().items.map { it.asString() }, d.processed)
            }
        }
    }

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
