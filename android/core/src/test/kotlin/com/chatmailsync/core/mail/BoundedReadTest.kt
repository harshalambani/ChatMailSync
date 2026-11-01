package com.chatmailsync.core.mail

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BUG-21: a media file or chat text over its limit is never loaded. Synthetic data, placeholder
 * names only.
 */
class BoundedReadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A stream of endless bytes that records how many were taken and fails past [failAfter]. */
    private class EndlessStream(val failAfter: Long) : InputStream() {
        var taken = 0L
        override fun read(): Int {
            if (++taken > failAfter) fail("read past $failAfter bytes")
            return 'a'.code
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            for (i in 0 until len) {
                if (++taken > failAfter) fail("read past $failAfter bytes")
                b[off + i] = 'a'.code.toByte()
            }
            return len
        }
    }

    private fun zipWith(name: String, content: ByteArray): File {
        val f = tmp.newFile("export-${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("_chat.txt"))
            z.write("14/03/25, 09:41 - Meera Iyer: hi\n".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry(name))
            z.write(content)
            z.closeEntry()
        }
        return f
    }

    /** Overwrite the uncompressed size recorded in every central-directory header. */
    private fun understateSize(zip: File, claimed: Int) {
        val bytes = zip.readBytes()
        var i = 0
        while (i < bytes.size - 4) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 0x01.toByte() && bytes[i + 3] == 0x02.toByte()
            ) {
                val at = i + 24
                bytes[at] = (claimed and 0xff).toByte()
                bytes[at + 1] = ((claimed shr 8) and 0xff).toByte()
                bytes[at + 2] = ((claimed shr 16) and 0xff).toByte()
                bytes[at + 3] = ((claimed shr 24) and 0xff).toByte()
            }
            i++
        }
        zip.writeBytes(bytes)
    }

    @Test
    fun readAtMostStopsExactlyOneBytePastTheLimitOnAnEndlessStream() {
        val s = EndlessStream(failAfter = 101)
        assertNull(readAtMost(s, 100))
        assertEquals(101L, s.taken)
    }

    @Test
    fun readAtMostReturnsAFileJustUnderOrAtTheLimit() {
        assertEquals(99, readAtMost(ByteArray(99).inputStream(), 100)!!.size)
        assertEquals(100, readAtMost(ByteArray(100).inputStream(), 100)!!.size)
        assertNull(readAtMost(ByteArray(101).inputStream(), 100))
    }

    @Test
    fun anOverBudgetZipEntryIsLeftUnreadAndItsSizeIsReported() {
        val ex = MediaExtractor(zipWith("big.mp4", ByteArray(5000) { 1 }))
        ex.use {
            val r = it.resolveWithin("big.mp4", 1000L)!!
            assertNull("not loaded", r.data)
            assertEquals(5000L, r.sizeBytes)
        }
    }

    @Test
    fun anEntryDeclaringSmallButHoldingMoreStopsAtTheLimit() {
        val zip = zipWith("liar.mp4", ByteArray(50_000) { 'x'.code.toByte() })
        understateSize(zip, claimed = 10)
        MediaExtractor(zip).use {
            val r = it.resolveWithin("liar.mp4", 1000L)!!
            assertNull("the data is not kept", r.data)
            assertEquals("stopped one byte past the limit", 1001L, r.sizeBytes)
        }
    }

    @Test
    fun aFileJustUnderTheBudgetIsStillIncluded() {
        val dir = tmp.newFolder()
        File(dir, "_chat.txt").writeText("x")
        File(dir, "ok.jpg").writeBytes(ByteArray(1000) { 2 })
        File(dir, "over.jpg").writeBytes(ByteArray(1001) { 2 })
        MediaExtractor(File(dir, "_chat.txt")).use {
            val ok = it.resolveWithin("ok.jpg", 1000L)!!
            assertEquals(1000, ok.data!!.size)
            val over = it.resolveWithin("over.jpg", 1000L)!!
            assertNull(over.data)
            assertEquals(1001L, over.sizeBytes)
        }
    }

    @Test
    fun aPlainFileOverBudgetIsLeftUnreadWithItsRealSize() {
        val dir = tmp.newFolder()
        File(dir, "_chat.txt").writeText("x")
        File(dir, "big.pdf").writeBytes(ByteArray(5000))
        MediaExtractor(File(dir, "_chat.txt")).use {
            val r = it.resolveWithin("big.pdf", 1000L)!!
            assertNull(r.data)
            assertEquals(5000L, r.sizeBytes)
        }
    }

    @Test
    fun theOmissionNoteForATooBigFileIsTheSameAsBefore() {
        val dir = tmp.newFolder()
        val chat = File(dir, "_chat.txt").also { it.writeText("x") }
        File(dir, "big.pdf").writeBytes(ByteArray(5000))
        val msg = ParsedMessage(
            "c", java.time.LocalDateTime.parse("2025-03-14T09:41:00"), "Meera Iyer", "big.pdf (file attached)", "big.pdf",
        )
        MediaExtractor(chat).use {
            val r = HtmlRenderer.renderChunk(listOf(msg), "Rohan Mehta", it, maxMediaBytes = 1000L)
            assertEquals(1, r.omissions.size)
            assertEquals(5000L, r.omissions[0].sizeBytes)
            assertEquals(1000L, r.omissions[0].limitBytes)
            assertTrue(
                r.htmlBody.contains("big.pdf") && r.htmlBody.contains("5 KB") &&
                    r.htmlBody.contains("too large to email (limit 1 KB). Not included; it stays in your WhatsApp export."),
            )
        }
    }

    @Test
    fun withNoBudgetAHugeFileStillHitsTheHardStop() {
        assertTrue(MAX_UNBUDGETED_MEDIA_BYTES > 0)
        val dir = tmp.newFolder()
        File(dir, "_chat.txt").writeText("x")
        File(dir, "small.jpg").writeBytes(ByteArray(10))
        MediaExtractor(File(dir, "_chat.txt")).use {
            assertNotNull(it.resolveWithin("small.jpg", null)!!.data)
        }
    }

    @Test
    fun chatTextInAZipOverTheCapIsAReadFailure() {
        val f = tmp.newFile("big.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("_chat.txt"))
            val chunk = ByteArray(1_048_576) { 'a'.code.toByte() }
            repeat(65) { z.write(chunk) }
            z.closeEntry()
        }
        try {
            readChatText(f)
            fail("expected a read failure")
        } catch (e: ChatTextReadException) {
            assertTrue(e.message!!.contains("limit"))
        }
    }

    @Test
    fun aPlainChatTextOverTheCapIsAReadFailure() {
        val f = tmp.newFile("WhatsApp Chat with Meera Iyer.txt")
        java.io.RandomAccessFile(f, "rw").use { it.setLength(MAX_CHAT_TEXT_BYTES + 1) }
        try {
            readChatText(f)
            fail("expected a read failure")
        } catch (e: ChatTextReadException) {
            assertTrue(e.message!!.contains("limit"))
        }
    }

    @Test
    fun aNormalChatTextStillReadsExactlyAsBefore() {
        val f = tmp.newFile("WhatsApp Chat with Rohan Mehta.txt")
        val text = "14/03/25, 09:41 - Rohan Mehta: café\n"
        f.writeText(text)
        assertEquals(text, readChatText(f))
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("_chat.txt"))
            z.write(text.toByteArray())
            z.closeEntry()
        }
        val z = tmp.newFile("ok.zip").also { it.writeBytes(out.toByteArray()) }
        assertEquals(text, readChatText(z))
    }
}
