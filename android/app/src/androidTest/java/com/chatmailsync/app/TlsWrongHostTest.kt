package com.chatmailsync.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SEC-12 (audit F-12): on a real device, with Android's own TLS provider and hostname verifier, a
 * certificate that the client TRUSTS but that names a different host is refused by the real
 * connection code (TlsHostCheck.handshake, the one handshake used by the IMAP connection and the
 * connection check). The JVM test of the same name only proves it on the desktop JDK.
 *
 * Both throwaway certificates are in the client trust store, so the chain check can never be what
 * rejects the wrong-name one: only the host-name check can. The matching-name case is the negative
 * partner: same server setup, same trust, the right name, accepted. Synthetic certificates (names
 * under .example.test, a fake store password); no account, no network beyond loopback.
 *
 * TlsHostCheck is internal to :core, so it is reached by reflection; nothing in the app changed.
 */
@RunWith(AndroidJUnit4::class)
class TlsWrongHostTest {

    companion object {
        private const val ASKED_FOR = "mail.example.test"
        private const val STORE_PASSWORD = "changeit-FAKE"

        private lateinit var matchingServer: SSLContext
        private lateinit var otherServer: SSLContext
        private lateinit var client: SSLContext
        private val servers = mutableListOf<SSLServerSocket>()

        private fun load(name: String): KeyStore {
            val stream: InputStream = TlsWrongHostTest::class.java.classLoader!!
                .getResourceAsStream("tls/$name.p12") ?: error("missing test resource tls/$name.p12")
            return stream.use { KeyStore.getInstance("PKCS12").apply { load(it, STORE_PASSWORD.toCharArray()) } }
        }

        private fun serverContext(ks: KeyStore): SSLContext {
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, STORE_PASSWORD.toCharArray())
            return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        }

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val matching = load("matching")
            val other = load("other")
            matchingServer = serverContext(matching)
            otherServer = serverContext(other)
            val trust = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            trust.setCertificateEntry("matching", matching.getCertificate("matching"))
            trust.setCertificateEntry("other", other.getCertificate("other"))
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(trust)
            client = SSLContext.getInstance("TLSv1.2").apply { init(null, tmf.trustManagers, null) }
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            servers.forEach { try { it.close() } catch (_: Exception) { } }
        }
    }

    /** A loopback TLS server that handshakes once with [ctx], then closes. Returns its port. */
    private fun serve(ctx: SSLContext): Int {
        val server = ctx.serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        servers += server
        Thread {
            try {
                val s = server.accept() as SSLSocket
                s.soTimeout = 10_000
                try {
                    s.startHandshake()
                } catch (_: Exception) {
                    // the client may walk away from a certificate it refuses
                } finally {
                    s.close()
                }
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true }.start()
        return server.localPort
    }

    /** The real production handshake, reached by reflection (it is internal to :core). */
    private fun handshake(port: Int, endpointAlgorithm: String?): SSLSocket {
        val plain = Socket()
        plain.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000)
        plain.soTimeout = 10_000
        val cls = Class.forName("com.chatmailsync.core.mail.TlsHostCheck")
        val target = cls.getField("INSTANCE").get(null)
        val m = cls.getMethod(
            "handshake",
            Socket::class.java, String::class.java, Int::class.javaPrimitiveType,
            SSLContext::class.java, String::class.java, HostnameVerifier::class.java,
        )
        try {
            return m.invoke(
                target, plain, ASKED_FOR, port, client, endpointAlgorithm,
                HttpsURLConnection.getDefaultHostnameVerifier(),
            ) as SSLSocket
        } catch (e: java.lang.reflect.InvocationTargetException) {
            try { plain.close() } catch (_: Exception) { }
            throw e.targetException
        }
    }

    private fun assertRefused(port: Int, endpointAlgorithm: String?) {
        try {
            handshake(port, endpointAlgorithm).close()
            fail("a trusted certificate for another host must be refused")
        } catch (e: SSLException) {
            // SSLHandshakeException (the platform endpoint check) or SSLPeerUnverifiedException
            // (the explicit check after the handshake): both are a refusal over the name.
        }
    }

    // Negative partner: same trust, the right name, accepted. So the refusals below are the
    // host-name check and nothing else (not trust, protocol or the server).
    @Test(timeout = 60_000)
    fun aCertificateForTheRightNameIsAccepted() {
        val socket = handshake(serve(matchingServer), "HTTPS")
        try {
            assertTrue(socket.session.isValid)
        } finally {
            socket.close()
        }
    }

    @Test(timeout = 60_000)
    fun theRightNameIsAcceptedWithTheEndpointCheckOffToo() {
        val socket = handshake(serve(matchingServer), null)
        try {
            assertTrue(socket.session.isValid)
        } finally {
            socket.close()
        }
    }

    @Test(timeout = 60_000)
    fun aTrustedCertificateForAnotherHostIsRefused() {
        assertRefused(serve(otherServer), "HTTPS")
    }

    // With the platform endpoint check off, the explicit post-handshake check in
    // TlsHostCheck.verify is the only thing left, and it must still refuse.
    @Test(timeout = 60_000)
    fun theExplicitVerifyStillRefusesWhenTheEndpointCheckIsOff() {
        assertRefused(serve(otherServer), null)
    }
}
