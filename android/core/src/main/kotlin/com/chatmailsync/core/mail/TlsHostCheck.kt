package com.chatmailsync.core.mail

import java.net.InetAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

/**
 * The one place a TLS handshake to the mail server is performed, used by both
 * [JvmImapConnection] and the connection check's TLS probe (SEC-05).
 *
 * A bare `SSLSocket` does not check the host name on its own. We set
 * `endpointIdentificationAlgorithm = "HTTPS"` (which makes the platform's
 * TLS stack do it), and then ALSO ask the default [HostnameVerifier] to
 * confirm the certificate is valid for the host after the handshake, so a
 * platform whose TLS provider quietly skips the endpoint check cannot accept
 * a valid certificate for the wrong server.
 *
 * What the JVM test does NOT prove: on Android the default verifier is
 * OkHostnameVerifier and TLS is Conscrypt. The proof that a wrong-host
 * certificate is rejected there is the badssl.com wrong.host test on a
 * phone, deferred to KT-09.
 */
internal object TlsHostCheck {

    private const val JDK_STUB_VERIFIER = "javax.net.ssl.HttpsURLConnection\$DefaultHostnameVerifier"

    fun defaultContext(): SSLContext {
        val context = SSLContext.getInstance("TLSv1.2")
        context.init(null, null, null)
        return context
    }

    /**
     * Wraps [plain] in TLS, handshakes, then checks the certificate names
     * [host]. On any failure the socket is closed and the exception thrown.
     * [context], [endpointAlgorithm] and [verifier] exist for tests; production
     * uses the defaults.
     */
    fun handshake(
        plain: Socket,
        host: String,
        port: Int,
        context: SSLContext = defaultContext(),
        endpointAlgorithm: String? = "HTTPS",
        verifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
    ): SSLSocket {
        val tls = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        try {
            val params = SSLParameters()
            params.endpointIdentificationAlgorithm = endpointAlgorithm
            tls.sslParameters = params
            tls.enabledProtocols = tls.supportedProtocols.filter {
                it == "TLSv1.2" || it == "TLSv1.3"
            }.toTypedArray()
            tls.startHandshake()
            verify(host, tls.session, verifier)
            return tls
        } catch (exc: Exception) {
            try {
                tls.close()
            } catch (_: Exception) {
                // best effort
            }
            throw exc
        }
    }

    /**
     * Fails with [SSLPeerUnverifiedException] unless [verifier] accepts [host].
     *
     * A real verifier (Android's OkHostnameVerifier, OkHttp's) is final: its
     * "no" is a no. The JDK's own default verifier is a stub that answers
     * "no" to everything (it exists only for HttpsURLConnection's internal
     * use), so when that stub is what we were given we check the
     * certificate's names ourselves with [matchesHost].
     */
    fun verify(host: String, session: SSLSession, verifier: HostnameVerifier) {
        val ok = if (verifier.javaClass.name == JDK_STUB_VERIFIER) {
            certificateMatches(host, session)
        } else {
            verifier.verify(host, session)
        }
        if (!ok) {
            throw SSLPeerUnverifiedException("the server's certificate is not valid for this server name")
        }
    }

    private fun certificateMatches(host: String, session: SSLSession): Boolean {
        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return false
        val names = leaf.subjectAlternativeNames ?: return false
        val sans = names.mapNotNull { entry ->
            val type = entry.getOrNull(0) as? Int ?: return@mapNotNull null
            val value = entry.getOrNull(1) as? String ?: return@mapNotNull null
            type to value
        }
        return matchesHost(host, sans)
    }

    /**
     * RFC 6125 matching of [host] against certificate subject-alternative
     * names ([sans] = pairs of GeneralName type and value). DNS names (type
     * 2) match case-insensitively; a `*.` wildcard covers exactly one
     * leftmost label. IP addresses (type 7) must match exactly, with no
     * wildcards. The subject CN is deliberately ignored.
     */
    fun matchesHost(host: String, sans: List<Pair<Int, String>>): Boolean {
        val h = host.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (h.isEmpty()) return false
        val hostIsIp = looksLikeIp(h)
        for ((type, raw) in sans) {
            if (hostIsIp) {
                if (type == 7 && sameIp(h, raw)) return true
            } else if (type == 2) {
                val pattern = raw.trim().trimEnd('.').lowercase(Locale.ROOT)
                if (pattern == h) return true
                if (pattern.startsWith("*.") && pattern.indexOf('*', 1) == -1) {
                    val suffix = pattern.substring(1) // ".example.com"
                    val firstDot = h.indexOf('.')
                    // exactly one label in place of the star, and at least two labels remain after it
                    if (firstDot > 0 && h.substring(firstDot) == suffix && suffix.count { it == '.' } >= 2) return true
                }
            }
        }
        return false
    }

    private fun looksLikeIp(h: String): Boolean =
        h.contains(':') || h.all { it.isDigit() || it == '.' }

    private fun sameIp(a: String, b: String): Boolean = try {
        InetAddress.getByName(a) == InetAddress.getByName(b)
    } catch (_: Exception) {
        false
    }
}
