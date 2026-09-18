package com.chatmailsync.core.mail

/**
 * Kotlin port of the untagged-response parsing helpers in
 * `src/mail_client.py`: `_LIST_RESPONSE_RE`, `_unquote_imap_token`,
 * `_parse_list_response`, `_APPENDUID_RE`, `_extract_appenduid`,
 * `_join_imap_response` (lines ~208-422 there).
 *
 * Test doubles hand these plain [String] lines (already UTF-8 decoded),
 * where the Python original works on `bytes` -- the regex and quoting rules
 * are identical either way since IMAP protocol syntax is ASCII.
 */

private val LIST_RESPONSE_RE =
    Regex("""^\((?<flags>[^)]*)\)\s+(?<delim>NIL|"(?:[^"\\]|\\.)*")\s+(?<name>.+?)\s*$""")

private val APPENDUID_RE = Regex("""APPENDUID\s+(\d+)\s+(\d+)""")

data class ParsedListLine(val delimiter: String?, val name: String)

/** One line of a LIST response: either a plain line, or a (head, literal) pair for literal mailbox names. */
sealed class ImapListRaw {
    data class Line(val text: String) : ImapListRaw()
    data class Literal(val head: String, val literalText: String) : ImapListRaw()
}

/** Mirrors `_unquote_imap_token`: NIL -> null, quoted-string -> unescaped inner text, else the atom verbatim. */
fun unquoteImapToken(token: String): String? {
    if (token == "NIL") return null
    if (token.length >= 2 && token[0] == '"' && token[token.length - 1] == '"') {
        val inner = token.substring(1, token.length - 1)
        return inner.replace("\\\"", "\"").replace("\\\\", "\\")
    }
    return token
}

/**
 * Mirrors `_parse_list_response`: returns null for lines it doesn't
 * recognise (never throws) -- one unparseable LIST line should not crash a
 * whole sync.
 */
fun parseListResponse(raw: ImapListRaw): ParsedListLine? {
    return when (raw) {
        is ImapListRaw.Literal -> {
            val m = LIST_RESPONSE_RE.matchEntire(raw.head) ?: return null
            val delim = unquoteImapToken(m.groups["delim"]!!.value)
            ParsedListLine(delim, raw.literalText)
        }
        is ImapListRaw.Line -> {
            if (raw.text.isEmpty()) return null
            val m = LIST_RESPONSE_RE.matchEntire(raw.text) ?: return null
            val delim = unquoteImapToken(m.groups["delim"]!!.value)
            val name = unquoteImapToken(m.groups["name"]!!.value) ?: ""
            ParsedListLine(delim, name)
        }
    }
}

/** Mirrors `_join_imap_response`: flattens a response's data lines into one string for substring matching. */
fun joinImapResponse(data: List<String?>): String = data.filterNotNull().joinToString(" ")

/** Mirrors `_is_already_exists_response`. */
fun isAlreadyExistsResponse(data: List<String?>): Boolean {
    val text = joinImapResponse(data).uppercase()
    return "ALREADYEXISTS" in text || "ALREADY EXISTS" in text
}

/**
 * Mirrors `_extract_appenduid` (RFC 4315 UIDPLUS): pulls `<uidvalidity>-<uid>`
 * out of an APPEND response's APPENDUID response code, or null if the server
 * doesn't support UIDPLUS -- caller falls back to the message's own
 * Message-ID in that case. Never invents a UID.
 */
fun extractAppendUid(data: List<String?>): String? {
    val m = APPENDUID_RE.find(joinImapResponse(data)) ?: return null
    return "${m.groupValues[1]}-${m.groupValues[2]}"
}
