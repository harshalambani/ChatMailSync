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
            val end = text.indexOf('-', i + 1)
            if (end == -1) {
                // Malformed: no terminating '-'. Keep verbatim, like Python.
                out.append(text.substring(i))
                i = n
                continue
            }
            val chunk = text.substring(i + 1, end)
            val decoded = base64ModifiedDecode(chunk)
            if (decoded == null) {
                // Malformed base64: keep the original sequence verbatim.
                out.append(text, i, end + 1)
            } else {
                out.append(String(decoded, Charsets.UTF_16BE))
            }
            i = end + 1
        }
        return out.toString()
    }

    /** Quote a wire mailbox name per RFC 3501 4.3 quoted-string. */
    fun quoteMailbox(wireName: String): String {
        val escaped = wireName.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

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

    private fun base64ModifiedDecode(chunk: String): ByteArray? {
        if (chunk.isEmpty()) return ByteArray(0)
        val values = IntArray(chunk.length)
        for (idx in chunk.indices) {
            val v = B64_ALPHABET.indexOf(chunk[idx])
            if (v == -1) return null
            values[idx] = v
        }
        val out = java.io.ByteArrayOutputStream()
        var bitsBuf = 0
        var bitsCount = 0
        for (v in values) {
            bitsBuf = (bitsBuf shl 6) or v
            bitsCount += 6
            if (bitsCount >= 8) {
                bitsCount -= 8
                val byte = (bitsBuf shr bitsCount) and 0xFF
                out.write(byte)
            }
        }
        return out.toByteArray()
    }
}
