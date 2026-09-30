package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.Base64

/**
 * SEC-01: a chat name or subject carrying CR, LF or NUL must not be able to
 * start a new header line or inject a header (Bcc, To, ...). The built message
 * has exactly one Subject and exactly one From, and a normal name is unchanged
 * against the Python golden (see [MimeCasesGoldenParityTest]).
 */
class MimeHeaderInjectionTest {

    private fun build(displayName: String, chatId: String = "chat", messageId: String = "<a@local>", inReplyTo: String? = null): String {
        val (raw, _) = MimeBuilder.buildMimeMessage(
            displayName = displayName,
            chunk = listOf(ParsedMessage(chatId, LocalDateTime.of(2026, 1, 2, 3, 4, 5), "Meera", "hi")),
            chunkSize = ChunkSize.Day,
            labelId = "WhatsApp/x",
            messageId = messageId,
            inReplyTo = inReplyTo,
        )
        return String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
    }

    private fun headerBlock(eml: String) = eml.substringBefore("\n\n")

    private fun headerNames(eml: String): List<String> =
        headerBlock(eml).split("\n").filter { !it.startsWith(" ") && !it.startsWith("\t") }.map { it.substringBefore(':') }

    private val attacks = listOf(
        "Meera\r\nBcc: attacker@example.com",
        "Meera\nBcc: attacker@example.com",
        "Meera\rBcc: attacker@example.com",
        "Meera\r\nTo: victim@example.com\r\n\r\nbody",
        "Meera\u0000\nBcc: x@y.z",
        "Meera\u0000Bcc: x@y.z",
        "Meera" + 0x2028.toChar() + "Bcc: x@y.z",
        "Meera\u0085Bcc: x@y.z",
        "मीरा\r\nBcc: x@y.z",
        "Meera\r\n Bcc: x@y.z",
        "\r\n",
        "\u0000",
    )

    @Test
    fun crLfOrNulInTheChatNameCannotInjectAHeader() {
        val known = setOf(
            "Content-Type", "MIME-Version", "Subject", "From", "To", "Message-ID", "Date",
            HEADER_VERSION, HEADER_CHAT, HEADER_COUNT, HEADER_INDEX,
        )
        for (attack in attacks) {
            val eml = build(attack)
            val names = headerNames(eml)
            assertEquals("one Subject for ${attack.toList()}", 1, names.count { it == "Subject" })
            assertEquals("one From for ${attack.toList()}", 1, names.count { it == "From" })
            assertFalse("Bcc injected for ${attack.toList()}: $names", names.contains("Bcc"))
            assertEquals("one To for ${attack.toList()}", 1, names.count { it == "To" })
            assertTrue("unknown header in $names", known.containsAll(names))
            // The header block ends at the blank line MimeBuilder wrote, so the
            // multipart body must follow at once.
            assertTrue(eml.substringAfter("\n\n").startsWith("--"))
            assertTrue(
                "control char left in headers for ${attack.toList()}",
                headerBlock(eml).none { (it.code < 0x20 && it != '\n' && it != '\t') || it.code == 0x7F },
            )
        }
    }

    @Test
    fun crLfInMessageIdAndInReplyToCannotInjectAHeader() {
        val eml = build("Meera", messageId = "<a@local>\r\nBcc: x@y.z", inReplyTo = "<b@local>\nBcc: q@r.s")
        assertFalse(headerNames(eml).contains("Bcc"))
        assertEquals(1, headerNames(eml).count { it == "Message-ID" })
    }

    @Test
    fun crLfInTheChatIdCannotInjectAHeader() {
        // headerSafe already covered this header; it must keep doing so.
        val eml = build("Meera", chatId = "chat\r\nBcc: x@y.z")
        assertFalse(headerNames(eml).contains("Bcc"))
    }

    @Test
    fun aNormalNameIsLeftAloneIncludingDoubleSpaces() {
        assertEquals("Meera Iyer", Compat32Headers.lineSafe("Meera Iyer"))
        // Python keeps double spaces in the Subject; so do we (no collapse).
        assertEquals("Rohan  Mehta", Compat32Headers.lineSafe("Rohan  Mehta"))
        val party = "मीरा 🎉"
        assertEquals(party, Compat32Headers.lineSafe(party))
        assertEquals("a\tb", Compat32Headers.lineSafe("a\tb"))
        assertEquals("Meera Bcc: x", Compat32Headers.lineSafe("Meera\r\n\u0000Bcc: x"))
    }

    @Test
    fun formatSenderSanitisesTheNameAndStillMatchesForNormalNames() {
        assertEquals("Meera Iyer <whatsapp-sync@local>", MimeBuilder.formatSender("Meera Iyer"))
        assertFalse(MimeBuilder.formatSender("Meera\r\nBcc: x@y.z").any { it == '\r' || it == '\n' })
        assertFalse(MimeBuilder.formatSender("Meera\u0000").contains('\u0000'))
        assertFalse(MimeBuilder.formatSender("मीरा\nBcc: x").contains('\n'))
    }
}
