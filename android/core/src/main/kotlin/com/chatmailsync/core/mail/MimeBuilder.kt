package com.chatmailsync.core.mail

import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

/**
 * Kotlin port of the plain-text MIME construction path in
 * `src/mail_client.py`: `_format_chunk_body`, `_chunk_subject`,
 * `_format_sender`, `_build_mime_message`, `_new_message_id` (lines
 * ~1450-1543 there).
 *
 * `_build_html_mime_message` (the HTML-rendered alternative, which depends
 * on the unported `src/html_renderer.py`) is out of scope for this phase --
 * see the PR body.
 *
 * The message is assembled by hand to exactly match what Python's
 * `email.mime` package + `Generator` produce for this specific shape of
 * message (a two-part multipart/mixed: one text/plain part, one
 * application/json part, both base64), rather than depending on a MIME
 * library. Known, deliberate gaps vs. the general email package (none of
 * which this app's own fixed message shape ever exercises): no RFC 2822
 * header line-folding for values over ~78 chars, and RFC 2047 encoding is
 * implemented only for the single-string-append case every header here
 * uses (see Rfc2047.kt) -- not the general multi-chunk case.
 */
object MimeBuilder {

    private val TIME_HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE_YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val DATE_YMD_H00: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH")
    private val RFC822_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss", java.util.Locale.ENGLISH)

    /** Mirrors `_new_message_id`. */
    fun newMessageId(): String = "<wa-sync-${UUID.randomUUID().toString().replace("-", "")}@local>"

    /** Mirrors `_format_chunk_body`. */
    fun formatChunkBody(chunk: List<ParsedMessage>): String {
        val lines = mutableListOf<String>()
        for (msg in chunk) {
            val timeStr = msg.timestamp.format(TIME_HHMM)
            val header = "[$timeStr] ${msg.sender}:"
            // Mirrors Python's str.splitlines(): our port covers the common
            // line separators (\n, \r\n, \r); Python additionally splits on a
            // handful of rarer unicode line boundaries (\v, \f, \x1c-\x1e,
            // \x85,  ,  ), which chat text never contains -- a
            // documented, deliberate gap.
            val bodyLines = splitLines(msg.body)
            if (bodyLines.isNotEmpty()) {
                lines.add("$header ${bodyLines[0]}")
                val indent = " ".repeat(header.length + 1)
                for (continuation in bodyLines.drop(1)) {
                    lines.add("$indent$continuation")
                }
            } else {
                lines.add(header)
            }
        }
        return lines.joinToString("\n")
    }

    private fun splitLines(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        return s.split(Regex("\r\n|\r|\n"))
    }

    /** Mirrors `_chunk_subject`. */
    fun chunkSubject(displayName: String, chunk: List<ParsedMessage>, chunkSize: ChunkSize, suffix: String = ""): String {
        val firstTs = chunk[0].timestamp
        val label = when (chunkSize) {
            ChunkSize.Hour -> firstTs.format(DATE_YMD_H00) + ":00"
            ChunkSize.Week -> {
                val isoYear = firstTs.get(IsoFields.WEEK_BASED_YEAR)
                val isoWeek = firstTs.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                "Week ${"%02d".format(isoWeek)}, $isoYear"
            }
            is ChunkSize.Count -> firstTs.format(DATE_YMD) + " (+${chunk.size} msgs)"
            ChunkSize.Day -> firstTs.format(DATE_YMD)
        }
        val base = "WhatsApp: $displayName — $label"
        return if (suffix.isNotEmpty()) "$base  ($suffix)" else base
    }

    private val PHONE_STRIP = Regex("[\\s\\-()]")
    private val PHONE_MATCH = Regex("^\\+?\\d{7,15}$")

    /** Mirrors `_format_sender`. */
    fun formatSender(displayName: String): String {
        val stripped = PHONE_STRIP.replace(displayName, "")
        if (PHONE_MATCH.matches(stripped)) {
            val normalized = if (stripped.startsWith("+")) stripped else "+$stripped"
            return formatAddr(normalized, "whatsapp-sync@local")
        }
        return formatAddr(displayName, "whatsapp-sync@local")
    }

    private val ADDR_SPECIALS = Regex("[\\[\\]\\\\()<>@,:;\".]")
    private val ADDR_ESCAPES = Regex("[\\\\\"]")

    /** Mirrors `email.utils.formataddr((name, address))` for the ASCII-name case this app's names take. */
    fun formatAddr(name: String, address: String): String {
        if (name.isEmpty()) return address
        if (!name.all { it.code < 128 }) {
            // Non-ASCII display name: Python charset-encodes the name only
            // (email.charset.Charset.header_encode), not the whole "name
            // <addr>" string. Approximated here with the same RFC 2047
            // machinery used for headers -- see Rfc2047.kt.
            val encodedName = Rfc2047.encodeHeaderValue(name)
            return "$encodedName <$address>"
        }
        val quotes = if (ADDR_SPECIALS.containsMatchIn(name)) "\"" else ""
        val escaped = ADDR_ESCAPES.replace(name) { "\\" + it.value }
        return "$quotes$escaped$quotes <$address>"
    }

    /**
     * Mirrors `_build_mime_message`: builds the RFC 2822 message and returns
     * the base64url-encoded raw bytes + labelId, ready for `ImapTransport`.
     */
    fun buildMimeMessage(
        displayName: String,
        chunk: List<ParsedMessage>,
        chunkSize: ChunkSize,
        labelId: String,
        messageId: String,
        appVersion: String = UNKNOWN_VERSION,
        inReplyTo: String? = null,
        references: String? = null,
        boundary: String = defaultBoundary(),
    ): Pair<String, String> {
        require(chunk.isNotEmpty()) { "chunk must not be empty" }

        val bodyText = formatChunkBody(chunk)
        val indexPart = indexBytes(buildIndex(displayName, chunk, chunkSize, messageId, appVersion))

        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "multipart/mixed; boundary=\"$boundary\""
        headers["MIME-Version"] = "1.0"
        headers["Subject"] = Rfc2047.encodeHeaderValue(chunkSubject(displayName, chunk, chunkSize))
        headers["From"] = formatSender(displayName)
        headers["To"] = "me"
        headers["Message-ID"] = messageId
        headers["Date"] = chunk[0].timestamp.format(RFC822_DATE) + " +0000"
        headers[HEADER_VERSION] = INDEX_SCHEMA.toString()
        headers[HEADER_CHAT] = headerSafe(chunk[0].chatId)
        headers[HEADER_COUNT] = chunk.size.toString()
        headers[HEADER_INDEX] = INDEX_FILENAME
        if (!inReplyTo.isNullOrEmpty()) {
            headers["In-Reply-To"] = inReplyTo
            headers["References"] = references ?: inReplyTo
        }

        val sb = StringBuilder()
        for ((name, value) in headers) {
            sb.append(name).append(": ").append(value).append('\n')
        }
        sb.append('\n')

        // Part 1: text/plain, base64, utf-8.
        sb.append("--").append(boundary).append('\n')
        sb.append("Content-Type: text/plain; charset=\"utf-8\"\n")
        sb.append("MIME-Version: 1.0\n")
        sb.append("Content-Transfer-Encoding: base64\n")
        sb.append('\n')
        sb.append(base64Lines(bodyText.toByteArray(Charsets.UTF_8)))

        // Part 2: application/json (the traceability index), base64.
        sb.append('\n').append("--").append(boundary).append('\n')
        sb.append("Content-Type: application/json\n")
        sb.append("MIME-Version: 1.0\n")
        sb.append("Content-Transfer-Encoding: base64\n")
        sb.append("Content-Disposition: attachment; filename=\"$INDEX_FILENAME\"\n")
        sb.append('\n')
        sb.append(base64Lines(indexPart))

        sb.append('\n').append("--").append(boundary).append("--\n")

        val rawBytes = sb.toString().toByteArray(Charsets.UTF_8)
        val raw = Base64.getUrlEncoder().encodeToString(rawBytes)
        return Pair(raw, labelId)
    }

    /** Mirrors `base64.encodebytes`: 76-char lines, trailing newline after the last line. */
    fun base64Lines(data: ByteArray): String {
        if (data.isEmpty()) return "\n"
        val full = Base64.getEncoder().encodeToString(data)
        val sb = StringBuilder()
        var i = 0
        while (i < full.length) {
            val end = minOf(i + 76, full.length)
            sb.append(full, i, end).append('\n')
            i = end
        }
        return sb.toString()
    }

    private fun defaultBoundary(): String {
        // Shape only needs to survive the golden-fixture test's boundary
        // normalization (a regex over "===...==" style tokens) -- it need
        // not match Python's exact RNG output.
        val token = Random.nextLong(0, Long.MAX_VALUE)
        return "===============${token}=="
    }
}
