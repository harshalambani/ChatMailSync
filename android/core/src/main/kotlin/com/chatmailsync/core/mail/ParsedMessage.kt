package com.chatmailsync.core.mail

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Kotlin mirror of `src/parser.ParsedMessage` (Python). Only the fields the
 * mail-transport/MIME/index code actually reads are ported here — parsing
 * itself (src/parser.py) is a separate, later phase.
 *
 * [timestamp] is naive local time, exactly like the Python dataclass — no
 * zone is attached, matching `datetime` there.
 */
data class ParsedMessage(
    val chatId: String,
    val timestamp: LocalDateTime,
    val sender: String,
    val body: String,
    val attachmentFilename: String? = null,
) {
    /** Mirrors `timestamp.isoformat(timespec="seconds")` in Python. */
    val timestampIso: String = timestamp.format(TIMESTAMP_ISO_FORMAT)

    companion object {
        private val TIMESTAMP_ISO_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
