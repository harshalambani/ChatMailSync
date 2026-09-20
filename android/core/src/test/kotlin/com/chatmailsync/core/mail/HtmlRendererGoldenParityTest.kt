package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.util.Base64

/**
 * Cross-language parity test for [HtmlRenderer] against the *real* Python
 * `src.html_renderer.render_chunk`.
 *
 * `tools/generate_kotlin_core_golden_fixtures.py`'s
 * `generate_html_renderer_golden` runs the real Python renderer over a sweep
 * of chunks -- HTML escaping, unicode/emoji/RTL text, day-pill/time
 * formatting, every inline-embeddable image type, every non-image
 * attachment icon category, a missing media file, an oversized media file
 * both with and without a byte cap, sender-colour determinism across a
 * group chat, the outgoing/incoming self-sender split, and the empty/
 * one-message edge cases -- and records the result as
 * `html_renderer_golden.json`. This test rebuilds the exact same fixtures
 * in Kotlin (same case list, same message text, same media files/bytes --
 * mirrored 1:1 from that generator function) and asserts [HtmlRenderer]
 * produces the same rendered HTML/parts/sizes for every case.
 *
 * The one genuinely non-deterministic value on both sides -- the inline
 * image `cid` -- is normalized to `img-NORMALIZEDCID<n>` placeholders, in
 * order of first appearance, exactly as the Python generator does; see
 * [normalizeCids] and that generator's own `_normalize_cids` docstring.
 *
 * There is no equivalent standalone Python test for this comparison (it is
 * inherently cross-language, like [MimeGoldenParityTest]/
 * [MediaExtractorGoldenParityTest]); this file is new, not a port of an
 * existing Python test.
 */
class HtmlRendererGoldenParityTest {

    private fun readGoldenResource(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("golden/$name")
            ?: error("golden resource not found on test classpath: golden/$name")
        return stream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    private fun createTempDirLocal(): File {
        val dir = File.createTempFile("htmlrenderertest", "dir")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    // -----------------------------------------------------------------
    // Media fixture -- mirrors generate_html_renderer_golden's media_files
    // list byte-for-byte.
    // -----------------------------------------------------------------

    private fun buildMediaExtractor(dir: File): MediaExtractor {
        val sourcePath = File(dir, "_chat.txt")
        sourcePath.writeText("html renderer golden source placeholder\n", Charsets.UTF_8)

        val mediaFiles = listOf<Pair<String, ByteArray>>(
            "photo.jpg" to (byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + "jpeg-bytes-for-html-renderer-golden".toByteArray(Charsets.UTF_8)),
            "icon.png" to (byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) + "png-bytes-for-html-renderer-golden".toByteArray(Charsets.UTF_8)),
            "clip.gif" to ("GIF89a".toByteArray(Charsets.UTF_8) + "gif-bytes-for-html-renderer-golden".toByteArray(Charsets.UTF_8)),
            "sticker.webp" to ("RIFF".toByteArray(Charsets.UTF_8) + "webp-bytes-for-html-renderer-golden".toByteArray(Charsets.UTF_8)),
            "sketch.bmp" to ("BM".toByteArray(Charsets.UTF_8) + "bmp-bytes-for-html-renderer-golden".toByteArray(Charsets.UTF_8)),
            "clip.mp4" to "video-bytes-for-html-renderer-golden".toByteArray(Charsets.UTF_8),
            "voice.opus" to "audio-bytes-for-html-renderer-golden".toByteArray(Charsets.UTF_8),
            "doc.pdf" to ("%PDF-1.4 ".toByteArray(Charsets.UTF_8) + "pdf-bytes-for-html-renderer-golden".toByteArray(Charsets.UTF_8)),
            "note.txt" to "text-bytes-for-html-renderer-golden".toByteArray(Charsets.UTF_8),
            "unknown.xyz" to "unmapped-extension-bytes-for-html-renderer-golden".toByteArray(Charsets.UTF_8),
            "bigdoc.pdf" to ("%PDF-1.4 ".toByteArray(Charsets.UTF_8) + ByteArray(1_048_600) { 'z'.code.toByte() }),
            "huge.jpg" to (byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(200_000) { 'z'.code.toByte() }),
        )
        for ((name, data) in mediaFiles) {
            File(dir, name).writeBytes(data)
        }
        return MediaExtractor(sourcePath)
    }

    // -----------------------------------------------------------------
    // Case fixtures -- mirror generate_html_renderer_golden's `cases` list
    // entry-for-entry.
    // -----------------------------------------------------------------

    private fun ts(day: Int, hour: Int, minute: Int): LocalDateTime =
        LocalDateTime.of(2025, 5, day, hour, minute, 0)

    private fun msg(
        sender: String,
        body: String,
        time: LocalDateTime,
        attachmentFilename: String? = null,
    ): ParsedMessage = ParsedMessage(
        chatId = "html_renderer_golden_chat",
        timestamp = time,
        sender = sender,
        body = body,
        attachmentFilename = attachmentFilename,
    )

    private data class CaseFixture(
        val name: String,
        val messages: List<ParsedMessage>,
        val displayName: String = "Meera Iyer",
        val label: String = "",
        val maxMediaBytes: Long? = null,
        val selfSender: String? = null,
        val useExtractor: Boolean = false,
    )

    private fun caseFixtures(): List<CaseFixture> = listOf(
        CaseFixture(
            name = "empty_chat",
            messages = emptyList(),
        ),
        CaseFixture(
            name = "one_message_chat",
            messages = listOf(msg("Meera Iyer", "Hello there, just checking in.", ts(3, 9, 41))),
            displayName = "Meera Iyer",
        ),
        CaseFixture(
            name = "html_escaping_in_name_and_body",
            messages = listOf(
                msg(
                    "Rohan \"R\" <Mehta>",
                    "<b>bold</b> & \"quoted\" 'single' <script>alert(1)</script>",
                    ts(3, 9, 42),
                ),
            ),
            displayName = "Rohan Mehta",
        ),
        CaseFixture(
            name = "url_as_plain_text_no_autolink",
            messages = listOf(
                msg(
                    "Priya Nair",
                    "Check this out: https://example.com/path?x=1&y=2 nice right?",
                    ts(3, 9, 43),
                ),
            ),
            displayName = "Priya Nair",
        ),
        CaseFixture(
            name = "unicode_emoji_rtl_text",
            messages = listOf(
                msg(
                    "Kavya Menon",
                    "मिलते हैं 🎉😊 " +
                        "مرحبا بالعالم " +
                        "शुक्रिया",
                    ts(3, 9, 44),
                ),
            ),
            displayName = "Kavya Menon",
        ),
        CaseFixture(
            name = "newlines_and_long_message",
            messages = listOf(
                msg(
                    "Meera Iyer",
                    "line one\nline two\nline three\n\n" +
                        "This is a long message. ".repeat(40).trim(),
                    ts(3, 9, 45),
                ),
            ),
            displayName = "Meera Iyer",
        ),
        CaseFixture(
            name = "system_and_deleted_style_messages",
            messages = listOf(
                msg(
                    "Rohan Mehta",
                    "Messages and calls are end-to-end encrypted. " +
                        "No one outside of this chat, not even WhatsApp, can read or listen to them.",
                    ts(3, 9, 40),
                ),
                msg("Meera Iyer", "This message was deleted", ts(3, 9, 46)),
            ),
            displayName = "Meera Iyer",
        ),
        CaseFixture(
            name = "inline_images_all_embeddable_types",
            messages = listOf(
                msg("Meera Iyer", "photo.jpg (file attached)", ts(3, 10, 1), "photo.jpg"),
                msg("Meera Iyer", "icon.png (file attached)", ts(3, 10, 2), "icon.png"),
                msg("Meera Iyer", "clip.gif (file attached)", ts(3, 10, 3), "clip.gif"),
                msg("Meera Iyer", "sticker.webp (file attached)", ts(3, 10, 4), "sticker.webp"),
                msg("Meera Iyer", "sketch.bmp (file attached)", ts(3, 10, 5), "sketch.bmp"),
            ),
            displayName = "Meera Iyer",
            useExtractor = true,
        ),
        CaseFixture(
            name = "non_image_attachments_all_icon_categories",
            messages = listOf(
                msg("Rohan Mehta", "clip.mp4 (file attached)", ts(3, 11, 1), "clip.mp4"),
                msg("Rohan Mehta", "voice.opus (file attached)", ts(3, 11, 2), "voice.opus"),
                msg("Rohan Mehta", "doc.pdf (file attached)", ts(3, 11, 3), "doc.pdf"),
                msg("Rohan Mehta", "note.txt (file attached)", ts(3, 11, 4), "note.txt"),
                msg("Rohan Mehta", "unknown.xyz (file attached)", ts(3, 11, 5), "unknown.xyz"),
                msg("Rohan Mehta", "bigdoc.pdf (file attached)", ts(3, 11, 6), "bigdoc.pdf"),
            ),
            displayName = "Rohan Mehta",
            useExtractor = true,
        ),
        CaseFixture(
            name = "missing_media_file",
            messages = listOf(msg("Priya Nair", "ghost.jpg (file attached)", ts(3, 12, 0), "ghost.jpg")),
            displayName = "Priya Nair",
            useExtractor = true,
        ),
        CaseFixture(
            name = "oversized_media_omitted_with_cap",
            messages = listOf(msg("Kavya Menon", "huge.jpg (file attached)", ts(3, 12, 30), "huge.jpg")),
            displayName = "Kavya Menon",
            useExtractor = true,
            maxMediaBytes = 100_000,
        ),
        CaseFixture(
            name = "large_media_not_omitted_without_cap",
            messages = listOf(msg("Kavya Menon", "huge.jpg (file attached)", ts(3, 12, 45), "huge.jpg")),
            displayName = "Kavya Menon",
            useExtractor = true,
        ),
        CaseFixture(
            name = "group_chat_sender_colors",
            messages = listOf(
                msg("Meera Iyer", "hi everyone", ts(3, 13, 1)),
                msg("Rohan Mehta", "hey!", ts(3, 13, 2)),
                msg("Priya Nair", "hello all", ts(3, 13, 3)),
                msg("Kavya Menon", "good morning", ts(3, 13, 4)),
            ),
            displayName = "Group Chat",
        ),
        CaseFixture(
            name = "outgoing_vs_incoming_with_self_sender",
            messages = listOf(
                msg("Meera Iyer", "outgoing message text", ts(3, 14, 1)),
                msg("Rohan Mehta", "incoming message text", ts(3, 14, 2)),
            ),
            displayName = "Rohan Mehta",
            selfSender = "Meera Iyer",
        ),
        CaseFixture(
            name = "outgoing_default_you_fallback",
            messages = listOf(
                msg("You", "outgoing via literal You fallback", ts(3, 14, 30)),
                msg("Priya Nair", "incoming reply", ts(3, 14, 31)),
            ),
            displayName = "Priya Nair",
        ),
        CaseFixture(
            name = "date_pill_with_label_suffix",
            messages = listOf(msg("Meera Iyer", "part two of three", ts(3, 15, 0))),
            displayName = "Meera Iyer",
            label = "Part 2/3",
        ),
        CaseFixture(
            name = "attachment_marker_only_produces_no_body_div",
            messages = listOf(msg("Meera Iyer", "(file attached)", ts(3, 15, 30), "photo.jpg")),
            displayName = "Meera Iyer",
            useExtractor = true,
        ),
        CaseFixture(
            name = "attachment_with_extra_text_keeps_body_div",
            messages = listOf(msg("Meera Iyer", "photo.jpg (file attached)", ts(3, 15, 35), "photo.jpg")),
            displayName = "Meera Iyer",
            useExtractor = true,
        ),
        CaseFixture(
            name = "ios_attachment_marker_only_produces_no_body_div",
            messages = listOf(msg("Kavya Menon", "<attached: icon.png>", ts(3, 15, 45), "icon.png")),
            displayName = "Kavya Menon",
            useExtractor = true,
        ),
        CaseFixture(
            name = "day_pill_leading_zero_stripped_various_days",
            messages = listOf(msg("Meera Iyer", "first of the month", ts(1, 8, 5))),
            displayName = "Meera Iyer",
        ),
    )

    // -----------------------------------------------------------------
    // Cid normalization -- twin of the Python generator's _normalize_cids.
    // -----------------------------------------------------------------

    private val cidRegex = Regex("img-[0-9a-f]{12}")

    private fun normalizeCids(htmlBody: String, cids: List<String>): Pair<String, List<String>> {
        val mapping = linkedMapOf<String, String>()
        var counter = 0
        val normalizedHtml = cidRegex.replace(htmlBody) { match ->
            mapping.getOrPut(match.value) {
                counter++
                "img-NORMALIZEDCID$counter"
            }
        }
        val normalizedCids = cids.map { mapping[it] ?: it }
        return normalizedHtml to normalizedCids
    }

    // -----------------------------------------------------------------
    // Golden case comparison
    // -----------------------------------------------------------------

    private data class GoldenCase(
        val htmlBody: String,
        val inlinePartsCids: List<String>,
        val inlinePartsMimeTypes: List<String>,
        val inlinePartsDataBase64: List<String>,
        val attachmentsFilenames: List<String>,
        val attachmentsMimeTypes: List<String>,
        val attachmentsDataBase64: List<String>,
        val totalBytes: Long,
        val wireBytes: Long,
        val omissions: List<Triple<String, Long, Long>>,
    )

    private fun parseGoldenCases(): Map<String, GoldenCase> {
        val root = parseJson(readGoldenResource("html_renderer_golden.json")).asObj()
        val cases = root["cases"].asArr().items.map { it.asObj() }
        return cases.associate { obj ->
            val name = obj["name"].asString()
            name to GoldenCase(
                htmlBody = obj["htmlBody"].asString(),
                inlinePartsCids = obj["inlinePartsCids"].asArr().items.map { it.asString() },
                inlinePartsMimeTypes = obj["inlinePartsMimeTypes"].asArr().items.map { it.asString() },
                inlinePartsDataBase64 = obj["inlinePartsDataBase64"].asArr().items.map { it.asString() },
                attachmentsFilenames = obj["attachmentsFilenames"].asArr().items.map { it.asString() },
                attachmentsMimeTypes = obj["attachmentsMimeTypes"].asArr().items.map { it.asString() },
                attachmentsDataBase64 = obj["attachmentsDataBase64"].asArr().items.map { it.asString() },
                totalBytes = obj["totalBytes"].asInt().toLong(),
                wireBytes = obj["wireBytes"].asInt().toLong(),
                omissions = obj["omissions"].asArr().items.map { it.asObj() }.map { o ->
                    Triple(o["filename"].asString(), o["sizeBytes"].asInt().toLong(), o["limitBytes"].asInt().toLong())
                },
            )
        }
    }

    @Test
    fun everyGoldenCaseMatchesPythonByteForByte() {
        val golden = parseGoldenCases()
        val fixtures = caseFixtures()
        assertTrue("expected a non-trivial case sweep", fixtures.size >= 15)
        assertEquals("fixture/golden case-name sets must match exactly", golden.keys, fixtures.map { it.name }.toSet())

        val dir = createTempDirLocal()
        val extractor = buildMediaExtractor(dir)
        val mismatches = mutableListOf<String>()

        try {
            for (fixture in fixtures) {
                val expected = golden.getValue(fixture.name)
                val rendered = HtmlRenderer.renderChunk(
                    messages = fixture.messages,
                    displayName = fixture.displayName,
                    extractor = if (fixture.useExtractor) extractor else null,
                    label = fixture.label,
                    maxMediaBytes = fixture.maxMediaBytes,
                    selfSender = fixture.selfSender,
                )

                val (normalizedHtml, normalizedCids) = normalizeCids(
                    rendered.htmlBody,
                    rendered.inlineParts.map { it.cid },
                )

                if (normalizedHtml != expected.htmlBody) {
                    mismatches.add("case=${fixture.name}: htmlBody differs")
                }
                if (normalizedCids != expected.inlinePartsCids) {
                    mismatches.add("case=${fixture.name}: inlinePartsCids differ: $normalizedCids vs ${expected.inlinePartsCids}")
                }
                val actualInlineMime = rendered.inlineParts.map { it.mimeType }
                if (actualInlineMime != expected.inlinePartsMimeTypes) {
                    mismatches.add("case=${fixture.name}: inline mimeTypes differ")
                }
                val actualInlineData = rendered.inlineParts.map { Base64.getEncoder().encodeToString(it.data) }
                if (actualInlineData != expected.inlinePartsDataBase64) {
                    mismatches.add("case=${fixture.name}: inline part bytes differ")
                }
                val actualAttFilenames = rendered.attachments.map { it.filename }
                if (actualAttFilenames != expected.attachmentsFilenames) {
                    mismatches.add("case=${fixture.name}: attachment filenames differ")
                }
                val actualAttMime = rendered.attachments.map { it.mimeType }
                if (actualAttMime != expected.attachmentsMimeTypes) {
                    mismatches.add("case=${fixture.name}: attachment mimeTypes differ")
                }
                val actualAttData = rendered.attachments.map { Base64.getEncoder().encodeToString(it.data) }
                if (actualAttData != expected.attachmentsDataBase64) {
                    mismatches.add("case=${fixture.name}: attachment bytes differ")
                }
                if (rendered.totalBytes != expected.totalBytes) {
                    mismatches.add("case=${fixture.name}: totalBytes python=${expected.totalBytes} kotlin=${rendered.totalBytes}")
                }
                if (rendered.wireBytes != expected.wireBytes) {
                    mismatches.add("case=${fixture.name}: wireBytes python=${expected.wireBytes} kotlin=${rendered.wireBytes}")
                }
                val actualOmissions = rendered.omissions.map { Triple(it.filename, it.sizeBytes, it.limitBytes) }
                if (actualOmissions != expected.omissions) {
                    mismatches.add("case=${fixture.name}: omissions differ: $actualOmissions vs ${expected.omissions}")
                }
            }
        } finally {
            extractor.close()
        }

        if (mismatches.isNotEmpty()) {
            fail("HtmlRenderer diverged from Python on ${mismatches.size} case(s):\n${mismatches.joinToString("\n")}")
        }
    }

    // -----------------------------------------------------------------
    // Negative tests -- assert the wrong behaviour does NOT happen.
    // -----------------------------------------------------------------

    /**
     * A sender name or message body containing HTML markup must never reach
     * the output unescaped -- the raw `<script>` tag must be absent and only
     * its escaped form must appear, both for the name and the body.
     */
    @Test
    fun markupInNameOrBodyIsNeverEmittedUnescaped() {
        val messages = listOf(
            msg(
                "Rohan \"R\" <Mehta>",
                "<b>bold</b> & \"quoted\" 'single' <script>alert(1)</script>",
                ts(3, 9, 42),
            ),
        )
        val rendered = HtmlRenderer.renderChunk(messages, "Rohan Mehta", extractor = null)

        assertFalse("raw <script> tag must never appear", rendered.htmlBody.contains("<script>"))
        assertFalse("raw <b> tag must never appear", rendered.htmlBody.contains("<b>bold</b>"))
        assertFalse("raw <Mehta> must never appear unescaped in the sender name", rendered.htmlBody.contains("<Mehta>"))
        assertTrue("escaped script tag must appear", rendered.htmlBody.contains("&lt;script&gt;"))
        assertTrue("escaped ampersand must appear", rendered.htmlBody.contains("&amp;"))
        assertTrue("escaped double quote must appear", rendered.htmlBody.contains("&quot;quoted&quot;"))
        assertTrue("escaped single quote must appear", rendered.htmlBody.contains("&#x27;single&#x27;"))
    }

    /**
     * A missing media file must never leak the extractor's real on-disk
     * source path (absolute or otherwise) into the rendered HTML -- only
     * the export-relative filename the message itself referenced, inside
     * the fixed "not found in export" placeholder text.
     */
    @Test
    fun missingMediaFileNeverLeaksARealFilesystemPath() {
        val dir = createTempDirLocal()
        val extractor = buildMediaExtractor(dir)
        try {
            val messages = listOf(msg("Priya Nair", "ghost.jpg (file attached)", ts(3, 12, 0), "ghost.jpg"))
            val rendered = HtmlRenderer.renderChunk(messages, "Priya Nair", extractor)

            assertTrue(
                "must show the fixed not-found placeholder",
                rendered.htmlBody.contains("not found in export"),
            )
            assertFalse(
                "must never leak the real temp directory's absolute path",
                rendered.htmlBody.contains(dir.absolutePath),
            )
            assertFalse("must never contain a Windows drive-letter path", rendered.htmlBody.contains(":\\"))
            assertFalse("must never contain a bare rooted unix path", rendered.htmlBody.contains(" /tmp"))
            assertTrue("only the referenced filename may appear", rendered.htmlBody.contains("ghost.jpg"))
        } finally {
            extractor.close()
        }
    }

    /**
     * `extractor == null` must also never invent or leak any filesystem
     * path -- only the escaped filename inside the fixed placeholder.
     */
    @Test
    fun nullExtractorNeverLeaksAFilesystemPath() {
        val messages = listOf(msg("Priya Nair", "ghost.jpg (file attached)", ts(3, 12, 0), "ghost.jpg"))
        val rendered = HtmlRenderer.renderChunk(messages, "Priya Nair", extractor = null)

        assertFalse(rendered.htmlBody.contains(":\\"))
        assertFalse(rendered.htmlBody.contains(" /tmp"))
        assertTrue(rendered.htmlBody.contains("ghost.jpg"))
    }
}
