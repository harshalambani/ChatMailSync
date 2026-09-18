package com.chatmailsync.core.mail

/**
 * Minimal recursive-descent JSON reader, test-scope only. `:core` has no
 * JSON library dependency (see `Json.kt`'s KDoc), and `Json.kt` itself is
 * write-only (serialises [JsonValue.Str]/[JsonValue.Num], never parses) --
 * so golden fixtures written by `tools/generate_kotlin_core_golden_fixtures.py`
 * as JSON need a reader from somewhere. This one supports exactly the value
 * shapes the golden fixtures use: objects, arrays, strings (with the
 * standard JSON escapes, including `\uXXXX` and surrogate pairs), and
 * `null`. No numbers or booleans are needed by any current golden fixture,
 * so they are deliberately not implemented.
 */
sealed class JsonNode {
    data class Obj(val fields: Map<String, JsonNode>) : JsonNode()
    data class Arr(val items: List<JsonNode>) : JsonNode()
    data class Str(val value: String) : JsonNode()
    object Null : JsonNode()

    fun asObj(): Obj = this as Obj
    fun asArr(): Arr = this as Arr
    fun asStringOrNull(): String? = when (this) {
        is Str -> value
        is Null -> null
        else -> error("not a string/null: $this")
    }
    fun asString(): String = (this as Str).value

    operator fun get(key: String): JsonNode =
        asObj().fields[key] ?: error("missing key '$key' in $this")
}

fun parseJson(text: String): JsonNode {
    val parser = MiniJsonParser(text)
    val node = parser.parseValue()
    parser.skipWhitespace()
    require(parser.atEnd()) { "trailing content after JSON value at offset ${parser.pos}" }
    return node
}

private class MiniJsonParser(private val text: String) {
    var pos = 0

    fun atEnd(): Boolean = pos >= text.length

    fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    fun parseValue(): JsonNode {
        skipWhitespace()
        return when (text[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonNode.Str(parseStringLiteral())
            'n' -> {
                require(text.startsWith("null", pos))
                pos += 4
                JsonNode.Null
            }
            else -> error("unexpected character '${text[pos]}' at offset $pos")
        }
    }

    private fun parseObject(): JsonNode.Obj {
        expect('{')
        val fields = linkedMapOf<String, JsonNode>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return JsonNode.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            val key = parseStringLiteral()
            skipWhitespace()
            expect(':')
            val value = parseValue()
            fields[key] = value
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    pos++
                }
                '}' -> {
                    pos++
                    return JsonNode.Obj(fields)
                }
                else -> error("expected ',' or '}' at offset $pos")
            }
        }
    }

    private fun parseArray(): JsonNode.Arr {
        expect('[')
        val items = mutableListOf<JsonNode>()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return JsonNode.Arr(items)
        }
        while (true) {
            items.add(parseValue())
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    pos++
                }
                ']' -> {
                    pos++
                    return JsonNode.Arr(items)
                }
                else -> error("expected ',' or ']' at offset $pos")
            }
        }
    }

    private fun parseStringLiteral(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = text[pos]
            when (c) {
                '"' -> {
                    pos++
                    return sb.toString()
                }
                '\\' -> {
                    pos++
                    when (val esc = text[pos]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'u' -> {
                            val hex = text.substring(pos + 1, pos + 5)
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                        else -> error("unknown escape '\\$esc' at offset $pos")
                    }
                    pos++
                }
                else -> {
                    sb.append(c)
                    pos++
                }
            }
        }
    }

    private fun peek(): Char {
        skipWhitespace()
        return text[pos]
    }

    private fun expect(c: Char) {
        skipWhitespace()
        require(text[pos] == c) { "expected '$c' at offset $pos, found '${text[pos]}'" }
        pos++
    }
}
