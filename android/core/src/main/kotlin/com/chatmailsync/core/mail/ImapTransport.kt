package com.chatmailsync.core.mail

import java.io.IOException
import java.net.SocketTimeoutException

/** Mirrors `_sanitise_label_name`: strips characters the label scheme doesn't allow. */
fun sanitiseLabelName(name: String): String {
    var n = name.trim()
    n = n.replace("/", "-")
    val maxChild = LABEL_MAX_LENGTH - LABEL_PARENT.length - 1
    if (n.length > maxChild) {
        n = n.substring(0, maxChild).trimEnd()
    }
    return n
}

/** Mirrors `_full_label_name`. */
fun fullLabelName(displayName: String): String = "$LABEL_PARENT/${sanitiseLabelName(displayName)}"

/**
 * Kotlin port of `ImapTransport` (`src/mail_client.py` lines ~478-846): the
 * IMAP APPEND mail backend. A "label" maps to an IMAP folder; folder names
 * stay canonical ('/'-delimited) at this boundary and are translated to the
 * server's real hierarchy delimiter only at the two points that touch the
 * wire (CREATE/SUBSCRIBE and APPEND) -- see `mailboxToWire`/`mailboxFromWire`.
 *
 * `get_or_create_label`/`push_chunks`/`push_chat` (the callers) are out of
 * scope for this phase -- see the PR body.
 */
class ImapTransport(
    private val host: String,
    private val port: Int,
    private val email: String,
    private val password: String,
    private val connectionFactory: (() -> ImapConnection)? = null,
    private val setSeen: Boolean = true,
    private val timeoutSeconds: Long = MAIL_SOCKET_TIMEOUT_SECONDS,
) {
    private var conn: ImapConnection? = null
    private var delimiter: String? = null

    private fun defaultConnectionFactory(): ImapConnection =
        JvmImapConnection.connect(host, port, email, password, timeoutSeconds)

    private fun getConn(): ImapConnection {
        var c = conn
        if (c == null) {
            c = (connectionFactory ?: ::defaultConnectionFactory)()
            conn = c
        }
        return c
    }

    /**
     * Opens (or reuses) the connection and returns it -- the Kotlin
     * equivalent of the staged connection test calling `transport._get_conn`
     * directly (same package, deliberate, see `check_connection`'s LOGIN
     * stage). Not part of the [MailIndex]/transport surface other callers use.
     */
    internal fun probeConnect(): ImapConnection = getConn()

    /**
     * Mirrors `max_message_bytes`: APPENDLIMIT from the live connection, then
     * [PROVIDER_MAX_MESSAGE_BYTES] by hostname, then [DEFAULT_MAX_MESSAGE_BYTES].
     */
    val maxMessageBytes: Long
        get() {
            appendLimit()?.let { return it }
            val h = host.lowercase()
            for ((key, preset) in IMAP_PROVIDERS) {
                if (preset.host != null && preset.host.lowercase() == h) {
                    PROVIDER_MAX_MESSAGE_BYTES[key]?.let { return it }
                }
            }
            return DEFAULT_MAX_MESSAGE_BYTES
        }

    /** Mirrors `_appendlimit`: never opens a connection just to ask -- returns null if not yet connected. */
    private fun appendLimit(): Long? {
        val c = conn ?: return null
        for (cap in c.capabilities) {
            if (!cap.uppercase().startsWith("APPENDLIMIT")) continue
            val value = cap.substringAfter("=", "").trim()
            if (value.isNotEmpty() && value.all { it.isDigit() }) return value.toLong()
        }
        return null
    }

    fun close() {
        conn?.let {
            try {
                it.logout()
            } catch (_: Exception) {
                // best-effort logout only
            }
        }
        conn = null
        delimiter = null
    }

    /** Mirrors `_call`: reconnects once, transparently, on an aborted connection. */
    private fun <T> call(action: (ImapConnection) -> T): T {
        val c = getConn()
        return try {
            action(c)
        } catch (exc: ImapAbortError) {
            conn = null
            delimiter = null
            action(getConn())
        }
    }

    private fun toWire(name: String): String {
        val d = delimiter
        if (d.isNullOrEmpty() || d == "/") return name
        return name.replace("/", d)
    }

    private fun fromWire(name: String): String {
        val d = delimiter
        if (d.isNullOrEmpty() || d == "/") return name
        return name.replace(d, "/")
    }

    private fun mailboxToWire(name: String): String = ImapUtf7.quoteMailbox(ImapUtf7.encode(toWire(name)))
    private fun mailboxFromWire(wireName: String): String = fromWire(ImapUtf7.decode(wireName))

    private fun mapException(exc: Throwable, context: String): MailTransportError {
        if (exc is MailTransportError) return exc
        val text = stripSecret(exc.message ?: exc.toString(), password)
        return when (exc) {
            is ImapAbortError -> MailTransportError("$context: IMAP connection aborted: $text", 503)
            is SocketTimeoutException, is IOException -> MailTransportError("$context: network error: $text", 503)
            is ImapCommandError -> transportError("$context: $text", statusForImapText(text))
            else -> MailTransportError("$context: $text", 400)
        }
    }

    private fun mapResponse(status: String, data: List<String?>, context: String): MailTransportError {
        val text = joinImapResponse(data)
        return transportError("$context failed ($status): $text", statusForImapText(text))
    }

    data class Label(val name: String, val id: String)

    fun labelsList(): List<Label> {
        val result = try {
            call { it.list("\"\"", "*") }
        } catch (exc: Exception) {
            throw mapException(exc, "LIST")
        }
        if (result.status != "OK") throw mapResponse(result.status, result.data, "LIST")

        val labels = mutableListOf<Label>()
        for (raw in result.data) {
            if (raw == null) continue
            val parsed = parseListResponse(ImapListRaw.Line(raw)) ?: continue
            if (parsed.delimiter != null) delimiter = parsed.delimiter
            val canonical = mailboxFromWire(parsed.name)
            labels.add(Label(canonical, canonical))
        }
        return labels
    }

    /** Mirrors `owns_label_id`. */
    fun ownsLabelId(labelId: String, displayName: String): Boolean = labelId == fullLabelName(displayName)

    /** Mirrors `labels_create`; returns the label id (== [name]). */
    fun labelsCreate(name: String): String {
        val wireArg = mailboxToWire(name)
        val result = try {
            call { it.create(wireArg) }
        } catch (exc: Exception) {
            throw mapException(exc, "CREATE")
        }
        if (result.status != "OK" && !isAlreadyExistsResponse(result.data)) {
            throw mapResponse(result.status, result.data, "CREATE")
        }
        try {
            call { it.subscribe(wireArg) }
        } catch (_: Exception) {
            // Subscription is best-effort -- see the Python docstring.
        }
        return name
    }

    data class InsertResult(val id: String, val threadId: String)

    /** Mirrors `messages_insert`. [folder] is the canonical '/'-delimited label/folder name. */
    fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String? = null): InsertResult {
        val crlfBytes = normalizeCrlf(rawMessageBytes)
        val wireFolder = mailboxToWire(folder)

        val headers = MessageHeaders.parse(crlfBytes)
        val internalDate = internaldateFromHeaders(headers)
        val messageId = headers["Message-ID"] ?: MimeBuilder.newMessageId()

        val flags = if (setSeen) "(\\Seen)" else null

        val result = try {
            call { it.append(wireFolder, flags, internalDate, crlfBytes) }
        } catch (exc: Exception) {
            throw mapException(exc, "APPEND")
        }
        if (result.status != "OK") throw mapResponse(result.status, result.data, "APPEND")

        val uid = extractAppendUid(result.data)
        return InsertResult(id = uid ?: messageId, threadId = threadId ?: messageId)
    }
}

/**
 * Mirrors `re.sub(rb"\r?\n", b"\r\n", raw_bytes)`: normalizes every bare "\n"
 * (and every existing "\r\n", which is a no-op) to "\r\n" -- RFC 3501
 * literals are CRLF-terminated. A lone "\r" not followed by "\n" is left
 * untouched, exactly like the Python regex.
 */
fun normalizeCrlf(data: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream(data.size + 16)
    val cr = '\r'.code.toByte()
    val lf = '\n'.code.toByte()
    for (i in data.indices) {
        val b = data[i]
        if (b == lf) {
            if (i == 0 || data[i - 1] != cr) out.write(cr.toInt())
            out.write(lf.toInt())
        } else {
            out.write(b.toInt())
        }
    }
    return out.toByteArray()
}
