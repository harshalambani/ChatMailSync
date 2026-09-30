package com.chatmailsync.core.mail

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory

/**
 * SEC-05: the certificate must be valid for the host we asked for, checked by
 * the endpoint algorithm AND by an explicit verifier after the handshake.
 *
 * Real TLS over loopback, with certificates generated at test runtime by
 * `keytool` into a test-owned temp directory (nothing is committed).
 *
 * What this JVM test does NOT prove: Android's Conscrypt provider and its
 * OkHostnameVerifier. That proof is the badssl.com wrong.host test on a
 * phone, deferred to KT-09.
 */
class TlsHostCheckTest {

    companion object {
        private const val MATCHING_NAME = "mail.example.test"
        private const val OTHER_NAME = "other.example.test"
        private const val STORE_PASSWORD = "changeit-FAKE"

        private lateinit var dir: File
        private lateinit var matchingServerContext: SSLContext
        private lateinit var otherServerContext: SSLContext
        private lateinit var clientContext: SSLContext

        private fun keytool(vararg args: String) {
            val exe = File(File(System.getProperty("java.home"), "bin"), if (isWindows()) "keytool.exe" else "keytool")
            val process = ProcessBuilder(listOf(exe.path) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(60, TimeUnit.SECONDS)) { "keytool timed out" }
            check(process.exitValue() == 0) { "keytool failed: $output" }
        }

        private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

        private fun makeStore(name: String, san: String): File {
            val file = File(dir, "$name.p12")
            file.deleteOnExit()
            keytool(
                "-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=$name", "-ext", "san=$san",
                "-keystore", file.path, "-storetype", "PKCS12",
                "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD,
            )
            return file
        }

        private fun load(file: File): KeyStore {
            val ks = KeyStore.getInstance("PKCS12")
            file.inputStream().use { ks.load(it, STORE_PASSWORD.toCharArray()) }
            return ks
        }

        private fun serverContext(ks: KeyStore): SSLContext {
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, STORE_PASSWORD.toCharArray())
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(kmf.keyManagers, null, null)
            return ctx
        }

        @BeforeClass
        @JvmStatic
        fun generateCertificates() {
            dir = Files.createTempDirectory("chatmailsync-tls-test").toFile()
            dir.deleteOnExit() // registered first, so it is removed last, after the files inside it
            val matching = load(makeStore("matching", "dns:$MATCHING_NAME"))
            val other = load(makeStore("other", "dns:$OTHER_NAME"))
            matchingServerContext = serverContext(matching)
            otherServerContext = serverContext(other)

            // The client trusts BOTH certificates, so the chain check can never be what rejects
            // the wrong-name one: only the host-name check can.
            val trust = KeyStore.getInstance("PKCS12")
            trust.load(null, null)
            trust.setCertificateEntry("matching", matching.getCertificate("matching"))
            trust.setCertificateEntry("other", other.getCertificate("other"))
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(trust)
            clientContext = SSLContext.getInstance("TLSv1.2")
            clientContext.init(null, tmf.trustManagers, null)
        }

        @AfterClass
        @JvmStatic
        fun noop() {
            // temp files are removed by deleteOnExit
        }
    }

    private val email = JvmImapConnectionWireTest.EMAIL
    private val password = JvmImapConnectionWireTest.FAKE_PASSWORD

    /** Opener that dials the loopback server but presents [host] (the name we "asked for"). */
    private fun opener(
        server: ScriptedImapServer,
        endpointAlgorithm: String? = "HTTPS",
        verifier: HostnameVerifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier(),
    ) = JvmImapConnection.SocketOpener { host, _, timeoutMillis ->
        val plain = Socket()
        plain.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), server.port), timeoutMillis)
        plain.soTimeout = timeoutMillis
        TlsHostCheck.handshake(plain, host, server.port, clientContext, endpointAlgorithm, verifier)
    }

    private fun connectTo(server: ScriptedImapServer, opener: JvmImapConnection.SocketOpener): JvmImapConnection =
        JvmImapConnection.connectVia(opener, MATCHING_NAME, server.port, email, password, 5)

    private fun assert503NoLogin(server: ScriptedImapServer, opener: JvmImapConnection.SocketOpener) {
        try {
            connectTo(server, opener)
            fail("a certificate for the wrong name must be refused")
        } catch (e: MailTransportError) {
            assertEquals(e.message, 503, e.status)
        }
        assertFalse(server.received, server.received.contains("LOGIN"))
    }

    // ---- real TLS, real handshake

    // Positive control: the right name, with the endpoint algorithm on (production), logs in.
    @Test(timeout = 30_000)
    fun matchingCertificateLogsIn() {
        ScriptedImapServer(matchingServerContext) { it.serve() }.use { server ->
            val conn = connectTo(server, opener(server))
            assertTrue(server.received, server.received.contains("LOGIN"))
            conn.logout()
        }
    }

    // Positive control with the endpoint algorithm OFF: our own verifier alone accepts the right name.
    @Test(timeout = 30_000)
    fun matchingCertificatePassesTheExplicitCheckAlone() {
        ScriptedImapServer(matchingServerContext) { it.serve() }.use { server ->
            val conn = connectTo(server, opener(server, endpointAlgorithm = null))
            conn.logout()
        }
    }

    // NEGATIVE: a trusted certificate for a DIFFERENT name is refused and LOGIN is never written.
    @Test(timeout = 30_000)
    fun wrongNameCertificateIsRefusedWith503AndNoLogin() {
        ScriptedImapServer(otherServerContext) { it.serve() }.use { server ->
            assert503NoLogin(server, opener(server))
        }
    }

    // NEGATIVE: same, with the platform endpoint check switched OFF. This is the case the extra
    // verifier exists for: only our post-handshake check can reject here.
    @Test(timeout = 30_000)
    fun wrongNameCertificateIsRefusedEvenWithTheEndpointAlgorithmOff() {
        // Precondition: without any host check at all, this handshake really does succeed.
        ScriptedImapServer(otherServerContext) { it.serve() }.use { server ->
            val plain = Socket(InetAddress.getLoopbackAddress(), server.port)
            val tls = clientContext.socketFactory.createSocket(plain, MATCHING_NAME, server.port, true) as javax.net.ssl.SSLSocket
            tls.startHandshake()
            tls.close()
        }
        ScriptedImapServer(otherServerContext) { it.serve() }.use { server ->
            assert503NoLogin(server, opener(server, endpointAlgorithm = null))
        }
    }

    // NEGATIVE: a real (non-stub) verifier that says no is final, even for a matching certificate.
    @Test(timeout = 30_000)
    fun aNonStubVerifierThatRefusesIsFinal() {
        ScriptedImapServer(matchingServerContext) { it.serve() }.use { server ->
            assert503NoLogin(server, opener(server, endpointAlgorithm = null, verifier = HostnameVerifier { _, _ -> false }))
        }
    }

    // A real verifier that says yes is authoritative (this is how Android's OkHostnameVerifier decides).
    @Test(timeout = 30_000)
    fun aNonStubVerifierThatAcceptsIsAuthoritative() {
        ScriptedImapServer(otherServerContext) { it.serve() }.use { server ->
            val conn = connectTo(server, opener(server, endpointAlgorithm = null, verifier = HostnameVerifier { _, _ -> true }))
            conn.logout()
        }
    }

    // ---- the connection check's TLS probe

    private fun probe(server: ScriptedImapServer, endpointAlgorithm: String?) {
        val plain = Socket(InetAddress.getLoopbackAddress(), server.port)
        probeTls(plain, MATCHING_NAME, clientContext, endpointAlgorithm)
    }

    @Test(timeout = 30_000)
    fun probeTlsAcceptsTheMatchingCertificate() {
        ScriptedImapServer(matchingServerContext) { it.readLine() }.use { server ->
            probe(server, "HTTPS")
        }
    }

    // NEGATIVE: the probe refuses a wrong-name certificate with the endpoint algorithm on.
    @Test(timeout = 30_000)
    fun probeTlsRefusesAWrongNameCertificate() {
        ScriptedImapServer(otherServerContext) { it.readLine() }.use { server ->
            try {
                probe(server, "HTTPS")
                fail("must be refused")
            } catch (_: Exception) {
                // expected
            }
        }
    }

    // NEGATIVE: and with the endpoint algorithm off, the explicit check still refuses it.
    @Test(timeout = 30_000)
    fun probeTlsRefusesAWrongNameCertificateWithTheEndpointAlgorithmOff() {
        ScriptedImapServer(otherServerContext) { it.readLine() }.use { server ->
            try {
                probe(server, null)
                fail("must be refused")
            } catch (e: SSLPeerUnverifiedException) {
                assertTrue(e.message, e.message!!.contains("not valid"))
            }
        }
    }

    // ---- the name matcher, without certificates

    private fun dns(vararg n: String) = n.map { 2 to it }

    @Test
    fun matcherAcceptsExactAndWildcardNames() {
        assertTrue(TlsHostCheck.matchesHost("imap.mail.example.com", dns("imap.mail.example.com")))
        assertTrue(TlsHostCheck.matchesHost("IMAP.Mail.Example.COM.", dns("imap.mail.example.com")))
        assertTrue(TlsHostCheck.matchesHost("imap.example.com", dns("*.example.com")))
        assertTrue(TlsHostCheck.matchesHost("imap.example.com", dns("a.example.com", "*.example.com")))
    }

    // NEGATIVE: what a wildcard and the matcher must NOT accept.
    @Test
    fun matcherRejectsWhatItMust() {
        assertFalse(TlsHostCheck.matchesHost("other.example.com", dns("imap.example.com")))
        assertFalse("wildcard covers one label only", TlsHostCheck.matchesHost("a.b.example.com", dns("*.example.com")))
        assertFalse("wildcard does not cover the bare domain", TlsHostCheck.matchesHost("example.com", dns("*.example.com")))
        assertFalse("wildcard over a bare TLD", TlsHostCheck.matchesHost("example.com", dns("*.com")))
        assertFalse("wildcard not in the leftmost label", TlsHostCheck.matchesHost("a.example.com", dns("a.*.com")))
        assertFalse("suffix trick", TlsHostCheck.matchesHost("imap.example.com.evil.test", dns("*.example.com")))
        assertFalse("no names at all", TlsHostCheck.matchesHost("imap.example.com", emptyList()))
        assertFalse("empty host", TlsHostCheck.matchesHost("", dns("*.example.com")))
        // A name sitting in another GeneralName slot (email, type 1) is not a DNS name.
        assertFalse(TlsHostCheck.matchesHost("imap.example.com", listOf(1 to "imap.example.com")))
    }

    @Test
    fun matcherHandlesIpAddresses() {
        assertTrue(TlsHostCheck.matchesHost("127.0.0.1", listOf(7 to "127.0.0.1")))
        assertFalse(TlsHostCheck.matchesHost("127.0.0.1", listOf(7 to "127.0.0.2")))
        assertFalse("a DNS SAN never matches an IP host", TlsHostCheck.matchesHost("127.0.0.1", dns("127.0.0.1")))
        assertFalse("an IP SAN never matches a name", TlsHostCheck.matchesHost("imap.example.com", listOf(7 to "127.0.0.1")))
    }
}
