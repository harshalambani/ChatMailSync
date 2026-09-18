package com.chatmailsync.core.mail

/**
 * Minimal JSON value serialiser matching Python's `json.dumps(value,
 * ensure_ascii=False)` for exactly the value shapes `mail_index.index_bytes`
 * needs: strings, ints, and the two used only at the top level (never
 * nested) here. Kept hand-written rather than pulling in a JSON library so
 * `:core` stays dependency-free -- see the PR body.
 */
sealed class JsonValue {
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Long) : JsonValue()

    fun render(): String = when (this) {
        is Str -> jsonQuote(value)
        is Num -> value.toString()
    }
}

/** Mirrors Python's `json.dumps` string escaping with `ensure_ascii=False`. */
fun jsonQuote(s: String): String {
    val sb = StringBuilder()
    sb.append('"')
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20) {
                    sb.append(String.format("\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append('"')
    return sb.toString()
}
