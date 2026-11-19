package com.chatmailsync.core.mail

import java.io.IOException
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D180: every APPEND reports size, seconds, outcome and scrubbed server text, and nothing sensitive. */
class AppendLogTest {

    private val id = "<wa-sync-qq41m@local>"
    private val folder = "WhatsApp/Tara Menon"
    private val email = "rohan.mehta@example.com"
    private val password = "pw-FAKE-9921"

    private fun conn(append: () -> ImapResult) = object : ImapConnection {
        override val capabilities: List<String> = emptyList()
        override fun list(reference: String, pattern: String) = ImapResult("OK", listOf("INBOX"))
        override fun create(mailboxWireArg: String) = ImapResult("OK", listOf("Completed"))
        override fun subscribe(mailboxWireArg: String) = ImapResult("OK", listOf("Completed"))
        override fun append(mailboxWireArg: String, flags: String?, internalDate: OffsetDateTime?, message: ByteArray) = append()
        override fun examine(mailboxWireArg: String) = ImapResult("OK", listOf("0 EXISTS"))
        override fun uidSearchMessageId(messageId: String) = ImapResult("OK", emptyList())
        override fun logout() {}
    }

    private fun raw(): ByteArray {
        val chunk = listOf(
            ParsedMessage(
                chatId = "c", timestamp = java.time.LocalDateTime.of(2019, 5, 3, 10, 15, 0),
                sender = "Meera Iyer", body = "hello",
            ),
        )
        val (b64, _) = MimeBuilder.buildMimeMessage(
            displayName = "Tara Menon", chunk = chunk, chunkSize = ChunkSize.Day, labelId = folder, messageId = id,
        )
        return java.util.Base64.getUrlDecoder().decode(b64)
    }

    private fun run(append: () -> ImapResult): List<String> {
        val lines = mutableListOf<String>()
        val t = ImapTransport(
            host = "imap.example.com", port = 993, email = email, password = password,
            connectionFactory = { conn(append) }, diagLog = { lines.add(it) },
        )
        try {
            t.messagesInsert(raw(), folder, null)
        } catch (_: Exception) {
        }
        return lines.filter { it.startsWith("APPEND:") }
    }

    private fun assertClean(lines: List<String>) {
        for (l in lines) {
            for (secret in listOf(id, "wa-sync-qq41m", folder, "Tara", "Menon", email, "rohan", password, "LOGIN a001")) {
                assertFalse("log line leaks '$secret': $l", l.contains(secret))
            }
            assertTrue(l.length < 300)
        }
    }

    private val line = Regex("^APPEND: \\d+ bytes, [0-9.]+ s, (.+)$")

    @Test
    fun okIsReportedWithSizeAndSeconds() {
        val lines = run { ImapResult("OK", listOf("[APPENDUID 1 7] Completed")) }
        assertEquals(1, lines.size)
        assertTrue(lines[0], line.matches(lines[0]))
        assertTrue(lines[0], lines[0].contains(", OK"))
        assertClean(lines)
    }

    @Test
    fun noIsReportedAndEchoedSecretsAreScrubbed() {
        val echo = "LOGIN a001 " + email + " " + password + " NO over quota for " + folder + " id " + id + " " + "x".repeat(400)
        val lines = run { ImapResult("NO", listOf(echo)) }
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains(", NO: "))
        assertTrue("truncated", lines[0].length < 300)
        assertFalse(lines[0].contains("LOGIN a001 " + email))
        assertClean(lines.map { it.replace("LOGIN a001", "") })
    }

    @Test
    fun anExceptionIsReportedByClassOnly() {
        val lines = run { throw IOException("reset " + password + " " + email) }
        assertTrue(lines.isNotEmpty())
        assertTrue(lines[0], lines[0].contains(", IOException"))
        assertClean(lines)
    }

    // Negative: an address-shaped string the code does not know is still removed.
    @Test
    fun anUnknownAddressShapedTokenIsRemoved() {
        val lines = run { ImapResult("NO", listOf("denied for stranger.person@other.example")) }
        assertFalse(lines[0], lines[0].contains("stranger"))
        assertFalse(lines[0], lines[0].contains("@"))
    }
}
