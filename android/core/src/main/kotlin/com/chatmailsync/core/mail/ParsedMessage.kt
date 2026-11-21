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
    /**
     * BUG-24: which identical copy this is. Within one chat, minute, sender and text the first copy
     * in the file is 1, the next 2, and so on; set by [parseFile] from the whole file. Copy 1 keeps
     * today's fingerprint exactly, so nothing already filed is sent again.
     */
    val occurrence: Int = 1,
) {
    /** Mirrors `timestamp.isoformat(timespec="seconds")` in Python. */
    val timestampIso: String = timestamp.format(TIMESTAMP_ISO_FORMAT)

    companion object {
        private val TIMESTAMP_ISO_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
