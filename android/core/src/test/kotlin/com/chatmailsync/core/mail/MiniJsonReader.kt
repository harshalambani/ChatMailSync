package com.chatmailsync.core.mail

/**
 * Minimal recursive-descent JSON reader, test-scope only. `:core` has no
 * JSON library dependency (see `Json.kt`'s KDoc), and `Json.kt` itself is
 * write-only (serialises [JsonValue.Str]/[JsonValue.Num], never parses) --
 * so golden fixtures written by `tools/generate_kotlin_core_golden_fixtures.py`
 * as JSON need a reader from somewhere. This one supports exactly the value
 * shapes the golden fixtures use: objects, arrays, strings (with the
 * standard JSON escapes, including `\uXXXX` and surrogate pairs), numbers
 * (integers only -- every numeric golden field so far is an hour/minute/
 * second, always a non-negative integer), booleans, and `null`.
 */
sealed class JsonNode {
    data class Obj(val fields: Map<String, JsonNode>) : JsonNode()
    data class Arr(val items: List<JsonNode>) : JsonNode()
    data class Str(val value: String) : JsonNode()
    data class Num(val value: Long) : JsonNode()
    data class Bool(val value: Boolean) : JsonNode()
    object Null : JsonNode()

    fun asObj(): Obj = this as Obj
    fun asArr(): Arr = this as Arr
    fun asStringOrNull(): String? = when (this) {
        is Str -> value
        is Null -> null
        else -> error("not a string/null: $this")
    }
    fun asString(): String = (this as Str).value
    fun asInt(): Int = (this as Num).value.toInt()
    fun asBoolean(): Boolean = (this as Bool).value

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
        return when (val c = text[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonNode.Str(parseStringLiteral())
            'n' -> {
                require(text.startsWith("null", pos))
                pos += 4
                JsonNode.Null
            }
            't' -> {
                require(text.startsWith("true", pos))
                pos += 4
                JsonNode.Bool(true)
            }
            'f' -> {
                require(text.startsWith("false", pos))
                pos += 5
                JsonNode.Bool(false)
            }
            '-', in '0'..'9' -> parseNumber()
            else -> error("unexpected character '$c' at offset $pos")
        }
    }

    private fun parseNumber(): JsonNode.Num {
        val start = pos
        if (text[pos] == '-') pos++
        while (pos < text.length && text[pos].isDigit()) pos++
        // Only integers appear in current golden fixtures; a '.'/'e' here
        // would be a genuine surprise worth failing loudly on rather than
        // silently truncating.
        require(pos < text.length && (text[pos] == ',' || text[pos] == '}' || text[pos] == ']' || text[pos].isWhitespace())) {
            "expected integer (no fraction/exponent support) at offset $start"
        }
        return JsonNode.Num(text.substring(start, pos).toLong())
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
                        'f' -> sb.append('\u000C')
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
