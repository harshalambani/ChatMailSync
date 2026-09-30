package com.chatmailsync.core.mail

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLContext

/**
 * A scripted IMAP server on a local loopback port, for driving the REAL
 * [JvmImapConnection] over a real socket (the "test seam" decided in the
 * fix-PR brief: the seam only replaces socket creation, so every read,
 * parse, tag and status line below runs through production code).
 *
 * One connection per server. [script] runs on the server thread; whatever it
 * throws is kept in [failure] for the test to inspect. Every byte the client
 * writes is recorded in [received], so a test can prove "nothing was sent".
 */
class ScriptedImapServer(
    tlsContext: SSLContext? = null,
    private val script: (Conn) -> Unit,
) : AutoCloseable {

    private val serverSocket: ServerSocket =
        if (tlsContext == null) {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        } else {
            tlsContext.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress())
        }

    val port: Int = serverSocket.localPort

    private val receivedBytes = ByteArrayOutputStream()

    /** Everything the client has written so far, as Latin-1 text (byte-for-byte, no decoding surprises). */
    val received: String
        get() = synchronized(receivedBytes) { String(receivedBytes.toByteArray(), Charsets.ISO_8859_1) }

    @Volatile
    var failure: Throwable? = null

    private val thread = Thread {
        try {
            serverSocket.accept().use { sock ->
                sock.soTimeout = 20_000
                script(Conn(sock, receivedBytes))
            }
        } catch (t: Throwable) {
            failure = t
        }
    }.apply {
        isDaemon = true
        start()
    }

    /** A plain (non-TLS) client socket to this server; the seam used by protocol tests. */
    internal fun plainOpener(): JvmImapConnection.SocketOpener = JvmImapConnection.SocketOpener { _, _, timeoutMillis ->
        val s = Socket()
        s.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeoutMillis)
        s.soTimeout = timeoutMillis
        s
    }

    override fun close() {
        try {
            serverSocket.close()
        } catch (_: Exception) {
            // best effort
        }
        thread.join(5_000)
    }

    class Conn(private val sock: Socket, private val sink: ByteArrayOutputStream) {
        private val input: InputStream = sock.getInputStream()
        private val output: OutputStream = sock.getOutputStream()

        fun send(line: String) {
            output.write(line.toByteArray(Charsets.ISO_8859_1))
            output.write(byteArrayOf(13, 10))
            output.flush()
        }

        /** Sends [size] bytes of [fill] with NO line break, then flushes. */
        fun sendRaw(size: Int, fill: Char = 'x') {
            val chunk = ByteArray(8192) { fill.code.toByte() }
            var left = size
            while (left > 0) {
                val n = minOf(left, chunk.size)
                output.write(chunk, 0, n)
                left -= n
            }
            output.flush()
        }

        /** Next line without its CRLF, or null at EOF. Recorded into the server's [received]. */
        fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                synchronized(sink) { sink.write(b) }
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }

        fun readExactly(n: Int): ByteArray {
            val out = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(out, off, n - off)
                if (r == -1) break
                synchronized(sink) { sink.write(out, off, r) }
                off += r
            }
            return out
        }

        fun close() {
            try {
                sock.close()
            } catch (_: Exception) {
                // best effort
            }
        }

        /**
         * The usual conversation: greeting, then answer every tagged command.
         * LOGIN and LOGOUT are answered here; anything else goes to [onCommand]
         * (which must send the tagged reply itself), or gets a plain tagged OK.
         */
        fun serve(
            greeting: String? = "* OK [CAPABILITY IMAP4rev1 APPENDLIMIT=1000] scripted server ready",
            onCommand: ((Conn, String, String) -> Unit)? = null,
        ) {
            if (greeting != null) send(greeting)
            while (true) {
                val line = readLine() ?: return
                val tag = line.substringBefore(' ')
                val cmd = line.substringAfter(' ', "")
                when {
                    cmd.startsWith("LOGIN ") -> send("$tag OK LOGIN completed")
                    cmd == "LOGOUT" -> {
                        send("* BYE bye")
                        send("$tag OK LOGOUT completed")
                        return
                    }
                    onCommand != null -> onCommand(this, tag, cmd)
                    else -> send("$tag OK done")
                }
            }
        }
    }
}
