package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.time.LocalDateTime
import java.util.Base64

/**
 * PAR-06: five places where the Kotlin port drifted from Python on edge input.
 * The expected values come from `golden/par06_edge_golden.json`, produced by
 * running the real Python functions (see the generator); each area below also
 * asserts the WRONG behaviour does not happen.
 */
class Par06EdgeGoldenTest {

    private val golden: JsonNode.Obj by lazy {
        val stream = javaClass.classLoader.getResourceAsStream("golden/par06_edge_golden.json")
            ?: error("golden resource not found")
        parseJson(stream.use { it.readBytes() }.toString(Charsets.UTF_8)).asObj()
    }

    private fun ch(code: Int): String = code.toChar().toString()

    // ---- (1) malformed modified UTF-7

    @Test
    fun utf7DecodeMatchesPythonOnEveryInputIncludingMalformedOnes() {
        val rows = golden["utf7"].asArr().items.map { it.asObj() }
        assertTrue("expected the full set, got ${rows.size}", rows.size > 300)
        val bad = rows.filter { ImapUtf7.decode(it["input"].asString()) != it["output"].asString() }
            .map { "${it["input"].asString()} -> want ${it["output"].asString()} got ${ImapUtf7.decode(it["input"].asString())}" }
        assertTrue("UTF-7 decodes that differ from Python: $bad", bad.isEmpty())
    }

    // NEGATIVE: an unterminated shift sequence is decoded, not kept as literal text.
    @Test
    fun anUnterminatedShiftSequenceIsDecodedNotLeftVerbatim() {
        assertEquals(ch(0xE9), ImapUtf7.decode("&AOk"))
        assertNotEquals("&AOk", ImapUtf7.decode("&AOk"))
    }

    // NEGATIVE: a lone surrogate or an odd byte count is kept verbatim, never turned into U+FFFD.
    @Test
    fun undecodableUtf16IsKeptVerbatimNeverAReplacementCharacter() {
        for (input in listOf("&2D0-", "&3gA-", "&AA-", "&AGEA-")) {
            val out = ImapUtf7.decode(input)
            assertFalse("replacement character for $input", out.contains(0xFFFD.toChar()))
            assertEquals(input, out)
        }
    }

    // NEGATIVE: characters outside the alphabet are dropped, as b64decode does, not treated as an error.
    @Test
    fun charactersOutsideTheBase64AlphabetAreDroppedNotFatal() {
        assertEquals(ch(0xE9), ImapUtf7.decode("&AO!k=-"))
    }

    // ---- (2) APPENDLIMIT

    private fun transport(host: String, caps: List<String>): ImapTransport {
        val fake = FakeImapConnection(caps)
        val t = ImapTransport(host, 993, "meera.iyer@example.com", "pw-FAKE", connectionFactory = { fake })
        t.probeConnect()
        return t
    }

    @Test
    fun maxMessageBytesMatchesPythonForEveryCapabilityShape() {
        val rows = golden["appendLimit"].asArr().items.map { it.asObj() }
        assertTrue(rows.size >= 15)
        for (row in rows) {
            val caps = row["capabilities"].asArr().items.map { it.asString() }
            val host = row["host"].asString()
            val want = BigInteger(row["maxMessageBytes"].asString())
            val got = BigInteger.valueOf(transport(host, caps).maxMessageBytes)
            // Python's int is unbounded; Kotlin clamps at Long.MAX_VALUE.
            val expected = want.min(BigInteger.valueOf(Long.MAX_VALUE))
            assertEquals("host=$host caps=$caps", expected, got)
        }
    }

    // NEGATIVE: APPENDLIMIT=0 does NOT become a zero-byte ceiling; it falls to the provider table, then the default.
    @Test
    fun aZeroAppendLimitIsNotAZeroByteCeiling() {
        assertEquals(DEFAULT_MAX_MESSAGE_BYTES, transport("imap.example.com", listOf("APPENDLIMIT=0")).maxMessageBytes)
        val gmail = transport("imap.gmail.com", listOf("APPENDLIMIT=0")).maxMessageBytes
        assertTrue(gmail > 0)
    }

    // NEGATIVE: a huge value neither overflows to a negative number nor throws.
    @Test
    fun aHugeAppendLimitDoesNotOverflowOrThrow() {
        val v = transport("imap.example.com", listOf("APPENDLIMIT=99999999999999999999")).maxMessageBytes
        assertEquals(Long.MAX_VALUE, v)
        assertTrue(v > 0)
    }

    // NEGATIVE: a bare APPENDLIMIT (per-mailbox limits) is not a number we can use.
    @Test
    fun aBareAppendLimitIsIgnored() {
        assertEquals(DEFAULT_MAX_MESSAGE_BYTES, transport("imap.example.com", listOf("APPENDLIMIT")).maxMessageBytes)
    }

    // ---- (3) empty Message-ID, thread id and References count as missing

    @Test
    fun insertResultsMatchPythonForEmptyAndMissingIds() {
        val rows = golden["insert"].asArr().items.map { it.asObj() }
        assertTrue(rows.size >= 8)
        for (row in rows) {
            val msgId = row["messageId"].asStringOrNull()
            val threadId = row["threadId"].asStringOrNull()
            val header = if (msgId == null) "" else "Message-ID: $msgId\n"
            val raw = ("From: a@b.c\nDate: Thu, 01 Jan 2026 10:00:00 +0000\n" + header + "Subject: x\n\nbody\n").toByteArray()
            val result = transport("imap.example.com", emptyList()).messagesInsert(raw, "WhatsApp/x", threadId)
            val label = "messageId=$msgId threadId=$threadId"
            val wantId = row["expectedId"].asString()
            val wantThread = row["expectedThreadId"].asString()
            if (wantId == "GENERATED") {
                assertTrue("$label: generated id shape ${result.id}", result.id.startsWith("<") && result.id.endsWith(">"))
            } else {
                assertEquals(label, wantId, result.id)
            }
            if (wantThread == "GENERATED") {
                assertEquals("$label: a generated thread id is the message id", result.id, result.threadId)
            } else {
                assertEquals(label, wantThread, result.threadId)
            }
        }
    }

    // NEGATIVE: an empty Message-ID or thread id is never returned as the empty string.
    @Test
    fun anEmptyIdIsNeverReturnedAsEmpty() {
        val raw = "From: a@b.c\nDate: Thu, 01 Jan 2026 10:00:00 +0000\nMessage-ID:\nSubject: x\n\nbody\n".toByteArray()
        val r = transport("imap.example.com", emptyList()).messagesInsert(raw, "WhatsApp/x", "")
        assertTrue(r.id.isNotEmpty())
        assertTrue(r.threadId.isNotEmpty())
    }

    // NEGATIVE: an empty References falls back to In-Reply-To instead of writing an empty header.
    @Test
    fun anEmptyReferencesFallsBackToInReplyTo() {
        val (raw, _) = MimeBuilder.buildMimeMessage(
            displayName = "Meera Iyer",
            chunk = listOf(ParsedMessage("chat", LocalDateTime.of(2026, 1, 2, 3, 4, 5), "Meera", "hi")),
            chunkSize = ChunkSize.Day,
            labelId = "WhatsApp/x",
            messageId = "<a@local>",
            inReplyTo = "<b@local>",
            references = "",
        )
        val eml = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8).substringBefore("\n\n")
        assertTrue(eml, eml.contains("References: <b@local>"))
        assertFalse(eml, Regex("(?m)^References:\\s*$").containsMatchIn(eml))
    }

    // ---- (4) and (5) are covered by the MIME goldens; these are their negatives

    @Test
    fun aBodyEndingInANewlineGetsNoExtraIndentedBlankLine() {
        val ts = LocalDateTime.of(2026, 1, 2, 3, 4, 5)
        val body = MimeBuilder.formatChunkBody(listOf(ParsedMessage("c", ts, "Meera", "hello\n")))
        assertEquals("[03:04] Meera: hello", body)
        assertFalse(body.endsWith(" "))
        assertFalse(body.contains("\n"))
        val two = MimeBuilder.formatChunkBody(listOf(ParsedMessage("c", ts, "Meera", "a\nb\n")))
        assertEquals(2, two.split("\n").size)
    }

    @Test
    fun unicodeSpacesAndDigitsInAPhoneNumberAreRecognised() {
        val expected = "+919876543210 <whatsapp-sync@local>"
        assertEquals(expected, MimeBuilder.formatSender("+91" + ch(0xA0) + "98765" + ch(0xA0) + "43210"))
        assertEquals(expected, MimeBuilder.formatSender("+91" + ch(0x3000) + "98765" + ch(0x3000) + "43210"))
        val arabic = listOf(9, 1, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0).joinToString("") { ch(0x660 + it) }
        val from = MimeBuilder.formatSender(arabic)
        assertTrue(from, from.startsWith("=?utf-8?b?") && from.endsWith(" <whatsapp-sync@local>"))
        val payload = from.substringAfter("?b?").substringBefore("?=")
        assertEquals("+$arabic", String(Base64.getDecoder().decode(payload), Charsets.UTF_8))
        // Python's \s also covers the file/group separators 0x1C..0x1F.
        assertEquals(expected, MimeBuilder.formatSender("+91" + ch(0x1C) + "98765" + ch(0x1C) + "43210"))
    }

    // NEGATIVE: names that are NOT phone numbers stay names.
    @Test
    fun nonPhoneNamesAreNotRewrittenAsPhoneNumbers() {
        assertEquals("123456 <whatsapp-sync@local>", MimeBuilder.formatSender("123456"))
        assertEquals("1234567890123456 <whatsapp-sync@local>", MimeBuilder.formatSender("1234567890123456"))
        assertFalse(MimeBuilder.formatSender("Meera 98765 43210").startsWith("+"))
    }
}
