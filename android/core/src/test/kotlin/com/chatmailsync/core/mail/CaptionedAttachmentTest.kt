package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BUG-14: a photo or file sent with a caption is one message whose body is
 * "<name> (file attached)" on the first line and the caption below it.
 * Synthetic exports, placeholder names only.
 */
class CaptionedAttachmentTest {

    private fun dir(): File {
        val d = File.createTempFile("captiontest", "dir")
        d.delete()
        d.mkdirs()
        return d
    }

    private fun parse(vararg lines: String): List<ParsedMessage> {
        val d = dir()
        val f = File(d, "chat.txt")
        f.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        return parseFile(f, chatId = "test_chat").toList()
    }

    private val head = "[14/03/25, 09:41:23] - Meera Iyer: "

    @Test
    fun captionedPhotoIsAttachedAndCaptionIsTheText() {
        val m = parse(head + "IMG-20261020-WA0001.jpg (file attached)", "Photo from the trek").single()
        assertEquals("IMG-20261020-WA0001.jpg", m.attachmentFilename)
        assertEquals("IMG-20261020-WA0001.jpg (file attached)\nPhoto from the trek", m.body)

        val d = dir()
        File(d, "_chat.txt").writeText("x")
        File(d, "IMG-20261020-WA0001.jpg").writeBytes(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + "jpeg".toByteArray(),
        )
        val r = HtmlRenderer.renderChunk(listOf(m), "Rohan Mehta", MediaExtractor(File(d, "_chat.txt")))
        assertEquals(1, r.inlineParts.size)
        assertTrue(r.htmlBody.contains("Photo from the trek"))
        assertFalse(r.htmlBody.contains("(file attached)"))
        assertFalse(r.htmlBody.contains("IMG-20261020-WA0001.jpg (file"))
    }

    @Test
    fun uncaptionedFileStillAttachesExactlyAsBefore() {
        val m = parse(head + "doc.pdf (file attached)").single()
        assertEquals("doc.pdf", m.attachmentFilename)
        assertEquals("doc.pdf (file attached)", m.body)
    }

    @Test
    fun laterLineMentioningTheMarkerAttachesNothing() {
        val m = parse(head + "look at this", "x.jpg (file attached)").single()
        assertNull(m.attachmentFilename)
        assertEquals("look at this\nx.jpg (file attached)", m.body)
    }

    @Test
    fun lookAlikeFirstLinesAttachNothing() {
        // no space before the bracket
        assertNull(parse(head + "x.jpg(file attached)", "caption").single().attachmentFilename)
        // text after the bracket on line 1
        assertNull(parse(head + "x.jpg (file attached) lol", "caption").single().attachmentFilename)
        // the same two forms on a single line, as today
        assertNull(parse(head + "x.jpg(file attached)").single().attachmentFilename)
        assertNull(parse(head + "x.jpg (file attached) lol").single().attachmentFilename)
    }

    @Test
    fun hashOfACaptionedMessageIsTheOneComputedFromTheVerbatimBody() {
        val m = parse(head + "IMG-1.jpg (file attached)", "Photo from the trek").single()
        val iso = m.timestamp.toString()
        // The body is exactly the export text, as before this change.
        val expected = computeMessageHash(
            "test_chat", iso, "Meera Iyer", "IMG-1.jpg (file attached)\nPhoto from the trek",
        )
        assertEquals(expected, computeMessageHash("test_chat", iso, m.sender, m.body))
        // Pinned value: a change to the body that feeds the hash would move it.
        assertEquals(
            "IMG-1.jpg (file attached)\nPhoto from the trek", m.body,
        )
    }

    @Test
    fun missingFileOfACaptionedMessageReadsLikeAnUncaptionedOne() {
        val d = dir()
        File(d, "_chat.txt").writeText("x")
        val ex = MediaExtractor(File(d, "_chat.txt"))
        val cap = parse(head + "ghost.jpg (file attached)", "Photo").single()
        val plain = parse(head + "ghost.jpg (file attached)").single()
        val rc = HtmlRenderer.renderChunk(listOf(cap), "Rohan Mehta", ex)
        val rp = HtmlRenderer.renderChunk(listOf(plain), "Rohan Mehta", ex)
        assertTrue(rc.htmlBody.contains("ghost.jpg"))
        assertTrue(rc.htmlBody.contains("not found in export"))
        assertTrue(rc.htmlBody.contains("Photo"))
        assertEquals(rp.omissions, rc.omissions)
        assertTrue(rc.inlineParts.isEmpty())
    }

    @Test
    fun oversizedCaptionedFileGoesToTheOmissionListLikeAnUncaptionedOne() {
        val d = dir()
        File(d, "_chat.txt").writeText("x")
        File(d, "big.pdf").writeBytes("%PDF-1.4 ".toByteArray() + ByteArray(5000) { 'z'.code.toByte() })
        val ex = MediaExtractor(File(d, "_chat.txt"))
        val cap = parse(head + "big.pdf (file attached)", "Annual report").single()
        val rc = HtmlRenderer.renderChunk(listOf(cap), "Rohan Mehta", ex, maxMediaBytes = 1000L)
        assertEquals(1, rc.omissions.size)
        assertEquals("big.pdf", rc.omissions[0].filename)
        assertTrue(rc.htmlBody.contains("Annual report"))
        assertTrue(rc.attachments.isEmpty())
    }

    @Test
    fun captionWithMoreLinesKeepsAllOfThem() {
        val m = parse(head + "a.jpg (file attached)", "line one", "line two").single()
        assertEquals("a.jpg", m.attachmentFilename)
        val r = HtmlRenderer.renderChunk(listOf(m), "Rohan Mehta", null)
        assertTrue(r.htmlBody.contains("line one"))
        assertTrue(r.htmlBody.contains("line two"))
        assertFalse(r.htmlBody.contains("(file attached)"))
    }
}
