package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.msg
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Differences from Python that are on purpose, each named here, plus negative
 * tests for the helper behaviour. Nothing in this file is "a fix": each test
 * says which side it is on.
 */
class SyncDeliberateDifferencesAndNegativesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val chat = "test_chat"

    private fun zipWith(vararg entries: Pair<String, ByteArray>): File {
        val f = tmp.newFile("export-${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return f
    }

    private val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46)

    // ---- D35: DELIBERATE DIFFERENCE FROM PYTHON (named, not a Python bug fix)

    @Test
    fun d35_anAttachmentWhoseNameHoldsALineBreakIsSkippedAndListedAndEverythingElseSends() {
        val bad = "a\nb.pdf"
        val bad2 = "x\r\nBcc: attacker@example.test\r\n.pdf"
        val zip = zipWith(bad to pdf, bad2 to pdf, "ok.pdf" to pdf)
        val messages = listOf(
            msg(chat, "2025-03-01T10:00:00", "Meera Iyer", "first with bad", bad),
            msg(chat, "2025-03-01T10:01:00", "Rohan Mehta", "second with good", "ok.pdf"),
            msg(chat, "2025-03-01T10:02:00", "Meera Iyer", "third with bad two", bad2),
            msg(chat, "2025-03-01T10:03:00", "Rohan Mehta", "fourth text only"),
        )
        val transport = FakeMailTransport()
        val out = pushChat(transport, "Meera Iyer", messages, ChunkSize.Day, sourcePath = zip)

        // everything else is sent: one email for the day, the good attachment inside it
        assertEquals(1, transport.inserted.size)
        val raw = String(transport.inserted[0].bytes, Charsets.ISO_8859_1)
        assertTrue("the good attachment is sent", raw.contains("ok.pdf"))
        assertFalse("no header is injected", raw.split("\r\n", "\n").any { it.startsWith("Bcc:") })

        // the two skipped files are listed under the message's omissions, with the reason
        val omissions = out.results.flatMap { it.omissions }
        assertEquals(setOf(bad, bad2), omissions.map { it.filename }.toSet())
        assertTrue(omissions.all { it.reason == HtmlMimeBuilder.UNSENDABLE_FILENAME_REASON })

        // and every message body still made it into the rendered email
        val rendered = HtmlRenderer.renderChunk(messages, "Meera Iyer", MediaExtractor(zip))
        for (text in listOf("first with bad", "second with good", "third with bad two", "fourth text only")) {
            assertTrue("$text must be in the email", rendered.htmlBody.contains(text))
        }
        assertEquals(listOf("ok.pdf"), rendered.attachments.map { it.filename })
    }

    @Test
    fun d35_theSummaryLineNamesTheFileWithTheBreakShownAsAQuestionMark() {
        val stats = SyncStats()
        val om = HtmlRenderer.MediaOmission("a\nb.pdf", 4, 0, HtmlMimeBuilder.UNSENDABLE_FILENAME_REASON)
        collectOmissions(stats, "Meera Iyer", listOf(PushResult("m", "m", "t", listOf(om, om))))
        assertEquals(
            listOf("Meera Iyer: a?b.pdf skipped - its file name holds a line break"),
            stats.mediaOmitted,
        )
        assertFalse("the stored line holds no line break", stats.mediaOmitted.any { '\n' in it || '\r' in it })
    }

    @Test
    fun d35_negative_aNameWithoutALineBreakIsNeverSkipped() {
        val zip = zipWith("my report (1).pdf" to pdf, "caf\u00e9 \u2764.pdf" to pdf, "tab\there.pdf" to pdf)
        val messages = listOf(
            msg(chat, "2025-03-01T10:00:00", "Meera Iyer", "one", "my report (1).pdf"),
            msg(chat, "2025-03-01T10:01:00", "Rohan Mehta", "two", "caf\u00e9 \u2764.pdf"),
        )
        val rendered = HtmlRenderer.renderChunk(messages, "Meera Iyer", MediaExtractor(zip))
        assertEquals(2, rendered.attachments.size)
        assertTrue(rendered.omissions.isEmpty())
    }

    @Test
    fun d35_negative_aTooLargeFileKeepsItsSizeLineAndHasNoReason() {
        val stats = SyncStats()
        val om = HtmlRenderer.MediaOmission("clip.mp4", 30_000_000, 25_000_000)
        assertEquals(null, om.reason)
        collectOmissions(stats, "Meera Iyer", listOf(PushResult("m", "m", "t", listOf(om))))
        assertEquals(
            listOf("Meera Iyer: clip.mp4 (30.0 MB) exceeds the 25 MB per-email limit"),
            stats.mediaOmitted,
        )
    }

    // ---- scrubPaths: platform behaviour named on purpose

    @Test
    fun scrubPaths_windowsDrivePathStaysWholeBecausePythonOnAndroidIsPosix() {
        // Python's Path.name is host-specific; the Android build is POSIX, so a
        // drive path is matched but not collapsed. Not a fix: the same result.
        assertEquals("failed C:\\Users\\alice\\x.txt", scrubPaths("failed C:\\Users\\alice\\x.txt"))
    }

    @Test
    fun scrubPaths_negative_hidesInstallPathsButNeverTouchesOrHidesASecret() {
        // Path scrubbing is not secret scrubbing: a password stays visible to
        // scrubPaths on purpose, which is why SyncManager also runs stripSecret.
        assertEquals("pw=hunter2 at c", scrubPaths("pw=hunter2 at /a/b/c"))
        assertEquals("pw=***", stripSecret("pw=hunter2", "hunter2"))
    }

    // ---- helper negatives

    @Test
    fun recordChatSenders_negative_aStoreFailureNeverEscapes() {
        val repo = SyncTestSupport.newRepo(tmp.newFolder("rs"))
        // no chat row and a closed-over bad sender count cannot fail the sync;
        // here a repository whose database cannot open must not throw either.
        val broken = StateRepository { throw IllegalStateException("db unavailable") }
        recordChatSendersFor(
            broken,
            chat,
            listOf(msg(chat, "2025-03-01T10:00:00", "Meera Iyer", "hi")),
            emptyList(),
        )
        recordChatSendersFor(repo, chat, emptyList(), emptyList())
        assertTrue(repo.listChatSenders(chat).isEmpty())
    }

    @Test
    fun matchesChatFilter_isCaseInsensitiveAndNeverASubstringMatch() {
        assertTrue(matchesChatFilter(null, "c1", "Meera Iyer"))
        assertTrue(matchesChatFilter("", "c1", "Meera Iyer"))
        assertTrue(matchesChatFilter("MEERA IYER", "c1", "Meera Iyer"))
        assertTrue(matchesChatFilter("c1", "c1", "Meera Iyer"))
        assertFalse(matchesChatFilter("meera", "c1", "Meera Iyer"))
        assertTrue("the filter is lower-cased before it meets the id, as in Python", matchesChatFilter("C1", "c1", "x"))
        assertFalse(matchesChatFilter("c2", "c1", "x"))
    }
}
