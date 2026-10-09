package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.util.Base64

/**
 * Byte parity of [HtmlMimeBuilder] against the real Python 3.13
 * `_build_html_mime_message`, plus the negative tests for header handling.
 *
 * Expected messages live in `golden/html_mime_golden.json`, written by
 * `tools/generate_kotlin_core_golden_fixtures.py`. The golden pins the
 * rendered input (html, inline parts, attachments) as data, so this is a pure
 * builder check; the renderer has its own golden. Boundaries are random in
 * both languages, so both sides are normalised in order of first appearance.
 */
class HtmlMimeGoldenParityTest {

    private class Case(
        val name: String,
        val displayName: String,
        val chatId: String,
        val messageId: String,
        val chunkSize: ChunkSize,
        val suffix: String,
        val inReplyTo: String?,
        val references: String?,
        val messages: List<ParsedMessage>,
        val rendered: HtmlRenderer.RenderedChunk,
        val eml: String,
    )

    private fun readGolden(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("golden/$name")
            ?: error("golden resource not found on test classpath: golden/$name")
        return stream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    private val root: JsonNode by lazy { parseJson(readGolden("html_mime_golden.json")) }

    private fun chunkSizeFrom(node: JsonNode): ChunkSize = when (node) {
        is JsonNode.Str -> when (node.value) {
            "day" -> ChunkSize.Day
            "hour" -> ChunkSize.Hour
            "week" -> ChunkSize.Week
            else -> error("unknown chunk size string: ${node.value}")
        }
        is JsonNode.Num -> ChunkSize.Count(node.value.toInt())
        else -> error("chunk size must be a string or number: $node")
    }

    private val cases: List<Case> by lazy {
        root["cases"].asArr().items.map { it.asObj() }.map { obj ->
            val chatId = obj["chatId"].asString()
            Case(
                name = obj["name"].asString(),
                displayName = obj["displayName"].asString(),
                chatId = chatId,
                messageId = obj["messageId"].asString(),
                chunkSize = chunkSizeFrom(obj["chunkSize"]),
                suffix = obj["suffix"].asString(),
                inReplyTo = obj["inReplyTo"].asStringOrNull(),
                references = obj["references"].asStringOrNull(),
                messages = obj["messages"].asArr().items.map { it.asObj() }.map { m ->
                    ParsedMessage(
                        chatId = chatId,
                        timestamp = LocalDateTime.parse(m["ts"].asString()),
                        sender = m["sender"].asString(),
                        body = m["body"].asString(),
                    )
                },
                rendered = HtmlRenderer.RenderedChunk(
                    htmlBody = obj["htmlBody"].asString(),
                    inlineParts = obj["inline"].asArr().items.map { it.asObj() }.map {
                        HtmlRenderer.InlinePart(
                            it["cid"].asString(),
                            Base64.getDecoder().decode(it["dataBase64"].asString()),
                            it["mimeType"].asString(),
                        )
                    },
                    attachments = obj["attachments"].asArr().items.map { it.asObj() }.map {
                        HtmlRenderer.AttachmentPart(
                            it["filename"].asString(),
                            Base64.getDecoder().decode(it["dataBase64"].asString()),
                            it["mimeType"].asString(),
                        )
                    },
                ),
                eml = obj["eml"].asString(),
            )
        }
    }

    private val KNOWN_DIVERGENCES = listOf("html_filename_nul_ascii", "html_filename_del_us_ascii")

    private val boundaryRegex = Regex("={15}\\d+==")

    /** Same ordered normalisation as the generator: first boundary seen is 1, the next is 2. */
    private fun normalise(text: String): String {
        val seen = LinkedHashMap<String, String>()
        return boundaryRegex.replace(text) { m ->
            seen.getOrPut(m.value) { "===============NORMALIZED${seen.size + 1}==" }
        }
    }

    private fun buildCase(c: Case): String {
        val bytes = HtmlMimeBuilder.buildRaw(
            displayName = c.displayName,
            chunk = c.messages,
            chunkSize = c.chunkSize,
            rendered = c.rendered,
            messageId = c.messageId,
            suffix = c.suffix,
            inReplyTo = c.inReplyTo,
            references = c.references,
        )
        return normalise(String(bytes, Charsets.UTF_8))
    }

    private fun simpleChunk(chatId: String = "test_chat") = listOf(
        ParsedMessage(chatId, LocalDateTime.of(2019, 5, 3, 10, 15, 0), "Meera Iyer", "hello"),
    )

    private fun buildWith(
        displayName: String = "Meera Iyer",
        chatId: String = "test_chat",
        rendered: HtmlRenderer.RenderedChunk = HtmlRenderer.RenderedChunk("<p>x</p>"),
    ): String = String(
        HtmlMimeBuilder.buildRaw(
            displayName, simpleChunk(chatId), ChunkSize.Day, rendered, "<id@local>",
        ),
        Charsets.UTF_8,
    )

    private fun headerBlock(eml: String) = eml.substringBefore("\n\n")

    // ------------------------------------------------------------------
    // Parity
    // ------------------------------------------------------------------

    @Test
    fun everyCaseMatchesPythonByteForByte() {
        assertTrue("expected the full set, got ${cases.size} cases", cases.size >= 30)
        val mismatches = cases.filter { it.name !in KNOWN_DIVERGENCES && buildCase(it) != it.eml }.map { it.name }
        assertTrue("cases that differ from Python: $mismatches", mismatches.isEmpty())
    }

    /**
     * Python 3.13 writes NUL, FS-less bare controls (0x1f, 0x7f) raw into the
     * Content-Disposition header. Kotlin writes them RFC 2231 encoded instead,
     * so nothing raw travels in a header. Asserted here so the difference
     * cannot become accidental.
     */
    @Test
    fun bareControlsInAFilenameAreEncodedWhereDifferingFromPython() {
        for (name in KNOWN_DIVERGENCES) {
            val c = cases.first { it.name == name }
            val ours = buildCase(c)
            assertTrue("$name: Python wrote it raw, so the golden must differ", ours != c.eml)
            assertTrue("$name: expected RFC 2231 form", ours.contains("filename*=utf-8''"))
            assertFalse("$name: no raw NUL", ours.contains('\u0000'))
            assertFalse("$name: no raw US", ours.contains('\u001f'))
            assertFalse("$name: no raw DEL", ours.contains('\u007f'))
        }
    }

    @Test
    fun partSuffixLandsInTheSubjectOnly() {
        val c = cases.first { it.name == "html_part_2_of_3" }
        val eml = buildCase(c)
        assertEquals(eml, c.eml)
        assertTrue(headerBlock(eml).contains("Subject:"))
        assertFalse("suffix must not leak into the index", eml.substringAfter("application/json").contains("Part 2/3"))
    }

    @Test
    fun messageHasMixedRelatedThenAttachmentsThenIndexLast() {
        val c = cases.first { it.name == "html_two_inline_images_and_pdf" }
        val eml = buildCase(c)
        val order = Regex("^Content-Type: ([^;\\n]+)", RegexOption.MULTILINE).findAll(eml).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "multipart/mixed", "multipart/related", "text/html", "image/png", "image/jpeg",
                "application/pdf", "application/json",
            ),
            order,
        )
    }

    // ------------------------------------------------------------------
    // Negative tests: header handling
    // ------------------------------------------------------------------

    @Test
    fun crLfInAChatNameGivesOneSubjectOneFromAndNoInjectedHeaders() {
        val names = listOf(
            "Meera\r\nBcc: attacker@example.com\r\nIyer",
            "Meera\nReply-To: attacker@example.com\nIyer",
            "Meera\rBcc: attacker@example.com",
            "Meera\u0000Iyer\r\nX-Evil: 1",
            "मीरा\r\nBcc: attacker@example.com",
        )
        for (name in names) {
            val eml = buildWith(displayName = name)
            val head = headerBlock(eml).split("\n")
            assertEquals("$name: Subject lines", 1, head.count { it.startsWith("Subject:") })
            assertEquals("$name: From lines", 1, head.count { it.startsWith("From:") })
            for (bad in listOf("Bcc:", "Reply-To:", "X-Evil:", "Cc:")) {
                assertFalse("$name: injected $bad", eml.split("\n").any { it.startsWith(bad) })
            }
            assertFalse("a raw CR must never reach the message", eml.contains('\r'))
        }
    }

    @Test
    fun crLfInAChatIdGivesOneChatHeaderAndNoInjectedHeaders() {
        val eml = buildWith(chatId = "chat\r\nBcc: attacker@example.com")
        val head = headerBlock(eml).split("\n")
        assertEquals(1, head.count { it.startsWith("$HEADER_CHAT:") })
        assertFalse(eml.split("\n").any { it.startsWith("Bcc:") })
    }

    @Test
    fun asciiFilenameWithALineBreakIsRefusedAndNeverWritten() {
        val refused = root["refusedFilenames"].asArr().items.map { it.asString() }
        assertTrue("golden must list the refused names", refused.size >= 8)
        for (fname in refused) {
            val rendered = HtmlRenderer.RenderedChunk(
                "<p>x</p>",
                attachments = listOf(HtmlRenderer.AttachmentPart(fname, byteArrayOf(1), "application/pdf")),
            )
            try {
                buildWith(rendered = rendered)
                fail("filename ${fname.length} chars with a control character was written")
            } catch (e: UnsafeHeaderValueException) {
                assertFalse("the refusal must not echo the filename", e.message!!.contains("Bcc"))
                assertFalse(e.message!!.contains("b.txt"))
            }
        }
    }

    @Test
    fun nonAsciiFilenameWithALineBreakIsPercentEncodedNotInjected() {
        val fname = "मीरा\r\nBcc: attacker@example.com\r\n.pdf"
        val rendered = HtmlRenderer.RenderedChunk(
            "<p>x</p>",
            attachments = listOf(HtmlRenderer.AttachmentPart(fname, byteArrayOf(1, 2, 3), "application/pdf")),
        )
        val eml = buildWith(rendered = rendered)
        assertFalse(eml.contains('\r'))
        assertFalse("no extra header line", eml.split("\n").any { it.startsWith("Bcc:") })
        assertTrue(eml.contains("%0D%0A"))
        // root: related + attachment + index = three parts at the mixed level.
        val boundary = Regex("boundary=\"(={15}\\d+==)\"").find(eml)!!.groupValues[1]
        val opens = eml.split("\n").count { it == "--$boundary" }
        assertEquals("exactly related + one attachment + index", 3, opens)
    }

    @Test
    fun filenameCannotAddAMimePart() {
        val boundaryAttack = "x\n--BOUNDARY\nContent-Type: text/plain\n\nowned"
        val rendered = HtmlRenderer.RenderedChunk(
            "<p>x</p>",
            attachments = listOf(HtmlRenderer.AttachmentPart(boundaryAttack, byteArrayOf(1), "application/pdf")),
        )
        try {
            buildWith(rendered = rendered)
            fail("a filename with a line break was written")
        } catch (_: UnsafeHeaderValueException) {
            // refused: nothing was assembled
        }
    }

    @Test
    fun anInlineIdOrMimeTypeThatIsNotAPlainTokenIsRefused() {
        for (cid in listOf("", "a b", "a\r\nBcc: x", "a>b", "<a")) {
            val rendered = HtmlRenderer.RenderedChunk(
                "<p>x</p>",
                inlineParts = listOf(HtmlRenderer.InlinePart(cid, byteArrayOf(1), "image/png")),
            )
            try {
                buildWith(rendered = rendered)
                fail("cid accepted")
            } catch (_: UnsafeHeaderValueException) {
            }
        }
        for (mime in listOf("image/png\r\nBcc: x", "nonsense", "a/b c", "")) {
            val rendered = HtmlRenderer.RenderedChunk(
                "<p>x</p>",
                attachments = listOf(HtmlRenderer.AttachmentPart("a.bin", byteArrayOf(1), mime)),
            )
            try {
                buildWith(rendered = rendered)
                fail("mime type accepted")
            } catch (_: UnsafeHeaderValueException) {
            }
        }
    }

    @Test
    fun emptyAttachmentWritesNoBlankPayloadLine() {
        val c = cases.first { it.name == "html_empty_attachment" }
        assertEquals(c.eml, buildCase(c))
    }

    @Test
    fun noReferencesWhenThereIsNoInReplyTo() {
        val c = cases.first { it.name == "html_reply_unset" }
        val eml = buildCase(c)
        assertFalse(eml.split("\n").any { it.startsWith("References:") || it.startsWith("In-Reply-To:") })
    }
}
