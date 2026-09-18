package com.chatmailsync.core.mail

/**
 * Kotlin port of the one corner of Python's `email.header.Header` encoding
 * this module's headers actually exercise: a single string assigned in one
 * call to a header (`msg["Subject"] = value`) either stays literal (pure
 * ASCII) or becomes one RFC 2047 `=?utf-8?q?...?=` encoded-word (any
 * non-ASCII content) -- mirroring `email.quoprimime.header_encode`'s safe-set
 * exactly (`_QUOPRI_HEADER_MAP`: letters, digits, `-!*+/` literal; space
 * becomes `_`; everything else is `=XX` against the UTF-8 bytes).
 *
 * Deliberately NOT implemented: header line folding for values that exceed
 * Python's default 78-char `max_line_length`. None of this port's fixed
 * fixture headers are long enough to trigger it in the Python reference
 * either -- see the PR body for this as a known gap.
 */
object Rfc2047 {
    private val SAFE = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '!', '*', '+', '/')

    /** Renders [value] the way `msg[name] = value` + `Message.as_bytes()` would, for a single-append header. */
    fun encodeHeaderValue(value: String): String {
        if (isAscii(value)) return value
        val bytes = value.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            when {
                c == ' ' -> sb.append('_')
                c.code < 128 && c in SAFE -> sb.append(c)
                else -> sb.append("=%02X".format(b.toInt() and 0xFF))
            }
        }
        return "=?utf-8?q?$sb?="
    }

    private fun isAscii(s: String): Boolean = s.all { it.code < 128 }
}
