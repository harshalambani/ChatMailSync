package com.chatmailsync.core.mail

/**
 * Kotlin port of how Python's `Message.as_bytes()` writes ONE header under
 * the default `compat32` policy: `Compat32._fold` builds
 * `email.header.Header(value, header_name=name)` and calls `encode` with
 * `maxlinelen=78` and a `\n` line separator. That is `Header.encode` plus its
 * `_ValueFormatter` and `_Accumulator` helpers (Python 3.13), ported step for
 * step so the folded bytes are identical:
 *
 *  - a pure-ASCII value is split at the highest-level break that keeps a line
 *    within 78 characters (`;` first, then `,`, then a space, then a tab);
 *  - a value with any non-ASCII character is encoded as RFC 2047 utf-8 words
 *    (see [Rfc2047.encodeLines]: shortest of base64 and quoted-printable, each
 *    word within 75 characters), the words joined by a `\n ` fold;
 *  - a token that cannot be split stays on one over-long line, as in Python.
 *
 * Use [fold] for every top-level header, so the output does not depend on
 * which header a long value lands in.
 *
 * Header injection: Python's folder raises `HeaderParseError` when a value
 * carries a line break followed by something that looks like a header name.
 * This port cannot raise mid-sync, so [fold] first replaces every line
 * break and control character with a space ([lineSafe]). Nothing passed to
 * [fold] can therefore start a new header line.
 */
internal object Compat32Headers {
    /** `Policy.max_line_length` for compat32. */
    const val MAX_LINE_LENGTH = 78

    private const val SPLIT_CHARS = ";, \t"
    private const val CONTINUATION_WS = " "

    /**
     * True for the characters Python's `str.splitlines()` treats as a line
     * boundary, plus every other C0/C1 control except TAB (folding white
     * space), plus DEL. NUL is in here on purpose.
     */
    private fun isLineBreakOrControl(c: Char): Boolean =
        (c.code < 0x20 && c != '\t') || c.code == 0x7F || c.code == 0x85 ||
            c.code == 0x2028 || c.code == 0x2029

    /**
     * Replaces each run of line-break and control characters in [value] with a
     * single space. A normal value (letters, digits, punctuation, spaces, tabs,
     * any non-ASCII text) is returned unchanged, including repeated spaces.
     */
    fun lineSafe(value: String): String {
        if (value.none { isLineBreakOrControl(it) }) return value
        val sb = StringBuilder(value.length)
        var inRun = false
        for (c in value) {
            if (isLineBreakOrControl(c)) {
                if (!inRun) sb.append(' ')
                inRun = true
            } else {
                sb.append(c)
                inRun = false
            }
        }
        return sb.toString()
    }

    /**
     * Returns the header as it is written to the message, WITHOUT the final
     * line break: `Name: value`, possibly several lines joined by `\n`, each
     * continuation line starting with one space.
     */
    fun fold(name: String, value: String): String {
        val clean = lineSafe(value)
        val formatter = ValueFormatter(name.length + 2, MAX_LINE_LENGTH)
        if (clean.all { it.code < 128 }) {
            formatter.feedAscii("", clean)
        } else {
            formatter.feedEncoded("", clean)
        }
        // Header.encode ends with add_transition() whenever it held a chunk.
        formatter.addTransition()
        return name + ": " + formatter.render()
    }

    private fun isPySpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' ||
            (c.code in 0x1C..0x1F)

    /** `_Accumulator`: the parts of the line being built, plus the header-name width counted as already used. */
    private class Accumulator(var initialSize: Int) {
        val parts = ArrayList<Pair<String, String>>()

        val partCount: Int get() = parts.size

        val length: Int get() = initialSize + parts.sumOf { it.first.length + it.second.length }

        fun push(fws: String, part: String) {
            parts.add(Pair(fws, part))
        }

        fun pop(): Pair<String, String> =
            if (parts.isEmpty()) Pair("", "") else parts.removeAt(parts.size - 1)

        fun popFrom(index: Int): List<Pair<String, String>> {
            val popped = ArrayList(parts.subList(index, parts.size))
            while (parts.size > index) parts.removeAt(parts.size - 1)
            return popped
        }

        fun reset(start: List<Pair<String, String>>? = null) {
            parts.clear()
            if (start != null) parts.addAll(start)
            initialSize = 0
        }

        fun isOnlyWhitespace(): Boolean {
            if (initialSize != 0) return false
            if (parts.isEmpty()) return true
            val s = render()
            return s.isNotEmpty() && s.all { isPySpace(it) }
        }

        fun render(): String = parts.joinToString("") { it.first + it.second }
    }

    /** `_ValueFormatter`. */
    private class ValueFormatter(headerLength: Int, private val maxLength: Int) {
        private val lines = ArrayList<String>()
        private val current = Accumulator(headerLength)

        fun render(): String {
            newline()
            return lines.joinToString("\n")
        }

        fun newline() {
            val endOfLine = current.pop()
            if (endOfLine != Pair(" ", "")) current.push(endOfLine.first, endOfLine.second)
            if (current.length > 0) {
                if (current.isOnlyWhitespace() && lines.isNotEmpty()) {
                    lines[lines.size - 1] = lines[lines.size - 1] + current.render()
                } else {
                    lines.add(current.render())
                }
            }
            current.reset()
        }

        fun addTransition() {
            current.push(" ", "")
        }

        /** `feed` for a charset with no header encoding (us-ascii). */
        fun feedAscii(fws: String, string: String) {
            // re.split("([ \t]+)", fws + string): text, white space, text, ...
            val text = fws + string
            val parts = ArrayList<String>()
            val token = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == ' ' || c == '\t') {
                    parts.add(token.toString())
                    token.setLength(0)
                    var j = i
                    while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
                    parts.add(text.substring(i, j))
                    i = j
                } else {
                    token.append(c)
                    i++
                }
            }
            parts.add(token.toString())
            if (parts[0].isNotEmpty()) parts.add(0, "") else parts.removeAt(0)
            var k = 0
            while (k + 1 < parts.size) {
                appendChunk(parts[k], parts[k + 1])
                k += 2
            }
        }

        /** `feed` for the utf-8 charset (header encoding SHORTEST). */
        fun feedEncoded(fws: String, string: String) {
            var calls = 0
            val encoded = ArrayList(
                Rfc2047.encodeLines(string) {
                    // First line: what is left of this one. Later lines: one
                    // column goes to the continuation white space.
                    if (calls++ == 0) maxLength - current.length else maxLength - CONTINUATION_WS.length
                },
            )
            if (encoded.isEmpty()) return
            val first = encoded.removeAt(0)
            if (first != null) appendChunk(fws, first)
            if (encoded.isEmpty()) return
            val last = encoded.removeAt(encoded.size - 1)
            newline()
            current.push(CONTINUATION_WS, last ?: "")
            for (line in encoded) lines.add(CONTINUATION_WS + (line ?: ""))
        }

        private fun appendChunk(fws: String, string: String) {
            current.push(fws, string)
            if (current.length <= maxLength) return
            // Find the best split point, working backward from the end.
            var split = -1
            search@ for (ch in SPLIT_CHARS) {
                var i = current.partCount - 1
                while (i > 0) {
                    if (ch == ' ' || ch == '\t') {
                        val before = current.parts[i].first
                        if (before.isNotEmpty() && before[0] == ch) {
                            split = i
                            break@search
                        }
                    }
                    val previous = current.parts[i - 1].second
                    if (previous.isNotEmpty() && previous[previous.length - 1] == ch) {
                        split = i
                        break@search
                    }
                    i--
                }
            }
            if (split < 0) {
                // No break point. Keep the token whole; if a header name
                // precedes it on this line, move it to a line of its own.
                val (popped, popPart) = current.pop()
                var popFws = popped
                if (current.initialSize > 0) {
                    newline()
                    if (popFws.isEmpty()) popFws = " "
                }
                current.push(popFws, popPart)
                return
            }
            val remainder = current.popFrom(split)
            lines.add(current.render())
            current.reset(remainder)
        }
    }
}
