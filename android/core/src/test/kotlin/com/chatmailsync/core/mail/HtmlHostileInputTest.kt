package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * HTML guard: every piece of text that comes from a chat export (message body, sender name, chat
 * display name, attachment file name, part label) is untrusted. In the generated HTML mail it must
 * stay inert text: no tag, no attribute, no link, no script. In the headers it must not start a new
 * line or inject a header. Names here are placeholders.
 */
class HtmlHostileInputTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Each entry carries "/"-free variants too, because a file name is split on "/" when it is resolved.
    private val hostile = listOf(
        "<script>alert(1)</script>",
        "<img src=x onerror=alert(1)>",
        "\"><svg onload=alert(1)>",
        "javascript:alert(1)",
        "<a href=\"javascript:alert(1)\">click</a>",
        "</div></div><div onclick=alert(1)>",
        "\" style=\"background:url(javascript:alert(1))",
        "' onmouseover='alert(1)",
        "x\" alt=\"y\" onerror=\"alert(1)",
        "&lt;script&gt; &amp;",
    )

    private val allowedTags = setOf("!DOCTYPE", "html", "head", "meta", "body", "div", "span", "img")
    private val allowedAttrs = setOf("style", "src", "alt", "charset", "name", "content")

    private val ts = LocalDateTime.of(2026, 1, 2, 3, 4, 5)

    // ---- the checker ----

    /** Everything wrong with [html] as markup, empty when it is exactly the app's own structure. */
    private fun violations(html: String): List<String> {
        val bad = mutableListOf<String>()
        val tagRe = Regex("<([^<>]*)>")
        val tags = tagRe.findAll(html).toList()
        for (m in tags) {
            val inner = m.groupValues[1]
            val name = Regex("^/?([A-Za-z!][A-Za-z0-9]*)").find(inner)?.groupValues?.get(1)
            if (name == null || name !in allowedTags) { bad.add("unexpected tag <$inner>"); continue }
            val attrsText = inner.removePrefix("/").removePrefix(name).trim()
            val attrRe = Regex("""([a-z-]+)="([^"<>]*)"""")
            var rest = attrsText
            if (name == "!DOCTYPE") continue
            for (a in attrRe.findAll(attrsText)) {
                if (a.groupValues[1] !in allowedAttrs) bad.add("unexpected attribute ${a.groupValues[1]} in <$name>")
                if (a.groupValues[1] == "src" && !a.groupValues[2].startsWith("cid:")) bad.add("src not a cid: ${a.groupValues[2]}")
                if (a.groupValues[2].contains("javascript:", ignoreCase = true) && a.groupValues[1] != "alt") bad.add("script URL in ${a.groupValues[1]}")
                rest = rest.replace(a.value, "")
            }
            if (rest.trim().isNotEmpty()) bad.add("stray text inside <$name ...>: '${rest.trim()}'")
        }
        // Whatever is left between the tags is text and must hold no angle bracket at all.
        val text = tagRe.replace(html, "")
        if (text.contains('<') || text.contains('>')) bad.add("raw angle bracket in text")
        // Attribute values hold no raw quote that could end them early (a stray quote splits an attribute).
        for (m in tags) if (m.groupValues[1].count { it == '"' } % 2 != 0) bad.add("odd quote count in <${m.groupValues[1]}>")
        if (Regex("<a[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(html)) bad.add("a link")
        if (html.contains("<script", ignoreCase = true)) bad.add("a script element")
        return bad
    }

    private fun msg(sender: String, body: String, attachment: String? = null) =
        ParsedMessage("chat-1", ts, sender, body, attachment)

    // ---- the checker must actually bite ----

    @Test
    fun theCheckerCatchesUnescapedInput() {
        assertTrue(violations("<div style=\"a\"><img src=x onerror=alert(1)></div>").isNotEmpty())
        assertTrue(violations("<div style=\"a\"><a href=\"javascript:x\">k</a></div>").isNotEmpty())
        assertTrue(violations("<div style=\"a\"><script>x</script></div>").isNotEmpty())
        assertTrue(violations("<div style=\"a\">a < b</div>").isNotEmpty())
        assertEquals(emptyList<String>(), violations("<div style=\"a\"><span style=\"b\">fine &lt;b&gt;</span></div>"))
    }

    // ---- HTML body ----

    @Test
    fun hostileBodySenderAndLabelStayInertTextWithoutMedia() {
        for (h in hostile) {
            val chunk = HtmlRenderer.renderChunk(
                messages = listOf(msg(sender = h, body = h), msg(sender = "Meera Iyer", body = h)),
                displayName = h,
                extractor = null,
                label = h,
                selfSender = null,
            )
            assertEquals("for [$h]", emptyList<String>(), violations(chunk.htmlBody))
            // The escaped form IS present, so the text is shown, not dropped.
            assertTrue(chunk.htmlBody.contains(HtmlRenderer.escapeHtml(h)))
        }
    }

    @Test
    fun hostileAttachmentNameIsInertInPlaceholderCardAndImageAlt() {
        val zip = File(tmp.root, "export.zip")
        // The names a hostile export could reference. "/" splits a name when it is resolved, so the zip holds the tail.
        val names = hostile.flatMap { listOf("$it.pdf", "$it.png") }
        ZipOutputStream(zip.outputStream()).use { z ->
            for (n in names) {
                z.putNextEntry(ZipEntry(n.substringAfterLast('/')))
                z.write(ByteArray(2048) { 7 })
                z.closeEntry()
            }
        }
        MediaExtractor(zip).use { ex ->
            var cards = 0
            var images = 0
            for (n in names) {
                val chunk = HtmlRenderer.renderChunk(
                    messages = listOf(msg("Meera Iyer", "see attached", attachment = n)),
                    displayName = "Meera Iyer",
                    extractor = ex,
                )
                assertEquals("for [$n]", emptyList<String>(), violations(chunk.htmlBody))
                cards += chunk.attachments.size
                images += chunk.inlineParts.size
                // Negative: the mime type that reaches the card and the part list is a plain token.
                for (a in chunk.attachments) assertTrue(a.mimeType, Regex("^[a-z0-9.+-]+/[a-z0-9.+-]+$").matches(a.mimeType))
                for (a in chunk.inlineParts) assertTrue(a.mimeType, Regex("^[a-z0-9.+-]+/[a-z0-9.+-]+$").matches(a.mimeType))
                // The image alt text holds the escaped name and cannot add an attribute.
                if (chunk.inlineParts.isNotEmpty()) {
                    assertTrue(chunk.htmlBody.contains("alt=\"${HtmlRenderer.escapeHtml(n)}\""))
                }
            }
            assertTrue("the cards were exercised", cards > 0)
            assertTrue("the inline images were exercised", images > 0)
        }
        // No extractor at all, and a missing file: the placeholder text paths.
        for (n in names) {
            assertEquals(emptyList<String>(), violations(HtmlRenderer.renderChunk(listOf(msg("a", "b", n)), "x", null).htmlBody))
        }
    }

    @Test
    fun aHostileMimeTypeNeverReachesTheMessageHeaders() {
        val rendered = HtmlRenderer.RenderedChunk(
            htmlBody = "<div>x</div>",
            attachments = listOf(HtmlRenderer.AttachmentPart("f.bin", byteArrayOf(1), "text/html\"><script>alert(1)</script>")),
        )
        try {
            HtmlMimeBuilder.buildRaw("Meera Iyer", listOf(msg("a", "b")), ChunkSize.Day, rendered, "<a@local>")
            fail("expected the hostile type to be refused")
        } catch (e: UnsafeHeaderValueException) {
            assertTrue(e.message, e.message!!.contains("MIME type"))
        }
        for (bad in listOf("text/plain\r\nBcc: x@y.z", "a b/c", "text/", "/x", "x/y; boundary=z", "x/y\u0000")) {
            val r = HtmlRenderer.RenderedChunk(
                htmlBody = "x",
                attachments = listOf(HtmlRenderer.AttachmentPart("f.bin", byteArrayOf(1), bad)),
            )
            try {
                HtmlMimeBuilder.buildRaw("Meera Iyer", listOf(msg("a", "b")), ChunkSize.Day, r, "<a@local>")
                fail("expected a refusal for [$bad]")
            } catch (_: UnsafeHeaderValueException) {
            }
        }
    }

    // ---- headers ----

    private fun headerBlocks(eml: String): List<List<String>> {
        // Every header block: the text before each blank line that is followed by a part, plus the top.
        val blocks = mutableListOf<List<String>>()
        val lines = eml.split("\n")
        var i = 0
        var inHeaders = true
        var cur = mutableListOf<String>()
        while (i < lines.size) {
            val l = lines[i]
            if (inHeaders) {
                if (l.isEmpty()) { if (cur.isNotEmpty()) blocks.add(cur); cur = mutableListOf(); inHeaders = false } else cur.add(l)
            } else if (l.startsWith("--")) {
                inHeaders = true
            }
            i++
        }
        return blocks
    }

    private fun namesOf(block: List<String>) =
        block.filter { !it.startsWith(" ") && !it.startsWith("\t") }.map { it.substringBefore(':') }

    private val hostileNames = listOf(
        "Meera\r\nBcc: attacker@example.com",
        "Meera\nX-Injected: 1",
        "Meera\rX-Injected: 1",
        "Meera\r\n\r\n<script>alert(1)</script>",
        "<script>alert(1)</script>",
        "\"><svg onload=alert(1)>",
    )

    private fun benignNames(): Set<String> {
        val png = HtmlRenderer.InlinePart("img-aaaaaaaaaaaa", byteArrayOf(1), "image/png")
        val pdf = HtmlRenderer.AttachmentPart("a.pdf", byteArrayOf(1), "application/pdf")
        val r = HtmlRenderer.RenderedChunk(htmlBody = "<div>x</div>", inlineParts = listOf(png), attachments = listOf(pdf))
        val raw = HtmlMimeBuilder.buildRaw("Meera Iyer", listOf(msg("Meera Iyer", "hi")), ChunkSize.Day, r, "<a@local>", inReplyTo = "<b@local>")
        return headerBlocks(String(raw, Charsets.UTF_8)).flatMap { namesOf(it) }.toSet()
    }

    @Test
    fun hostileNamesNeverInjectAHeaderInTheHtmlMail() {
        val allowed = benignNames()
        assertTrue(allowed.contains("Subject") && allowed.contains("Content-Disposition"))
        var built = 0
        for (h in hostileNames) {
            for (variant in 0..2) {
                val rendered = HtmlRenderer.RenderedChunk(
                    htmlBody = "<div>x</div>",
                    attachments = listOf(HtmlRenderer.AttachmentPart(if (variant == 1) h else "a.pdf", byteArrayOf(1), "application/pdf")),
                )
                val chunk = listOf(ParsedMessage(if (variant == 2) h else "chat-1", ts, h, h))
                val raw = try {
                    HtmlMimeBuilder.buildRaw(
                        displayName = if (variant == 0) h else "Meera Iyer", chunk = chunk, chunkSize = ChunkSize.Day,
                        rendered = rendered, messageId = "<a@local>", suffix = if (variant == 0) h else "",
                    )
                } catch (_: UnsafeHeaderValueException) {
                    continue // refused outright is also safe
                }
                built++
                val eml = String(raw, Charsets.UTF_8)
                for (block in headerBlocks(eml)) {
                    val extra = namesOf(block).filter { it !in allowed }
                    assertEquals("injected header(s) for [$h] variant $variant", emptyList<String>(), extra)
                }
                assertFalse("no Bcc line", eml.lines().any { it.startsWith("Bcc:", ignoreCase = true) })
                assertFalse("no X-Injected line", eml.lines().any { it.startsWith("X-Injected", ignoreCase = true) })
                // Exactly one Subject, one From, one To in the top block.
                val top = namesOf(headerBlocks(eml).first())
                for (n in listOf("Subject", "From", "To", "Message-ID")) assertEquals("$n for [$h]", 1, top.count { it == n })
            }
        }
        assertTrue("some cases were actually built", built > 0)
    }

    @Test
    fun hostileNamesNeverInjectAHeaderInThePlainTextMail() {
        for (h in hostileNames + hostile) {
            val (raw, _) = MimeBuilder.buildMimeMessage(
                displayName = h,
                chunk = listOf(ParsedMessage("chat-1", ts, h, h)),
                chunkSize = ChunkSize.Day,
                labelId = "WhatsApp/x",
                messageId = "<a@local>",
            )
            val eml = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
            val top = namesOf(headerBlocks(eml).first())
            assertEquals("for [$h]", 1, top.count { it == "Subject" })
            assertEquals("for [$h]", 1, top.count { it == "From" })
            assertFalse(top.any { it.equals("Bcc", true) || it.startsWith("X-Injected") })
        }
    }
}
