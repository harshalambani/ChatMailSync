package com.chatmailsync.core.mail

import java.security.MessageDigest

/**
 * Kotlin port of `src/mail_index.py` (build_index / index_bytes /
 * estimate_index_bytes / header constants) and `compute_message_hash` from
 * `src/state.py`, exactly enough to give `MimeBuilder` byte-identical index
 * attachments to the Python side.
 */

const val INDEX_SCHEMA = 1
const val UNKNOWN_VERSION = "unknown"
const val INDEX_FILENAME = "chatmailsync-index.json"

const val HEADER_VERSION = "X-ChatMailSync-Version"
const val HEADER_CHAT = "X-ChatMailSync-Chat"
const val HEADER_COUNT = "X-ChatMailSync-Count"
const val HEADER_INDEX = "X-ChatMailSync-Index"

private const val ESTIMATED_BYTES_PER_ENTRY = 256

/** Mirrors `estimate_index_bytes`: a conservative upper bound on the attached index size for N messages. */
fun estimateIndexBytes(messageCount: Int): Int = 512 + ESTIMATED_BYTES_PER_ENTRY * messageCount

/** Mirrors `src.state.compute_message_hash`. */
fun computeMessageHash(chatId: String, timestampIso: String, sender: String, body: String): String {
    val raw = "$chatId\u0000$timestampIso\u0000$sender\u0000$body"
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

data class IndexMessageEntry(val n: Int, val ts: String, val sender: String, val hash: String)

data class MailIndex(
    val schema: Int,
    val chatId: String,
    val displayName: String,
    val messageId: String,
    val chunkSize: ChunkSize,
    val count: Int,
    val firstTs: String,
    val lastTs: String,
    val appVersion: String,
    val messages: List<IndexMessageEntry>,
)

/**
 * Mirrors `build_index`. [chunk] must be non-empty (same precondition as the
 * Python original).
 *
 * [appVersion] mirrors what `app_version()` returns at build time in Python:
 * a blank string resolves to [UNKNOWN_VERSION], exactly like
 * `set_app_version`'s `version if version else UNKNOWN_VERSION` — Kotlin has
 * no separate set/get step (no mutable global), so that same fallback is
 * applied here, at the one call site that stands in for it.
 */
fun buildIndex(
    displayName: String,
    chunk: List<ParsedMessage>,
    chunkSize: ChunkSize,
    messageId: String,
    appVersion: String = UNKNOWN_VERSION,
): MailIndex {
    require(chunk.isNotEmpty()) { "chunk must not be empty" }
    val resolvedAppVersion = appVersion.ifEmpty { UNKNOWN_VERSION }
    val messages = chunk.mapIndexed { idx, msg ->
        IndexMessageEntry(
            n = idx + 1,
            ts = msg.timestampIso,
            sender = msg.sender,
            hash = computeMessageHash(msg.chatId, msg.timestampIso, msg.sender, msg.body),
        )
    }
    return MailIndex(
        schema = INDEX_SCHEMA,
        chatId = chunk[0].chatId,
        displayName = displayName,
        messageId = messageId,
        chunkSize = chunkSize,
        count = chunk.size,
        firstTs = chunk[0].timestampIso,
        lastTs = chunk.last().timestampIso,
        appVersion = resolvedAppVersion,
        messages = messages,
    )
}

/**
 * Mirrors `index_bytes`: hand-assembled layout (one meta key per line, one
 * message entry per line) rather than `json.dumps(indent=...)` — see the
 * Python docstring. Byte-for-byte match is required for the golden-fixture
 * test, including key order.
 */
fun indexBytes(index: MailIndex): ByteArray {
    val lines = mutableListOf("{")
    lines.add("  ${jsonQuote("schema")}: ${index.schema},")
    lines.add("  ${jsonQuote("chat_id")}: ${jsonQuote(index.chatId)},")
    lines.add("  ${jsonQuote("display_name")}: ${jsonQuote(index.displayName)},")
    lines.add("  ${jsonQuote("message_id")}: ${jsonQuote(index.messageId)},")
    lines.add("  ${jsonQuote("chunk")}: ${index.chunkSize.toJsonValue().render()},")
    lines.add("  ${jsonQuote("count")}: ${index.count},")
    lines.add("  ${jsonQuote("first_ts")}: ${jsonQuote(index.firstTs)},")
    lines.add("  ${jsonQuote("last_ts")}: ${jsonQuote(index.lastTs)},")
    lines.add("  ${jsonQuote("app_version")}: ${jsonQuote(index.appVersion)},")
    lines.add("  ${jsonQuote("messages")}: [")

    val entries = index.messages.map { m ->
        // Compact, comma-separated (Python's separators=(",", ":")) — no spaces.
        "{${jsonQuote("n")}:${m.n},${jsonQuote("ts")}:${jsonQuote(m.ts)}," +
            "${jsonQuote("sender")}:${jsonQuote(m.sender)},${jsonQuote("hash")}:${jsonQuote(m.hash)}}"
    }
    for ((i, entry) in entries.withIndex()) {
        val trailing = if (i < entries.size - 1) "," else ""
        lines.add("    $entry$trailing")
    }
    lines.add("  ]")
    lines.add("}")
    return (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
}

/** Mirrors `_header_safe`: collapses whitespace so a header value can't break out of its line. */
fun headerSafe(value: String): String = value.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
