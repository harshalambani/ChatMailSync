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
        return messages.chunked(chunkSize.n)
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
