package com.chatmailsync.core.mail

/**
 * Kotlin port of the modified UTF-7 helpers in `src/mail_client.py`
 * (`_encode_imap_utf7` / `_decode_imap_utf7` / `_quote_imap_mailbox`,
 * lines ~263-355 there). RFC 3501 5.1.3 modified UTF-7: shift char is `&`
 * (not `+`), base64 alphabet substitutes `,` for `/`, and there is no `=`
 * padding.
 *
 * Both functions iterate the string by UTF-16 code unit (Kotlin's native
 * `Char`), exactly matching what Python's `text[i:j].encode("utf-16-be")`
 * produces for a code-point run, including runs that span a surrogate
 * pair -- the resulting bytes are identical either way.
 */
object ImapUtf7 {
    private const val STD_B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+,"

    fun encode(text: String): String {
        val out = StringBuilder()
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            val code = ch.code
            if (ch == '&') {
                out.append("&-")
                i += 1
                continue
            }
            if (code in 0x20..0x7E) {
                out.append(ch)
                i += 1
                continue
            }
            // Collect a run of non-printable-ASCII code units.
            var j = i
            while (j < n) {
                val c = text[j]
                if (c == '&' || c.code in 0x20..0x7E) break
                j += 1
            }
            val run = text.substring(i, j)
            val bytes = run.toByteArray(Charsets.UTF_16BE)
            out.append('&')
            out.append(base64ModifiedNoPad(bytes))
            out.append('-')
            i = j
        }
        return out.toString()
    }

    fun decode(text: String): String {
        val out = StringBuilder()
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (ch != '&') {
                out.append(ch)
                i += 1
                continue
            }
            // ch == '&'
            if (i + 1 < n && text[i + 1] == '-') {
                out.append('&')
                i += 2
                continue
            }
            // Python scans to the terminating '-' or to the end of the text, and in
            // both cases tries to decode what it found (PAR-06): an unterminated
            // "&AGE" decodes; it is not kept verbatim.
            val found = text.indexOf('-', i + 1)
            val end = if (found == -1) n else found
            val decoded = base64ModifiedDecode(text.substring(i + 1, end))
            if (decoded == null) {
                // Malformed: keep the original sequence verbatim, like Python.
                out.append(text, i, minOf(end + 1, n))
            } else {
                out.append(decoded)
            }
            i = if (end < n) end + 1 else end
        }
        return out.toString()
    }

    /**
     * Quote a wire mailbox name per RFC 3501 4.3 quoted-string.
     *
     * SEC-02: a quoted-string cannot carry CR, LF or NUL, and a stray one
     * would let the value end the command and start another. Any control
     * character (below 0x20, or 0x7F) is refused BEFORE a byte is written.
     * The error text is fixed and never contains the value.
     */
    fun quoteMailbox(wireName: String): String {
        requireNoControl(wireName)
        val escaped = wireName.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    /**
     * Validates an email or password for the LOGIN line (SEC-02), without
     * ever putting the value in an error: no control characters, and ASCII
     * only (IMAP LOGIN takes an ASCII quoted-string; literals are not
     * supported here, matching the Python client, which stops with an
     * encoding error). [what] is "email address" or "app password".
     */
    fun requireLoginSafe(value: String, what: String) {
        if (value.any { isControl(it) }) {
            throw MailTransportError(
                "Refused: the $what contains a line break or control character, so nothing was sent.",
                400,
            )
        }
        if (value.any { it.code > 0x7E }) {
            throw MailTransportError(
                "Refused: the $what contains a character outside plain ASCII, which IMAP sign-in " +
                    "cannot carry. Check it for accented or special characters; nothing was sent.",
                400,
            )
        }
    }

    /**
     * Refuses a folder name with any control character. [encode] would turn
     * such a character into harmless modified-UTF-7, but a folder called
     * "a<LF>b" is never legitimate, so it is stopped at the source, before
     * encoding. The error never contains the name.
     */
    fun requireNoControl(name: String) {
        if (name.any { isControl(it) }) {
            throw MailTransportError("Refused: a folder name contains a control character, so nothing was sent.", 400)
        }
    }

    private fun isControl(c: Char): Boolean = c.code < 0x20 || c.code == 0x7F

    private fun base64ModifiedNoPad(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val n = (b0 shl 16) or (b1 shl 8) or b2
            val c0 = (n shr 18) and 0x3F
            val c1 = (n shr 12) and 0x3F
            val c2 = (n shr 6) and 0x3F
            val c3 = n and 0x3F
            val remaining = bytes.size - i
            sb.append(B64_ALPHABET[c0])
            sb.append(B64_ALPHABET[c1])
            if (remaining > 1) sb.append(B64_ALPHABET[c2])
            if (remaining > 2) sb.append(B64_ALPHABET[c3])
            i += 3
        }
        return sb.toString()
    }

    /**
     * Python's `base64.b64decode(chunk.replace(",", "/") + "=" * (-len % 4))`
     * followed by `.decode("utf-16-be")`, or null where either step raises
     * (PAR-06). Non-alphabet characters are silently dropped (b64decode's
     * non-strict mode), the padding is computed from the ORIGINAL length, and
     * a data-character count of 1 mod 4, or leftover data without enough
     * padding, is an error. The UTF-16 step is strict: an odd byte count or
     * a lone surrogate is an error, never a replacement character.
     */
    private fun base64ModifiedDecode(chunk: String): String? {
        val text = chunk.replace(',', '/')
        val padded = text + "=".repeat((4 - text.length % 4) % 4)
        val out = java.io.ByteArrayOutputStream()
        var quadPos = 0
        var left = 0
        var pads = 0
        for (ch in padded) {
            if (ch == '=') {
                // CPython 3.13 (non-strict): padding never ends the input, it only counts
                // toward completing the final quad; data after it carries on.
                if (quadPos >= 2) pads++
                continue
            }
            val v = STD_B64.indexOf(ch)
            if (v == -1) continue
            pads = 0
            when (quadPos) {
                0 -> { quadPos = 1; left = v }
                1 -> { quadPos = 2; out.write((left shl 2) or (v shr 4)); left = v and 0xF }
                2 -> { quadPos = 3; out.write((left shl 4) or (v shr 2)); left = v and 0x3 }
                else -> { quadPos = 0; out.write((left shl 6) or v); left = 0 }
            }
        }
        // One data character left over, or a partial quad without enough '=' to finish it, is an error.
        if (quadPos == 1 || (quadPos != 0 && quadPos + pads < 4)) return null
        return try {
            Charsets.UTF_16BE.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(out.toByteArray()))
                .toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }
}
