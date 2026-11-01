package com.chatmailsync.app

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A share is copied in the background under a temporary name, accepted only as a content link, and
 * capped in size. Synthetic data, placeholder names only.
 */
class SharedImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dirs(): Pair<File, File> {
        val data = tmp.newFolder("data")
        val inbox = File(data, "inbox").also { it.mkdirs() }
        return inbox to incomingDirFor(inbox)
    }

    private fun bytes(n: Int) = ByteArray(n) { 7 }

    /** Yields [first] bytes and then fails, as a source that goes away half-way. */
    private class BreaksHalfway(private val first: Int) : InputStream() {
        private var given = 0
        override fun read(): Int {
            if (given >= first) throw IOException("gone")
            given++
            return 1
        }
    }

    private fun queued(inbox: File) = inbox.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    @Test
    fun aNormalContentShareQueuesExactlyOneFileUnderTheRightName() {
        val (inbox, incoming) = dirs()
        val r = importShared("content", "WhatsApp Chat with Meera Iyer.zip", { ByteArrayInputStream(bytes(1000)) }, inbox, incoming)
        val outcome = r.outcome!!
        assertFalse(outcome.alreadyQueued)
        assertEquals(listOf("WhatsApp Chat with Meera Iyer.zip"), queued(inbox))
        assertEquals(1000L, File(inbox, "WhatsApp Chat with Meera Iyer.zip").length())
        assertEquals("no leftover temporary file", 0, incoming.listFiles()?.size ?: 0)
    }

    @Test
    fun aFileLinkIsRefusedAndNothingIsOpenedOrQueued() {
        val (inbox, incoming) = dirs()
        var opened = false
        val r = importShared("file", "chat.zip", { opened = true; ByteArrayInputStream(bytes(10)) }, inbox, incoming)
        assertNull(r.outcome)
        assertNotNull(r.message)
        assertTrue(r.message!!.contains("Nothing was copied"))
        assertFalse("the source was never opened", opened)
        assertEquals(emptyList<String>(), queued(inbox))
        assertFalse(incoming.exists() && (incoming.listFiles()?.isNotEmpty() == true))
    }

    @Test
    fun noSchemeOrAnOtherSchemeIsRefusedToo() {
        val (inbox, incoming) = dirs()
        for (scheme in listOf(null, "", "http", "android.resource", "FILE")) {
            val r = importShared(scheme, "chat.zip", { ByteArrayInputStream(bytes(10)) }, inbox, incoming)
            assertNull("scheme $scheme", r.outcome)
        }
        assertEquals(emptyList<String>(), queued(inbox))
        assertTrue(isShareableScheme("content"))
        assertTrue(isShareableScheme("CONTENT"))
    }

    @Test
    fun aCopyThatFailsHalfwayLeavesNothing() {
        val (inbox, incoming) = dirs()
        val r = importShared("content", "chat.zip", { BreaksHalfway(300_000) }, inbox, incoming)
        assertNull(r.outcome)
        assertEquals(emptyList<String>(), queued(inbox))
        assertEquals("its temporary file is removed", 0, incoming.listFiles()?.size ?: 0)
    }

    @Test
    fun aShareOverTheCapLeavesNothing() {
        val (inbox, incoming) = dirs()
        val r = importShared("content", "big.zip", { ByteArrayInputStream(bytes(2001)) }, inbox, incoming, maxBytes = 2000L)
        assertNull(r.outcome)
        assertTrue(r.message!!.contains("Nothing was copied"))
        assertEquals(emptyList<String>(), queued(inbox))
        assertEquals(0, incoming.listFiles()?.size ?: 0)
        // exactly at the cap is fine
        val ok = importShared("content", "edge.zip", { ByteArrayInputStream(bytes(2000)) }, inbox, incoming, maxBytes = 2000L)
        assertNotNull(ok.outcome)
    }

    @Test
    fun aTemporaryFileIsNeverQueuedNorBlocksAReShare() {
        val (inbox, incoming) = dirs()
        incoming.mkdirs()
        // A copy another run left behind, long ago, with a name that looks like the export.
        val stale = File(incoming, "copy-1.part").also { it.writeBytes(bytes(50)) }
        stale.setLastModified(1_000L)
        assertEquals("never in the queue listing", emptyList<String>(), queued(inbox))
        val r = importShared("content", "chat.zip", { ByteArrayInputStream(bytes(10)) }, inbox, incoming)
        assertFalse(r.outcome!!.alreadyQueued)
        assertEquals(listOf("chat.zip"), queued(inbox))
        assertFalse("the leftover is cleaned at the next import", stale.exists())
    }

    @Test
    fun aFreshInProgressTemporaryFileIsLeftAlone() {
        val (inbox, incoming) = dirs()
        incoming.mkdirs()
        val busy = File(incoming, "copy-2.part").also { it.writeBytes(bytes(50)) }
        importShared("content", "chat.zip", { ByteArrayInputStream(bytes(10)) }, inbox, incoming)
        assertTrue("another copy still running is not cut off", busy.exists())
    }

    @Test
    fun theSameNameShareIsAlreadyQueuedAndCopiesNothingNew() {
        val (inbox, incoming) = dirs()
        importShared("content", "chat.zip", { ByteArrayInputStream(bytes(10)) }, inbox, incoming)
        var opened = false
        val again = importShared("content", "chat.zip", { opened = true; ByteArrayInputStream(bytes(99)) }, inbox, incoming)
        assertTrue(again.outcome!!.alreadyQueued)
        assertFalse(opened)
        assertEquals(10L, File(inbox, "chat.zip").length())
    }

    @Test
    fun theDisplayNameIsAlwaysAPlainLeaf() {
        assertEquals("chat.zip", safeLeafName("../../etc/chat.zip"))
        assertEquals("chat.zip", safeLeafName("a\\b\\chat.zip"))
        assertEquals("chat.zip", safeLeafName("dir/chat.zip"))
        assertNull(safeLeafName(".."))
        assertNull(safeLeafName("."))
        assertNull(safeLeafName("  "))
        assertNull(safeLeafName("x/"))
        assertNull(safeLeafName(null))
        val (inbox, incoming) = dirs()
        val r = importShared("content", "../outside.zip", { ByteArrayInputStream(bytes(5)) }, inbox, incoming)
        assertEquals(inbox.canonicalFile, r.outcome!!.file.canonicalFile.parentFile)
        assertFalse(File(inbox.parentFile, "outside.zip").exists())
    }

    @Test
    fun theTemporaryFolderIsBesideTheQueueNotInsideIt() {
        val (inbox, incoming) = dirs()
        assertEquals(inbox.parentFile.canonicalFile, incoming.parentFile.canonicalFile)
        assertTrue(incoming.canonicalFile != inbox.canonicalFile)
    }

    @Test
    fun aBatchSummaryCountsRefusedItemsAsNotRead() {
        val ok = ImportResult(ImportOutcome(File("a.zip"), alreadyQueued = false), null)
        val dup = ImportResult(ImportOutcome(File("b.zip"), alreadyQueued = true), null)
        val no = ImportResult(null, "x")
        assertEquals("Imported 1 file · 1 already queued · 1 file could not be read", importSummary(listOf(ok, dup, no)))
    }
}
