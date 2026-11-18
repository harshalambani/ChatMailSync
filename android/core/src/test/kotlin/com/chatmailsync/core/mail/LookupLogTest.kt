package com.chatmailsync.core.mail

import java.io.IOException
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * F-02 diagnostics: each time the Message-ID lookup runs it reports ONE outcome-only line, and that
 * line never carries the Message-ID, the folder, the address, the password or any server text.
 */
class LookupLogTest {

    private val id = "<wa-sync-zq9x7@local>"
    private val folder = "WhatsApp/Tara Menon"
    private val email = "rohan.mehta@example.com"
    private val password = "pw-FAKE-4471"

    private class Fake(
        val appendFirst: String, // abort | store-abort
        val lookup: String, // ok | io | no
        val password: String,
        val email: String,
    ) : ImapConnection {
        var appended = 0
        var stored = false
        override val capabilities: List<String> = emptyList()
        override fun list(reference: String, pattern: String) = ImapResult("OK", listOf("INBOX"))
        override fun create(mailboxWireArg: String) = ImapResult("OK", listOf("Completed"))
        override fun subscribe(mailboxWireArg: String) = ImapResult("OK", listOf("Completed"))
        override fun append(mailboxWireArg: String, flags: String?, internalDate: OffsetDateTime?, message: ByteArray): ImapResult {
            appended++
            if (appended == 1) {
                if (appendFirst == "store-abort") stored = true
                throw ImapAbortError("dropped LOGIN $password $email")
            }
            stored = true
            return ImapResult("OK", listOf("Completed"))
        }
        override fun examine(mailboxWireArg: String): ImapResult = when (lookup) {
            "io" -> throw IOException("reset $password $email")
            "no" -> ImapResult("NO", listOf("busy $password"))
            else -> ImapResult("OK", listOf("1 EXISTS"))
        }
        override fun uidSearchMessageId(messageId: String) =
            ImapResult("OK", if (stored) listOf("7") else emptyList())
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

    private fun run(appendFirst: String, lookup: String): List<String> {
        val lines = mutableListOf<String>()
        val fake = Fake(appendFirst, lookup, password, email)
        val t = ImapTransport(
            host = "imap.example.com", port = 993, email = email, password = password,
            connectionFactory = { fake }, lookupLog = { lines.add(it) },
        )
        try {
            t.messagesInsert(raw(), folder, null)
        } catch (_: MailTransportError) {
        }
        return lines
    }

    private fun assertClean(lines: List<String>) {
        for (l in lines) {
            for (secret in listOf(id, "wa-sync-zq9x7", folder, "Tara", "Menon", email, "rohan", password, "LOGIN", "busy", "reset")) {
                assertFalse("log line leaks '$secret': $l", l.contains(secret))
            }
        }
    }

    @Test
    fun foundIsReported() {
        val lines = run("store-abort", "ok")
        assertEquals(listOf("F-02 lookup: found"), lines)
        assertClean(lines)
    }

    @Test
    fun notFoundIsReported() {
        val lines = run("abort", "ok")
        assertEquals(listOf("F-02 lookup: not found"), lines)
        assertClean(lines)
    }

    @Test
    fun failedIsReportedWithTheClassOnly() {
        val io = run("abort", "io")
        assertEquals(listOf("F-02 lookup: failed (IOException)"), io)
        assertClean(io)
        val no = run("abort", "no")
        assertEquals(listOf("F-02 lookup: failed (MailTransportError)"), no)
        assertClean(no)
    }

    // Negative: no uncertain APPEND means no lookup and no line at all.
    @Test
    fun noLookupMeansNoLine() {
        val lines = mutableListOf<String>()
        val fake = object : ImapConnection by Fake("abort", "ok", password, email) {
            override fun append(mailboxWireArg: String, flags: String?, internalDate: OffsetDateTime?, message: ByteArray) =
                ImapResult("OK", listOf("Completed"))
        }
        ImapTransport(
            host = "imap.example.com", port = 993, email = email, password = password,
            connectionFactory = { fake }, lookupLog = { lines.add(it) },
        ).messagesInsert(raw(), folder, null)
        assertTrue(lines.isEmpty())
    }
}
