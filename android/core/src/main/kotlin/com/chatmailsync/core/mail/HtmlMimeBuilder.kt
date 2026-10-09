package com.chatmailsync.core.mail

import java.util.Base64

/**
 * Kotlin port of `_build_html_mime_message` (`src/mail_client.py` ~1550-1625),
 * the builder production actually uses (`push_chunks` calls it for every email).
 *
 * Structure, identical to Python's:
 *
 *     multipart/mixed
 *     +-- multipart/related
 *     |   +-- text/html (the bubbles)
 *     |   +-- inline images x N   (Content-ID, inline)
 *     +-- <attachment> x N   (Content-Disposition: attachment)
 *     +-- application/json   (the traceability index, always last)
 *
 * Like [MimeBuilder] it writes the bytes by hand to match what Python's
 * `email.mime` + `Generator` produce, and every header goes through
 * [Compat32Headers.fold], so folding and RFC 2047 encoding are Python's.
 *
 * Header handling:
 *  - Headers built from a chat name or id go through [Compat32Headers] (which
 *    neutralises CR/LF/controls) and the chat id through [headerSafe].
 *  - An attachment filename is written the way `add_header` writes it: quoted
 *    with `\` and `"` escaped when pure ASCII, RFC 2231 (`filename*=utf-8''..`)
 *    when not. Python 3.13 cannot write an ASCII filename holding CR, LF, VT,
 *    FF or FS/GS/RS (it raises while serialising); here such a filename is
 *    refused up front with [UnsafeHeaderValueException]. A non-ASCII name is
 *    percent-encoded by RFC 2231, so a line break inside it is written as
 *    `%0A`. Either way no extra header line or MIME part can appear.
 */
object HtmlMimeBuilder {

    /** The ASCII characters Python's header writer cannot put on one line. */
    private val REFUSED_IN_ASCII_FILENAME =
        setOf('\r', '\n', '\u000B', '\u000C', '\u001C', '\u001D', '\u001E')

    /** Controls Python writes raw but that must not travel in a header: not TAB, not the refused set. */
    private fun isBareControl(c: Char): Boolean =
        c.code in 0x00..0x08 || c.code in 0x0E..0x1B || c.code == 0x1F || c.code == 0x7F

    private val MIME_TYPE = Regex("^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$")

    private fun leaf(sb: StringBuilder, headers: List<Pair<String, String>>, payload: String) {
        for ((n, v) in headers) sb.append(Compat32Headers.fold(n, v)).append('\n')
        sb.append('\n').append(payload)
    }

    /** Mirrors `MIMEBase(main, sub)` + `encode_base64`: the headers every binary part starts with. */
    private fun binaryHeaders(mimeType: String): MutableList<Pair<String, String>> {
        if (!MIME_TYPE.matches(mimeType)) {
            throw UnsafeHeaderValueException("an attachment has a MIME type that cannot be written into a header")
        }
        return mutableListOf(
            "Content-Type" to mimeType,
            "MIME-Version" to "1.0",
            "Content-Transfer-Encoding" to "base64",
        )
    }

    /** Mirrors `add_header("Content-Disposition", kind, filename=name)` (`email.message._formatparam`). */
    internal fun contentDisposition(kind: String, filename: String): String {
        if (filename.isEmpty()) return "$kind; filename"
        if (filename.all { it.code < 128 }) {
            if (filename.any { it in REFUSED_IN_ASCII_FILENAME }) {
                throw UnsafeHeaderValueException(
                    "an attachment filename contains a line break and cannot be written into a header",
                )
            }
            // Deliberate difference from Python 3.13, which writes NUL and the
            // other bare control characters into the header unchanged. A raw
            // NUL inside an IMAP literal is not something servers agree on, so
            // such a name takes the RFC 2231 form, which round-trips it as
            // %00 and writes nothing unsafe. Tab is legal in a quoted string
            // and stays as Python writes it.
            if (filename.any { isBareControl(it) }) {
                return "$kind; filename*=utf-8''${percentEncodeUtf8(filename)}"
            }
            val escaped = filename.replace("\\", "\\\\").replace("\"", "\\\"")
            return "$kind; filename=\"$escaped\""
        }
        return "$kind; filename*=utf-8''${percentEncodeUtf8(filename)}"
    }

    /** `urllib.parse.quote(s, safe='')`: only A-Z a-z 0-9 _ . - ~ stay literal. */
    private fun percentEncodeUtf8(s: String): String {
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '.' || ch == '-' || ch == '~') {
                sb.append(ch)
            } else {
                sb.append('%').append(hex[c shr 4]).append(hex[c and 15])
            }
        }
        return sb.toString()
    }

    private fun checkCid(cid: String) {
        if (cid.isEmpty() || cid.any { it.code < 0x21 || it.code == 0x7F || it == '<' || it == '>' }) {
            throw UnsafeHeaderValueException("an inline image id is not a plain token")
        }
    }

    /**
     * Mirrors `_build_html_mime_message`, returning the RFC 2822 message bytes
     * with "\n" line ends (the transport turns them into CRLF). [suffix] goes
     * into the Subject only, like Python's parameter.
     *
     * [boundaryRoot] / [boundaryRelated] exist so tests can pin the two MIME
     * boundaries; production leaves them random, like Python.
     */
    fun buildRaw(
        displayName: String,
        chunk: List<ParsedMessage>,
        chunkSize: ChunkSize,
        rendered: HtmlRenderer.RenderedChunk,
        messageId: String,
        suffix: String = "",
        inReplyTo: String? = null,
        references: String? = null,
        appVersion: String = UNKNOWN_VERSION,
        boundaryRoot: String = MimeBuilder.defaultBoundary(),
        boundaryRelated: String = MimeBuilder.defaultBoundary(),
    ): ByteArray {
        require(chunk.isNotEmpty()) { "chunk must not be empty" }

        // Validate everything that can be refused BEFORE assembling.
        val attachmentDispositions = rendered.attachments.map { contentDisposition("attachment", it.filename) }
        for (img in rendered.inlineParts) checkCid(img.cid)

        val indexBytes = indexBytes(buildIndex(displayName, chunk, chunkSize, messageId, appVersion))

        val sb = StringBuilder()

        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "multipart/mixed; boundary=\"$boundaryRoot\""
        headers["MIME-Version"] = "1.0"
        headers["Subject"] = MimeBuilder.chunkSubject(displayName, chunk, chunkSize, suffix)
        headers["From"] = MimeBuilder.formatSender(displayName)
        headers["To"] = "me"
        headers["Message-ID"] = messageId
        headers["Date"] = chunk[0].timestamp.format(MimeBuilder.RFC822_DATE) + " +0000"
        headers[HEADER_VERSION] = INDEX_SCHEMA.toString()
        headers[HEADER_CHAT] = headerSafe(chunk[0].chatId)
        headers[HEADER_COUNT] = chunk.size.toString()
        headers[HEADER_INDEX] = INDEX_FILENAME
        if (!inReplyTo.isNullOrEmpty()) {
            headers["In-Reply-To"] = inReplyTo
            headers["References"] = references?.takeIf { it.isNotEmpty() } ?: inReplyTo
        }
        for ((name, value) in headers) sb.append(Compat32Headers.fold(name, value)).append('\n')
        sb.append('\n')

        sb.append("--").append(boundaryRoot).append('\n')

        // multipart/related: its own header block, then HTML + inline images.
        sb.append(Compat32Headers.fold("Content-Type", "multipart/related; boundary=\"$boundaryRelated\"")).append('\n')
        sb.append("MIME-Version: 1.0\n\n")
        sb.append("--").append(boundaryRelated).append('\n')
        leaf(
            sb,
            listOf(
                "Content-Type" to "text/html; charset=\"utf-8\"",
                "MIME-Version" to "1.0",
                "Content-Transfer-Encoding" to "base64",
            ),
            MimeBuilder.base64Lines(rendered.htmlBody.toByteArray(Charsets.UTF_8)),
        )
        for (img in rendered.inlineParts) {
            sb.append('\n').append("--").append(boundaryRelated).append('\n')
            val h = binaryHeaders(img.mimeType)
            h.add("Content-ID" to "<${img.cid}>")
            h.add("Content-Disposition" to "inline")
            leaf(sb, h, MimeBuilder.base64Lines(img.data))
        }
        sb.append('\n').append("--").append(boundaryRelated).append("--\n")

        for ((i, att) in rendered.attachments.withIndex()) {
            sb.append('\n').append("--").append(boundaryRoot).append('\n')
            val h = binaryHeaders(att.mimeType)
            h.add("Content-Disposition" to attachmentDispositions[i])
            leaf(sb, h, MimeBuilder.base64Lines(att.data))
        }

        // The index goes last, so it sorts after the chat's own attachments.
        sb.append('\n').append("--").append(boundaryRoot).append('\n')
        leaf(
            sb,
            listOf(
                "Content-Type" to "application/json",
                "MIME-Version" to "1.0",
                "Content-Transfer-Encoding" to "base64",
                "Content-Disposition" to contentDisposition("attachment", INDEX_FILENAME),
            ),
            MimeBuilder.base64Lines(indexBytes),
        )
        sb.append('\n').append("--").append(boundaryRoot).append("--\n")

        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Python's return pair (base64url raw message, label id). Prefer [buildRaw] in new code. */
    fun build(
        displayName: String,
        chunk: List<ParsedMessage>,
        chunkSize: ChunkSize,
        rendered: HtmlRenderer.RenderedChunk,
        labelId: String,
        messageId: String,
        suffix: String = "",
        inReplyTo: String? = null,
        references: String? = null,
        appVersion: String = UNKNOWN_VERSION,
    ): Pair<String, String> {
        val raw = buildRaw(displayName, chunk, chunkSize, rendered, messageId, suffix, inReplyTo, references, appVersion)
        return Pair(Base64.getUrlEncoder().encodeToString(raw), labelId)
    }
}
