package com.chatmailsync.core.mail

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Minimal top-level RFC 822 header parser -- just enough for
 * `ImapTransport.messagesInsert` to read `Date` and `Message-ID` back off a
 * raw message the same way Python's `email.message_from_bytes(...).get(...)`
 * does. Does not parse MIME structure or bodies.
 */
class MessageHeaders private constructor(private val values: List<Pair<String, String>>) {
    /** First header matching [name], case-insensitively -- mirrors `Message.get`. */
    operator fun get(name: String): String? =
        values.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    companion object {
        fun parse(rawBytes: ByteArray): MessageHeaders {
            val text = String(rawBytes, Charsets.UTF_8)
            val headerBlockEnd = text.indexOf("\r\n\r\n").let { if (it == -1) text.length else it }
            val headerBlock = text.substring(0, headerBlockEnd)
            val rawLines = headerBlock.split("\r\n")
            val out = mutableListOf<Pair<String, String>>()
            for (line in rawLines) {
                if (line.isEmpty()) continue
                if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) {
                    val (n, v) = out.removeAt(out.size - 1)
                    out.add(n to "$v ${line.trim()}")
                    continue
                }
                val idx = line.indexOf(':')
                if (idx == -1) continue
                val name = line.substring(0, idx)
                val value = line.substring(idx + 1).trim()
                out.add(name to value)
            }
            return MessageHeaders(out)
        }
    }
}

private val INTERNALDATE_FORMAT: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

/**
 * Mirrors `_internaldate_from_message`: derives an APPEND internaldate from
 * the message's own Date header -- never the clock. Returns null (never
 * throws) when the header is missing or unparseable, same as the Python
 * original; the IMAP server then defaults internaldate to the upload time.
 */
fun internaldateFromHeaders(headers: MessageHeaders): OffsetDateTime? {
    val dateHeader = headers["Date"] ?: return null
    return try {
        OffsetDateTime.parse(dateHeader, INTERNALDATE_FORMAT)
    } catch (_: Exception) {
        null
    }
}
