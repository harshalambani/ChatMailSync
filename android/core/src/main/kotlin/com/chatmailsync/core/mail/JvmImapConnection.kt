package com.chatmailsync.core.mail

import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket

/**
 * Production [ImapConnection]: a small hand-written IMAP4 client over
 * `javax.net.ssl`, since `:core` deliberately carries no dependency beyond
 * the JDK (see the PR body). It implements exactly the commands
 * [ImapTransport] issues -- LOGIN, LIST, CREATE, SUBSCRIBE, APPEND, LOGOUT --
 * not a general-purpose IMAP library.
 *
 * TLS is pinned the same way `imap_tls_context()` pins it on the Python
 * side: TLS 1.2 floor, and hostname verification turned on explicitly
 * (`SSLParameters.endpointIdentificationAlgorithm = "HTTPS"`) -- a bare
 * `SSLSocket` does *not* verify the hostname on its own, only the
 * certificate chain, so skipping this would silently accept a valid cert for
 * the wrong host.
 *
 * Exercised only by [LiveImapHarness] (a human, by hand, against a real
 * mailbox) and never by the JUnit suite, which talks to a fake
 * [ImapConnection] instead -- see the PR body for why no live-network test
 * runs in CI.
 */
class JvmImapConnection private constructor(
    private val socket: Socket,
    private val input: BufferedInputStream,
    private val output: OutputStream,
    override val capabilities: List<String>,
) : ImapConnection {

    private val reader = ImapLineReader(input)

    private var tagCounter = 0

    private fun nextTag(): String {
        tagCounter += 1
        return String.format(Locale.ROOT, "A%04d", tagCounter)
    }

    private fun writeLine(line: String) {
        try {
            output.write(line.toByteArray(Charsets.UTF_8))
            output.write(CRLF)
            output.flush()
        } catch (exc: IOException) {
            throw ImapAbortError("connection closed while sending: ${exc.message}")
        }
    }

    private fun readLine(): String = reader.readLine()

    /** Runs one tagged command, collecting untagged response lines, and returns the final status. */
    private fun runCommand(commandLine: String, literal: ByteArray? = null): ImapResult {
        val tag = nextTag()
        writeLine("$tag $commandLine")
        val data = mutableListOf<String?>()
        var lines = 0
        while (true) {
            val line = readLine()
            if (++lines > MAX_UNTAGGED_LINES) throw tooManyLines()
            if (line.startsWith("+")) {
                if (literal != null) {
                    try {
                        output.write(literal)
                        output.write(CRLF)
                        output.flush()
                    } catch (exc: IOException) {
                        throw ImapAbortError("connection closed while sending literal: ${exc.message}")
                    }
                }
                continue
            }
            if (line.startsWith("$tag ")) {
                val rest = line.removePrefix("$tag ")
                val status = rest.substringBefore(' ')
                val text = rest.substringAfter(' ', "")
                if (status != "OK" && status != "NO" && status != "BAD") {
                    throw ImapCommandError("unexpected tagged response: $line")
                }
                // Like imaplib: BAD is the only status that raises. A NO is
                // returned as data -- the untagged lines plus the tagged text
                // (where servers put e.g. "[ALREADYEXISTS] Mailbox exists") --
                // so ImapTransport can act on it (labelsCreate treats
                // already-exists as success; a real NO still fails there).
                if (status == "BAD") throw ImapCommandError(text.ifEmpty { rest })
                if (status == "NO") {
                    data.add(text.ifEmpty { rest })
                    return ImapResult(status, data)
                }
                return ImapResult(status, data)
            }
            if (line.startsWith("*")) {
                data.add(line.removePrefix("*").trim())
                continue
            }
            // Anything else (blank line, garbage) is ignored rather than
            // treated as fatal -- servers occasionally send stray whitespace.
        }
    }

    override fun list(reference: String, pattern: String): ImapResult = runCommand("LIST $reference $pattern")

    override fun create(mailboxWireArg: String): ImapResult = runCommand("CREATE $mailboxWireArg")

    override fun subscribe(mailboxWireArg: String): ImapResult = runCommand("SUBSCRIBE $mailboxWireArg")

    override fun append(
        mailboxWireArg: String,
        flags: String?,
        internalDate: OffsetDateTime?,
        message: ByteArray,
    ): ImapResult {
        val parts = mutableListOf("APPEND", mailboxWireArg)
        if (flags != null) parts.add(flags)
        if (internalDate != null) parts.add(ImapUtf7.quoteMailbox(internalDate.format(INTERNALDATE_FORMAT)))
        parts.add("{${message.size}}")
        return runCommand(parts.joinToString(" "), literal = message)
    }

    override fun logout() {
        try {
            runCommand("LOGOUT")
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
                // best-effort close
            }
        }
    }

    /** Creates the connected socket the IMAP conversation runs over. The only thing tests may replace. */
    internal fun interface SocketOpener {
        fun open(host: String, port: Int, timeoutMillis: Int): Socket
    }

    companion object {
        /** Production opener: TCP connect, then a verified TLS handshake. */
        internal val TLS_SOCKET_OPENER = SocketOpener { host, port, timeoutMillis ->
            val context = SSLContext.getInstance("TLSv1.2")
            context.init(null, null, null)
            val factory = context.socketFactory
            val plain = Socket()
            plain.connect(InetSocketAddress(host, port), timeoutMillis)
            plain.soTimeout = timeoutMillis
            val tls = factory.createSocket(plain, host, port, true) as SSLSocket
            val params = SSLParameters()
            params.endpointIdentificationAlgorithm = "HTTPS"
            tls.sslParameters = params
            tls.enabledProtocols = tls.supportedProtocols.filter {
                it == "TLSv1.2" || it == "TLSv1.3"
            }.toTypedArray()
            tls.startHandshake()
            tls
        }

        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
        private val INTERNALDATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss Z", Locale.ENGLISH)

        /**
         * Connects, verifies TLS, and logs in -- mirroring
         * `ImapTransport._default_connection_factory`. Throws
         * [MailTransportError] (status 503 for a connect/TLS failure, 401 for
         * a login failure, with [loginFailureHint] folded into the message)
         * rather than a raw exception, exactly like the Python original.
         */
        fun connect(host: String, port: Int, email: String, password: String, timeoutSeconds: Long): JvmImapConnection =
            connectVia(TLS_SOCKET_OPENER, host, port, email, password, timeoutSeconds)

        /**
         * Test seam (see the Phase A brief): [opener] may only replace socket
         * creation. Everything after the socket exists -- greeting,
         * CAPABILITY, LOGIN, every read, parse, tag and status line -- is the
         * same production code. Production always passes [TLS_SOCKET_OPENER].
         */
        internal fun connectVia(
            opener: SocketOpener,
            host: String,
            port: Int,
            email: String,
            password: String,
            timeoutSeconds: Long,
        ): JvmImapConnection {
            // SEC-02: refuse a bad credential before any socket exists, so nothing can be sent.
            ImapUtf7.requireLoginSafe(email, "email address")
            ImapUtf7.requireLoginSafe(password, "app password")

            val socket: Socket
            try {
                socket = opener.open(host, port, (timeoutSeconds * 1000).toInt())
            } catch (exc: Exception) {
                throw MailTransportError(
                    "Could not connect to $host:$port: ${stripSecret(exc.message ?: exc.toString(), password)}",
                    503,
                )
            }

            // PAR-03 / SEC-04: everything from the greeting to the end of LOGIN
            // is wrapped, like the TLS block above it. A dropped connection, a
            // timeout or a refusing server ends as a MailTransportError (503),
            // never a raw IOException, and the socket is closed on any failure.
            try {
                return handshake(socket, host, port, email, password)
            } catch (exc: MailTransportError) {
                closeQuietly(socket)
                throw exc
            } catch (exc: Exception) {
                closeQuietly(socket)
                throw MailTransportError(
                    "Could not talk to $host:$port: ${stripSecret(exc.message ?: exc.toString(), password)}",
                    503,
                )
            }
        }

        private fun closeQuietly(socket: Socket) {
            try {
                socket.close()
            } catch (_: Exception) {
                // best effort
            }
        }

        private val CAPABILITY_IN_BRACKETS = Regex("\\[CAPABILITY ([^]]+)]")

        private fun handshake(socket: Socket, host: String, port: Int, email: String, password: String): JvmImapConnection {
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val reader = ImapLineReader(input)

            val greeting = reader.readLine()
            val greetingWord = greeting.removePrefix("*").trim().substringBefore(' ').uppercase(Locale.ROOT)
            if (!greeting.startsWith("*") || greetingWord !in setOf("OK", "PREAUTH", "BYE")) {
                throw MailTransportError("Unexpected greeting from $host:$port (not an IMAP server?)", 503)
            }
            if (greetingWord == "BYE") {
                throw MailTransportError(
                    "$host:$port refused the connection: ${stripSecret(greeting.removePrefix("*").trim(), password)}",
                    503,
                )
            }
            val capabilities = mutableListOf<String>()
            CAPABILITY_IN_BRACKETS.find(greeting)?.let { capabilities.addAll(it.groupValues[1].split(" ")) }

            var tagCounter = 0
            fun nextTag(): String {
                tagCounter += 1
                return String.format(Locale.ROOT, "L%04d", tagCounter)
            }
            fun writeLine(line: String) {
                output.write(line.toByteArray(Charsets.UTF_8))
                output.write(CRLF)
                output.flush()
            }

            if (capabilities.isEmpty()) {
                val tag = nextTag()
                writeLine("$tag CAPABILITY")
                var lines = 0
                while (true) {
                    val line = reader.readLine()
                    if (++lines > MAX_UNTAGGED_LINES) throw tooManyLines()
                    if (line.startsWith("*")) {
                        capabilities.addAll(line.removePrefix("*").trim().removePrefix("CAPABILITY").trim().split(" "))
                    } else if (line.startsWith("$tag ")) {
                        break
                    }
                }
            }

            // A PREAUTH greeting means the session is already authenticated;
            // sending LOGIN would only earn a BAD.
            if (greetingWord == "PREAUTH") return JvmImapConnection(socket, input, output, capabilities)

            val loginTag = nextTag()
            writeLine("$loginTag LOGIN ${ImapUtf7.quoteMailbox(email)} ${ImapUtf7.quoteMailbox(password)}")
            var loginLines = 0
            while (true) {
                val line = reader.readLine()
                if (++loginLines > MAX_UNTAGGED_LINES) throw tooManyLines()
                if (line.startsWith("$loginTag ")) {
                    val rest = line.removePrefix("$loginTag ")
                    val status = rest.substringBefore(' ')
                    if (status != "OK") {
                        throw MailTransportError(
                            "IMAP login failed for $email @ $host:$port \u2014 " +
                                "${loginFailureHint(host, email)} Server said: " +
                                stripSecret(rest, password),
                            401,
                        )
                    }
                    break
                }
                if (line.startsWith("*")) {
                    CAPABILITY_IN_BRACKETS.find(line)?.let {
                        capabilities.clear()
                        capabilities.addAll(it.groupValues[1].split(" "))
                    }
                }
            }

            return JvmImapConnection(socket, input, output, capabilities)
        }
    }
}

/**
 * One CRLF-terminated line at a time from an IMAP socket. The single reader
 * shared by the greeting/CAPABILITY/LOGIN exchange and by every command, so
 * end-of-stream and (SEC-03) over-long lines are handled in one place:
 * EOF is an [ImapAbortError], never an endless run of empty lines.
 * A read timeout still surfaces as [SocketTimeoutException].
 */
internal class ImapLineReader(private val input: BufferedInputStream) {
    fun readLine(): String {
        val sb = StringBuilder()
        var bytes = 0
        try {
            while (true) {
                val b = input.read()
                if (b == -1) {
                    if (sb.isEmpty()) throw ImapAbortError("server closed the connection")
                    break
                }
                if (b == '\n'.code) break
                bytes++
                // SEC-03: imaplib's _MAXLINE parity; a line with no end must not eat the heap.
                if (bytes > MAX_LINE_BYTES) throw ImapAbortError("server line longer than $MAX_LINE_BYTES bytes")
                if (b == '\r'.code) continue
                sb.append(b.toChar())
            }
        } catch (exc: SocketTimeoutException) {
            throw exc
        } catch (exc: IOException) {
            throw ImapAbortError("connection dropped while reading: ${exc.message}")
        }
        return sb.toString()
    }
}

/** Longest single response line accepted, in bytes (imaplib `_MAXLINE` parity). */
internal const val MAX_LINE_BYTES = 1_000_000

/** Most untagged response lines accepted for one command (or one handshake step). */
internal const val MAX_UNTAGGED_LINES = 100_000

internal fun tooManyLines(): ImapAbortError =
    ImapAbortError("server sent more than $MAX_UNTAGGED_LINES response lines for one command")
