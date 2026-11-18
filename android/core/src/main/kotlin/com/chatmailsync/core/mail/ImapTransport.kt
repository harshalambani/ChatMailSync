package com.chatmailsync.core.mail

import java.io.IOException
import java.net.SocketTimeoutException

/** BUG-26: the most times one email bytes are uploaded in one run, however many retry loops there are. */
const val MAX_UPLOADS_PER_EMAIL = 2

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
 * `get_or_create_label`/`push_chunks`/`push_chat` (the callers) live in
 * `MailTransport.kt` and `PushChunks.kt`.
 */
class ImapTransport(
    private val host: String,
    private val port: Int,
    private val email: String,
    private val password: String,
    private val connectionFactory: (() -> ImapConnection)? = null,
    private val setSeen: Boolean = true,
    private val timeoutSeconds: Long = MAIL_SOCKET_TIMEOUT_SECONDS,
    /**
     * Receives one outcome-only line each time the F-02 Message-ID lookup runs: "F-02 lookup: found",
     * "F-02 lookup: not found" or "F-02 lookup: failed (<exception class>)". Never the id, the folder,
     * the address or any server text. No-op by default (core is plain JVM; the app wires Android Log).
     */
    private val diagLog: (String) -> Unit = {},
) : MailTransport {
    @Volatile
    private var conn: ImapConnection? = null

    /** BUG-26: set by [abort] from the stopping thread; read by the thread that is uploading. */
    @Volatile
    private var aborted = false

    /** BUG-26: how many times each email's bytes went on the wire in this run (key: its Message-ID). */
    private val uploads = HashMap<String, Int>()
    private var delimiter: String? = null

    /**
     * F-02: Message-IDs whose APPEND ended in an abort / I/O error / timeout, so nobody knows whether
     * the server stored them. Such an id is looked up in the target folder before it is ever sent
     * again -- including on a later [messagesInsert] call, after a failed lookup.
     */
    private val unconfirmed = HashSet<String>()

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
    override val maxMessageBytes: Long
        get() {
            // `if advertised:` -- a zero limit is falsy in Python and falls through (PAR-06).
            appendLimit()?.takeIf { it > 0 }?.let { return it }
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
            // Python's int() has no upper bound; a value beyond Long clamps instead of overflowing (PAR-06).
            if (value.isNotEmpty() && value.all { it.isDigit() }) return value.toLongOrNull() ?: Long.MAX_VALUE
        }
        return null
    }

    /** BUG-26: Stop was pressed. Cuts the live connection so a blocked upload ends at once. */
    override fun abort() {
        aborted = true
        conn?.abort()
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

    private fun mailboxToWire(name: String): String {
        ImapUtf7.requireNoControl(name) // SEC-02: before encoding, which would otherwise hide a control character
        return ImapUtf7.quoteMailbox(ImapUtf7.encode(toWire(name)))
    }
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
        val text = stripSecret(joinImapResponse(data), password)
        return transportError("$context failed ($status): $text", statusForImapText(text))
    }

    data class Label(val name: String, val id: String)

    override fun labelsList(): List<Label> {
        val result = try {
            call { it.list("\"\"", "*") }
        } catch (exc: Exception) {
            throw mapException(exc, "LIST")
        }
        if (result.status != "OK") throw mapResponse(result.status, result.data, "LIST")

        val labels = mutableListOf<Label>()
        for ((index, raw) in result.data.withIndex()) {
            if (raw == null) continue
            // PAR-04: a mailbox name sent as a literal arrives as head + literal.
            val literal = result.literals[index]
            val parsed = parseListResponse(
                if (literal != null) ImapListRaw.Literal(raw, literal) else ImapListRaw.Line(raw),
            ) ?: continue
            if (parsed.delimiter != null) delimiter = parsed.delimiter
            val canonical = mailboxFromWire(parsed.name)
            labels.add(Label(canonical, canonical))
        }
        return labels
    }

    /** Mirrors `owns_label_id`. */
    override fun ownsLabelId(labelId: String, displayName: String): Boolean = labelId == fullLabelName(displayName)

    /** Mirrors `labels_create`; returns the label id (== [name]). */
    override fun labelsCreate(name: String): String {
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

    /**
     * BUG-05 / D29: renames the folder [oldName] to [newName] and returns the new id. Any refusal
     * (the target exists, the server does not allow it) throws [MailTransportError] and leaves
     * the old folder exactly as it was.
     */
    override fun labelsRename(oldName: String, newName: String): String {
        val oldWire = mailboxToWire(oldName)
        val newWire = mailboxToWire(newName)
        val result = try {
            call { it.rename(oldWire, newWire) }
        } catch (exc: Exception) {
            throw mapException(exc, "RENAME")
        }
        if (result.status != "OK") throw mapResponse(result.status, result.data, "RENAME")
        try {
            call { it.subscribe(newWire) }
        } catch (_: Exception) {
            // Subscription is best-effort, as for CREATE.
        }
        return newName
    }

    data class InsertResult(val id: String, val threadId: String)

    /** Mirrors `messages_insert`. [folder] is the canonical '/'-delimited label/folder name. */
    override fun markPossiblySent(messageId: String) {
        unconfirmed.add(messageId)
    }

    override fun lookupStored(messageId: String, folder: String): Boolean {
        if (!isSafeMessageId(messageId)) return false
        return findByMessageId(mailboxToWire(folder), messageId) != null
    }

    override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): InsertResult {
        val crlfBytes = normalizeCrlf(rawMessageBytes)
        val wireFolder = mailboxToWire(folder)

        val headers = MessageHeaders.parse(crlfBytes)
        val internalDate = internaldateFromHeaders(headers)
        val headerId = headers["Message-ID"]?.takeIf { it.isNotEmpty() }
        val messageId = headerId ?: MimeBuilder.newMessageId()
        // F-02: the id is sent in a SEARCH after an unknown outcome, so it must be safe to send.
        if (headerId != null && !isSafeMessageId(headerId)) {
            throw MailTransportError("Refused: the message's Message-ID is not safe to send, so nothing was sent.", 400)
        }
        val resultThread = threadId?.takeIf { it.isNotEmpty() } ?: messageId

        val flags = if (setSeen) "(\\Seen)" else null

        val uploadKey = headerId ?: ("bytes:" + java.util.Arrays.hashCode(crlfBytes))
        while (true) {
            if (aborted) throw StopRequestedException()
            if (headerId != null && headerId in unconfirmed) {
                // Throws a retryable error if the lookup itself fails; the id then stays unconfirmed.
                val found = findByMessageId(wireFolder, headerId)
                unconfirmed.remove(headerId)
                if (found != null) return InsertResult(id = found, threadId = resultThread)
            }
            // BUG-26: at most MAX_UPLOADS_PER_EMAIL uploads of one email bytes per run, counted across this
            // loop and insertWithBackoff. The lookup above is not an upload and is not counted.
            if ((uploads[uploadKey] ?: 0) >= MAX_UPLOADS_PER_EMAIL) throw uploadCapError()
            val connection = try {
                getConn()
            } catch (exc: Exception) {
                if (aborted) throw StopRequestedException()
                throw mapException(exc, "APPEND") // connecting sent no bytes; insertWithBackoff may retry it
            }
            val count = (uploads[uploadKey] ?: 0) + 1
            uploads[uploadKey] = count
            val startedNs = System.nanoTime()
            val result = try {
                // Never `call`: a blind reconnect-and-resend of an APPEND is exactly the F-02 bug.
                connection.append(wireFolder, flags, internalDate, crlfBytes)
            } catch (exc: Exception) {
                logAppend(crlfBytes.size, startedNs, exc.javaClass.simpleName, null, wireFolder, folder, headerId)
                if (aborted) {
                    // Stop cut the upload off: nobody knows whether the server kept it.
                    conn = null
                    delimiter = null
                    if (headerId != null) unconfirmed.add(headerId)
                    throw StopRequestedException()
                }
                if (!outcomeUnknown(exc)) throw capIfRetryable(mapException(exc, "APPEND"), count)
                conn = null
                delimiter = null
                if (headerId == null) {
                    throw MailTransportError(
                        "APPEND: the connection failed and the message has no Message-ID to check for, " +
                            "so it was not sent again.",
                        400,
                    )
                }
                unconfirmed.add(headerId)
                if (count >= MAX_UPLOADS_PER_EMAIL) throw uploadCapError()
                continue
            }
            logAppend(crlfBytes.size, startedNs, result.status, result.data, wireFolder, folder, headerId)
            if (headerId != null) unconfirmed.remove(headerId)
            if (result.status != "OK") throw capIfRetryable(mapResponse(result.status, result.data, "APPEND"), count)
            val uid = extractAppendUid(result.data)
            return InsertResult(id = uid ?: messageId, threadId = resultThread)
        }
    }

    /**
     * D180 diagnostics: one line per APPEND with the size, the seconds taken, the outcome (OK / NO / BAD /
     * an exception class) and the server's tagged reply text, scrubbed. The text has the password, the
     * address, the user, the folder and the Message-ID removed, anything address-shaped removed, and is
     * cut to about 200 characters. Never the message bytes.
     */
    private fun logAppend(
        bytes: Int, startedNs: Long, outcome: String, data: List<String?>?, wireFolder: String, folder: String, messageId: String?,
    ) {
        val seconds = (System.nanoTime() - startedNs) / 100_000_000L / 10.0
        var text = data?.filterNotNull()?.joinToString(" ").orEmpty()
        text = stripSecret(text, password)
        for (secret in listOf(email, email.substringBefore('@'), folder, wireFolder, folderLeaf(folder), folderLeaf(wireFolder), messageId.orEmpty())) {
            if (secret.length >= 3) text = text.replace(secret, "***", ignoreCase = true)
        }
        text = text.replace(Regex("\\S+@\\S+"), "***").replace(Regex("[\\u0000-\\u001f]"), " ").trim()
        if (text.length > 200) text = text.substring(0, 200)
        diagLog("APPEND: " + bytes + " bytes, " + seconds + " s, " + outcome + (if (text.isEmpty()) "" else ": $text"))
    }

    private fun folderLeaf(wireFolder: String): String = wireFolder.substringAfterLast('/').substringAfterLast('.')

    private fun uploadCapError() = MailTransportError(
        "Uploading this email kept failing, so it was not sent again. Nothing more was sent for this chat.",
        400,
    )

    /** BUG-26: a failure that backoff would retry becomes final once the email has used its uploads. */
    private fun capIfRetryable(exc: MailTransportError, uploadsSoFar: Int): MailTransportError =
        if (exc.status in RETRYABLE_STATUSES && uploadsSoFar >= MAX_UPLOADS_PER_EMAIL) uploadCapError() else exc

    /** An abort, I/O error or timeout: the server may or may not have stored the message. */
    private fun outcomeUnknown(exc: Throwable): Boolean =
        exc is ImapAbortError || exc is SocketTimeoutException || exc is IOException

    /**
     * F-02: asks the server whether [messageId] is already in [wireFolder] (EXAMINE + UID SEARCH, both
     * read-only). Returns its UID, or null when it is definitely not there. Any failure of the lookup
     * itself is a retryable 503 -- "cannot tell" is never reported as "not found".
     */
    private fun findByMessageId(wireFolder: String, messageId: String): String? {
        try {
            val examined = call { it.examine(wireFolder) }
            if (examined.status != "OK") throw MailTransportError("lookup: EXAMINE refused", 503)
            val found = call { it.uidSearchMessageId(messageId) }
            if (found.status != "OK") throw MailTransportError("lookup: SEARCH refused", 503)
            val uids = found.data.filterNotNull()
                .flatMap { it.trim().split(' ') }
                .filter { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
            val uid = uids.lastOrNull()
            diagLog(if (uid != null) "F-02 lookup: found" else "F-02 lookup: not found")
            return uid
        } catch (exc: MailTransportError) {
            if (aborted) throw StopRequestedException()
            diagLog("F-02 lookup: failed (MailTransportError)")
            throw exc
        } catch (exc: Exception) {
            if (aborted) throw StopRequestedException()
            diagLog("F-02 lookup: failed (" + exc.javaClass.simpleName + ")")
            val text = stripSecret(exc.message ?: exc.toString(), password)
            throw MailTransportError("lookup after an uncertain APPEND failed: $text", 503)
        }
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

/** F-02: a Message-ID that may be put inside a quoted SEARCH argument: printable ASCII, no quote, no backslash. */
internal fun isSafeMessageId(id: String): Boolean =
    id.isNotEmpty() && id.all { it.code in 0x21..0x7E && it != '"' && it != '\\' }
