package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.Locale

/**
 * The REAL [JvmImapConnection] against a scripted local server (plain socket
 * seam). No double in the way: every read, tag and status line is production
 * code.
 */
class JvmImapConnectionWireTest {

    private fun connect(
        server: ScriptedImapServer,
        password: String = FAKE_PASSWORD,
        email: String = EMAIL,
    ): JvmImapConnection =
        JvmImapConnection.connectVia(server.plainOpener(), "127.0.0.1", server.port, email, password, 5)

    private fun <T> underMarathi(block: () -> T): T {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("mr-IN"))
            return block()
        } finally {
            Locale.setDefault(saved)
        }
    }

    // ---- PAR-01: command tags stay ASCII whatever the phone's language is

    // NEGATIVE: on a Marathi phone the tags must not turn into native digits.
    @Test(timeout = 20_000)
    fun tagsAreAsciiUnderMarathiLocale() = underMarathi {
        // Precondition: on this JDK the bug really would bite. Without it
        // this test would pass vacuously.
        assertFalse(
            "precondition: %d under mr-IN should produce native digits on this JDK",
            String.format("%04d", 1).all { it.code < 128 },
        )
        ScriptedImapServer { it.serve() }.use { server ->
            val conn = connect(server)
            conn.list("\"\"", "*")
            conn.logout()
            val sent = server.received
            assertTrue("only ASCII may reach the wire", sent.all { it.code < 128 })
            // The greeting carries CAPABILITY, so the login is the first L-tag.
            assertTrue(sent, sent.contains("L0001 LOGIN "))
            assertTrue(sent, sent.contains("A0001 LIST "))
            assertTrue(sent, sent.contains("A0002 LOGOUT"))
        }
    }

    // NEGATIVE: with the bug the server's echo of the tag never matches and the
    // command hangs until the timeout. It must complete with a status instead.
    @Test(timeout = 20_000)
    fun commandCompletesUnderMarathiLocale() = underMarathi {
        ScriptedImapServer { it.serve() }.use { server ->
            val conn = connect(server)
            assertEquals("OK", conn.list("\"\"", "*").status)
            conn.logout()
        }
    }

    // NEGATIVE: the Week subject must read "Week 07", not native digits.
    @Test
    fun weekSubjectIsAsciiUnderMarathiLocale() = underMarathi {
        val msg = ParsedMessage(
            chatId = "chat-1",
            timestamp = LocalDateTime.of(2025, 2, 12, 10, 0, 0),
            sender = "Meera Iyer",
            body = "hello",
        )
        val subject = MimeBuilder.chunkSubject("Rohan Mehta", listOf(msg), ChunkSize.Week)
        assertTrue(subject, subject.contains("Week 07, 2025"))
        assertTrue(subject, subject.substringAfter("Week").all { it.code < 128 })
    }

    companion object {
        const val FAKE_PASSWORD = "p@ss\"w\\rd-FAKE"
        const val EMAIL = "meera.iyer@example.com"
    }
}
