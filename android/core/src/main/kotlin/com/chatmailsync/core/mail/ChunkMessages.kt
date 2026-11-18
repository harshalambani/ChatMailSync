package com.chatmailsync.core.mail

import java.time.temporal.IsoFields

/** Mirrors Python's `ChunkSize = Union[Literal["day","hour","week"], int]`. */
sealed class ChunkSize {
    object Day : ChunkSize()
    object Hour : ChunkSize()
    object Week : ChunkSize()
    data class Count(val n: Int) : ChunkSize()

    /** The value `mail_index.build_index` stamps into the "chunk" field. */
    fun toJsonValue(): JsonValue = when (this) {
        Day -> JsonValue.Str("day")
        Hour -> JsonValue.Str("hour")
        Week -> JsonValue.Str("week")
        is Count -> JsonValue.Num(n.toLong())
    }
}

/**
 * Kotlin port of `chunk_messages` (mail_client.py lines ~1394-1442). Groups a
 * sorted (ascending) list of [ParsedMessage] into non-empty sublists,
 * preserving order.
 */
fun chunkMessages(messages: List<ParsedMessage>, chunkSize: ChunkSize = ChunkSize.Day): List<List<ParsedMessage>> {
    if (messages.isEmpty()) return emptyList()

    if (chunkSize is ChunkSize.Count) {
        require(chunkSize.n > 0) { "chunk_size must be 'day', 'hour', 'week', or a positive int; got ${chunkSize.n}" }
        // BUG-24: a boundary never falls between two messages with one fingerprint.
        val locked = lockedCuts(messages)
        val out = ArrayList<List<ParsedMessage>>()
        var start = 0
        while (start < messages.size) {
            var end = minOf(start + chunkSize.n, messages.size)
            while (end < messages.size && locked[end]) end++
            out.add(messages.subList(start, end))
            start = end
        }
        return out
    }

    fun bucket(ts: java.time.LocalDateTime): List<Int> = when (chunkSize) {
        ChunkSize.Hour -> listOf(ts.year, ts.monthValue, ts.dayOfMonth, ts.hour)
        ChunkSize.Week -> {
            val isoYear = ts.get(IsoFields.WEEK_BASED_YEAR)
            val isoWeek = ts.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            listOf(isoYear, isoWeek)
        }
        else -> listOf(ts.year, ts.monthValue, ts.dayOfMonth) // "day"
    }

    val chunks = mutableListOf<List<ParsedMessage>>()
    var currentBucket: List<Int>? = null
    var currentChunk = mutableListOf<ParsedMessage>()

    for (msg in messages) {
        val b = bucket(msg.timestamp)
        if (b != currentBucket) {
            if (currentChunk.isNotEmpty()) chunks.add(currentChunk)
            currentChunk = mutableListOf(msg)
            currentBucket = b
        } else {
            currentChunk.add(msg)
        }
    }
    if (currentChunk.isNotEmpty()) chunks.add(currentChunk)
    return chunks
}

/**
 * BUG-24: `result[i]` is true when a split between message i-1 and message i is forbidden because
 * both sides hold a message with the same fingerprint (same chat, minute, sender and text).
 * The ledger keeps ONE row per fingerprint, so two such copies must travel in the same email: if
 * they were split and the run ended after the first email, the second copy would look filed.
 * Index 0 is never a cut. Nothing here changes a fingerprint or the ledger.
 */
internal fun lockedCuts(messages: List<ParsedMessage>): BooleanArray {
    val locked = BooleanArray(messages.size + 1)
    val first = HashMap<String, Int>()
    for ((i, m) in messages.withIndex()) {
        val h = computeMessageHash(m.chatId, m.timestampIso, m.sender, m.body)
        val f = first.putIfAbsent(h, i)
        if (f != null) for (c in (f + 1)..i) locked[c] = true
    }
    return locked
}

/**
 * BUG-24: where to halve [messages]: the allowed cut nearest the middle (the middle itself when
 * no fingerprint repeats, exactly as before), or null when every cut would separate two copies.
 */
internal fun splitPoint(messages: List<ParsedMessage>): Int? {
    if (messages.size < 2) return null
    val locked = lockedCuts(messages)
    val mid = messages.size / 2
    for (d in 0..messages.size) {
        if (mid - d >= 1 && !locked[mid - d]) return mid - d
        if (mid + d <= messages.size - 1 && !locked[mid + d]) return mid + d
    }
    return null
}
