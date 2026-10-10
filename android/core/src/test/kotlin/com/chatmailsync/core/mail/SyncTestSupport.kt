package com.chatmailsync.core.mail

import java.io.File
import java.time.LocalDateTime

/** Shared helpers for the sync-manager tests (made-up names only). */
internal object SyncTestSupport {

    const val FROZEN_NOW = "2025-03-14T09:40:00"

    fun golden(name: String): JsonNode {
        val stream = SyncTestSupport::class.java.classLoader.getResourceAsStream("golden/$name")
            ?: error("golden resource not found on test classpath: golden/$name")
        return parseJson(stream.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    /** A real repository over a fresh SQLite file under [dir], clock frozen. */
    fun newRepo(dir: File, fileName: String = "state.db"): StateRepository {
        dir.mkdirs()
        val path = File(dir, fileName).absolutePath
        val repo = StateRepository(clock = { FROZEN_NOW }) { SqliteJdbcStateDb(path) }
        repo.initDb()
        return repo
    }

    fun msg(chatId: String, ts: String, sender: String, body: String, attachment: String? = null) =
        ParsedMessage(chatId, LocalDateTime.parse(ts), sender, body, attachment)

    /** A `[ts, sender, body]` triple from a golden file. */
    fun msgOf(chatId: String, node: JsonNode): ParsedMessage {
        val a = node.asArr().items
        return msg(chatId, a[0].asString(), a[1].asString(), a[2].asString())
    }

    fun strings(node: JsonNode): List<String> = node.asArr().items.map { it.asString() }

    fun ints(node: JsonNode): List<Int> = node.asArr().items.map { it.asInt() }
}
