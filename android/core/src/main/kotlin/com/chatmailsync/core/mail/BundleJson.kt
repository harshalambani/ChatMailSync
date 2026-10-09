package com.chatmailsync.core.mail

/**
 * The JSON reader and writer the `.cmsbackup` bundle needs ([Migration.kt]). `Json.kt`
 * only writes two value shapes for the mail index; a bundle's `manifest.json` and
 * `settings.json` carry arbitrary small documents, so this file adds a full reader and a
 * writer that matches Python's `json.dumps(value, indent=2)` (the call `migration.py`
 * makes, with the default `ensure_ascii=True`) so a Kotlin-written member reads the same
 * to a person and to Python.
 *
 * Values are plain Kotlin: `null`, [Boolean], [Long] (or [Double] for a fraction or an
 * integer too big for a Long), [String], [List] and [Map] (insertion-ordered).
 */
internal class BundleJsonException(message: String) : RuntimeException(message)

internal fun parseBundleJson(text: String): Any? {
    val p = BundleJsonParser(text)
    val v = p.value()
    p.skipSpace()
    if (!p.atEnd()) throw BundleJsonException("trailing content")
    return v
}

private class BundleJsonParser(private val s: String) {
    private var i = 0
    private var depth = 0

    fun atEnd() = i >= s.length

    fun skipSpace() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    fun value(): Any? {
        skipSpace()
        if (atEnd()) throw BundleJsonException("unexpected end")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c in '0'..'9') num() else throw BundleJsonException("unexpected character")
        }
    }

    private fun literal(word: String, v: Any?): Any? {
        if (!s.startsWith(word, i)) throw BundleJsonException("bad literal")
        i += word.length
        return v
    }

    private fun enter() {
        // Python's json hits RecursionError near 1000 levels; refuse far sooner than a stack overflow.
        if (++depth > 200) throw BundleJsonException("nested too deeply")
    }

    private fun obj(): Map<String, Any?> {
        enter()
        i++
        val out = LinkedHashMap<String, Any?>()
        skipSpace()
        if (i < s.length && s[i] == '}') { i++; depth--; return out }
        while (true) {
            skipSpace()
            if (atEnd() || s[i] != '"') throw BundleJsonException("expected key")
            val k = str()
            skipSpace()
            if (atEnd() || s[i] != ':') throw BundleJsonException("expected colon")
            i++
            out[k] = value() // a repeated key: the last wins, as in Python
            skipSpace()
            if (atEnd()) throw BundleJsonException("unexpected end")
            if (s[i] == ',') { i++; continue }
            if (s[i] == '}') { i++; depth--; return out }
            throw BundleJsonException("expected comma or brace")
        }
    }

    private fun arr(): List<Any?> {
        enter()
        i++
        val out = ArrayList<Any?>()
        skipSpace()
        if (i < s.length && s[i] == ']') { i++; depth--; return out }
        while (true) {
            out.add(value())
            skipSpace()
            if (atEnd()) throw BundleJsonException("unexpected end")
            if (s[i] == ',') { i++; continue }
            if (s[i] == ']') { i++; depth--; return out }
            throw BundleJsonException("expected comma or bracket")
        }
    }

    private fun str(): String {
        i++
        val sb = StringBuilder()
        while (true) {
            if (atEnd()) throw BundleJsonException("unterminated string")
            val c = s[i++]
            when {
                c == '"' -> return sb.toString()
                c.code < 0x20 -> throw BundleJsonException("control character in string")
                c == '\\' -> {
                    if (atEnd()) throw BundleJsonException("unterminated escape")
                    when (val e = s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) throw BundleJsonException("bad unicode escape")
                            val hex = s.substring(i, i + 4)
                            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                throw BundleJsonException("bad unicode escape")
                            }
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> throw BundleJsonException("bad escape '$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun num(): Any {
        val start = i
        if (s[i] == '-') i++
        if (atEnd()) throw BundleJsonException("bad number")
        if (s[i] == '0') i++ else if (s[i] in '1'..'9') { while (i < s.length && s[i] in '0'..'9') i++ } else throw BundleJsonException("bad number")
        var isInt = true
        if (i < s.length && s[i] == '.') {
            isInt = false
            i++
            val d = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == d) throw BundleJsonException("bad number")
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            isInt = false
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            val d = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == d) throw BundleJsonException("bad number")
        }
        val text = s.substring(start, i)
        if (isInt) text.toLongOrNull()?.let { return it }
        return text.toDouble()
    }
}

/** Python's `json.dumps(value, indent=2)` (`ensure_ascii=True`) for the shapes above. */
internal fun dumpBundleJson(value: Any?): String = StringBuilder().also { writeValue(it, value, 0) }.toString()

private fun writeValue(sb: StringBuilder, v: Any?, level: Int) {
    when (v) {
        null -> sb.append("null")
        is Boolean -> sb.append(if (v) "true" else "false")
        is Int, is Long, is Short, is Byte -> sb.append(v.toString())
        is Double -> sb.append(if (v == Math.floor(v) && Math.abs(v) < 1e16) "%.1f".format(java.util.Locale.ROOT, v) else v.toString())
        is String -> quoteAscii(sb, v)
        is Map<*, *> -> {
            if (v.isEmpty()) { sb.append("{}"); return }
            sb.append("{\n")
            var first = true
            for ((k, x) in v) {
                if (!first) sb.append(",\n")
                first = false
                indent(sb, level + 1)
                quoteAscii(sb, k as String)
                sb.append(": ")
                writeValue(sb, x, level + 1)
            }
            sb.append('\n')
            indent(sb, level)
            sb.append('}')
        }
        is List<*> -> {
            if (v.isEmpty()) { sb.append("[]"); return }
            sb.append("[\n")
            var first = true
            for (x in v) {
                if (!first) sb.append(",\n")
                first = false
                indent(sb, level + 1)
                writeValue(sb, x, level + 1)
            }
            sb.append('\n')
            indent(sb, level)
            sb.append(']')
        }
        else -> throw BundleJsonException("cannot serialise " + v.javaClass.simpleName)
    }
}

private fun indent(sb: StringBuilder, level: Int) {
    repeat(level * 2) { sb.append(' ') }
}

private fun quoteAscii(sb: StringBuilder, s: String) {
    sb.append('"')
    for (ch in s) {
        when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch == '\b' -> sb.append("\\b")
            ch == '\u000C' -> sb.append("\\f")
            ch.code in 0x20..0x7e -> sb.append(ch)
            else -> sb.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", ch.code))
        }
    }
    sb.append('"')
}
