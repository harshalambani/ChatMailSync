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

    private var tagCounter = 0

    private fun nextTag(): String {
        tagCounter += 1
        return "A%04d".format(tagCounter)
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

    private fun readLine(): String {
        val sb = StringBuilder()
        try {
            while (true) {
                val b = input.read()
                if (b == -1) {
                    if (sb.isEmpty()) throw ImapAbortError("server closed the connection")
                    break
                }
                if (b == '\n'.code) break
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

    /** Runs one tagged command, collecting untagged response lines, and returns the final status. */
    private fun runCommand(commandLine: String, literal: ByteArray? = null): ImapResult {
        val tag = nextTag()
        writeLine("$tag $commandLine")
        val data = mutableListOf<String?>()
        while (true) {
            val line = readLine()
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
                if (status != "OK") throw ImapCommandError(text.ifEmpty { rest })
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

    companion object {
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
        fun connect(host: String, port: Int, email: String, password: String, timeoutSeconds: Long): JvmImapConnection {
            val socket: SSLSocket
            try {
                val context = SSLContext.getInstance("TLSv1.2")
                context.init(null, null, null)
                val factory = context.socketFactory
                val plain = Socket()
                plain.connect(InetSocketAddress(host, port), (timeoutSeconds * 1000).toInt())
                plain.soTimeout = (timeoutSeconds * 1000).toInt()
                socket = factory.createSocket(plain, host, port, true) as SSLSocket
                val params = SSLParameters()
                params.endpointIdentificationAlgorithm = "HTTPS"
                socket.sslParameters = params
                socket.enabledProtocols = socket.supportedProtocols.filter {
                    it == "TLSv1.2" || it == "TLSv1.3"
                }.toTypedArray()
                socket.startHandshake()
            } catch (exc: Exception) {
                throw MailTransportError(
                    "Could not connect to $host:$port: ${stripSecret(exc.message ?: exc.toString(), password)}",
                    503,
                )
            }

            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            fun readLine(): String {
                val sb = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b == -1) break
                    if (b == '\n'.code) break
                    if (b == '\r'.code) continue
                    sb.append(b.toChar())
                }
                return sb.toString()
            }

            val greeting = readLine()
            val capabilities = mutableListOf<String>()
            val capMatch = Regex("\\[CAPABILITY ([^]]+)]").find(greeting)
            if (capMatch != null) capabilities.addAll(capMatch.groupValues[1].split(" "))

            var tagCounter = 0
            fun nextTag(): String {
                tagCounter += 1
                return "L%04d".format(tagCounter)
            }
            fun writeLine(line: String) {
                output.write(line.toByteArray(Charsets.UTF_8))
                output.write(CRLF)
                output.flush()
            }

            if (capabilities.isEmpty()) {
                val tag = nextTag()
                writeLine("$tag CAPABILITY")
                while (true) {
                    val line = readLine()
                    if (line.startsWith("*")) {
                        capabilities.addAll(line.removePrefix("*").trim().removePrefix("CAPABILITY").trim().split(" "))
                    } else if (line.startsWith("$tag ")) {
                        break
                    }
                }
            }

            val loginTag = nextTag()
            writeLine("$loginTag LOGIN ${ImapUtf7.quoteMailbox(email)} ${ImapUtf7.quoteMailbox(password)}")
            while (true) {
                val line = readLine()
                if (line.startsWith("$loginTag ")) {
                    val rest = line.removePrefix("$loginTag ")
                    val status = rest.substringBefore(' ')
                    if (status != "OK") {
                        throw MailTransportError(
                            "IMAP login failed for $email @ $host:$port — " +
                                "${loginFailureHint(host, email)} Server said: " +
                                stripSecret(rest, password),
                            401,
                        )
                    }
                    break
                }
                if (line.startsWith("*")) {
                    val capMatch2 = Regex("\\[CAPABILITY ([^]]+)]").find(line)
                    if (capMatch2 != null) {
                        capabilities.clear()
                        capabilities.addAll(capMatch2.groupValues[1].split(" "))
                    }
                }
            }

            return JvmImapConnection(socket, input, output, capabilities)
        }
    }
}
