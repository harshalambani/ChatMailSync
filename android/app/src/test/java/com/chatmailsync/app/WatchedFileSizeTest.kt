package com.chatmailsync.app

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A watched-folder file over the cap is skipped before it is opened, is not ledgered, and is named
 * in the run's result. Synthetic data, placeholder names only.
 */
class WatchedFileSizeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cap = MAX_SHARED_EXPORT_BYTES

    /** The worker's per-file step in miniature: the skip decision comes first, then the copy. */
    private class Run(val inbox: File, val incoming: File) {
        var opened = 0
        var skipped = 0
        val ledger = mutableListOf<String>()
        val pending = mutableListOf<String>()
    }

    private fun step(run: Run, name: String, length: Long, size: Int, maxBytes: Long = cap) {
        if (shouldSkipOversizeWatchedFile(length, maxBytes)) {
            run.skipped++
            return
        }
        val r = importShared(
            "content", name,
            { run.opened++; ByteArrayInputStream(ByteArray(size) { 3 }) },
            run.inbox, run.incoming, maxBytes,
        )
        val outcome = r.outcome
        if (outcome != null) {
            run.ledger.add(name)
            if (!outcome.alreadyQueued) run.pending.add(outcome.file.name)
        }
    }

    private var counter = 0

    private fun newRun(): Run {
        val data = tmp.newFolder("data" + (counter++))
        val inbox = File(data, "inbox").also { it.mkdirs() }
        return Run(inbox, incomingDirFor(inbox))
    }

    @Test
    fun anOverCapFileIsNeverOpenedNeverLedgeredNeverPendingAndIsNamed() {
        val run = newRun()
        step(run, "WhatsApp Chat with Meera Iyer.zip", cap + 1, 10)
        assertEquals("never opened", 0, run.opened)
        assertEquals(emptyList<String>(), run.ledger)
        assertEquals(emptyList<String>(), run.pending)
        assertEquals(0, run.inbox.listFiles()?.size ?: 0)
        assertEquals(1, run.skipped)
        val text = withOversizeNote("No new files found", run.skipped)
        assertTrue(text.startsWith("No new files found"))
        assertTrue(text.contains("1 file in the watched folder is larger than 600 MB"))
        assertTrue(text.contains("skipped"))
    }

    @Test
    fun theSkipDecisionBoundaries() {
        assertTrue(shouldSkipOversizeWatchedFile(cap + 1))
        assertFalse("exactly at the cap is read", shouldSkipOversizeWatchedFile(cap))
        assertFalse("size unknown is read", shouldSkipOversizeWatchedFile(0L))
        assertFalse(shouldSkipOversizeWatchedFile(1234L))
    }

    @Test
    fun anUnknownSizeStillGoesThroughTheBoundedCopy() {
        val run = newRun()
        step(run, "WhatsApp Chat with Rohan Mehta.zip", 0L, 500)
        assertEquals(1, run.opened)
        assertEquals(listOf("WhatsApp Chat with Rohan Mehta.zip"), run.ledger)
        // And the copy is still bounded: an unknown-size source over the limit leaves nothing.
        val run2 = newRun()
        step(run2, "big.zip", 0L, 50, maxBytes = 10L)
        assertEquals(1, run2.opened)
        assertEquals(emptyList<String>(), run2.ledger)
        assertEquals(0, run2.inbox.listFiles()?.size ?: 0)
        assertEquals(0, run2.incoming.listFiles()?.size ?: 0)
    }

    @Test
    fun aFileExactlyAtTheCapIsImported() {
        val run = newRun()
        step(run, "edge.zip", 100L, 100, maxBytes = 100L)
        assertEquals(0, run.skipped)
        assertEquals(listOf("edge.zip"), run.ledger)
        assertEquals(listOf("edge.zip"), run.pending)
    }

    @Test
    fun aNormalWatchedFileBehavesAsBefore() {
        val run = newRun()
        step(run, "WhatsApp Chat with Meera Iyer.zip", 2048L, 2048)
        assertEquals(1, run.opened)
        assertEquals(0, run.skipped)
        assertEquals(listOf("WhatsApp Chat with Meera Iyer.zip"), run.ledger)
        assertEquals(listOf("WhatsApp Chat with Meera Iyer.zip"), run.pending)
        assertEquals("text is untouched when nothing was skipped", "Imported 1 new file(s)", withOversizeNote("Imported 1 new file(s)", 0))
        assertEquals("", oversizeSkipNote(0))
    }

    @Test
    fun severalSkippedFilesAreCountedAndTheNoteIsPlainAscii() {
        val note = oversizeSkipNote(3)
        assertTrue(note.startsWith("3 files in the watched folder are larger than 600 MB"))
        assertTrue(note.all { it.code in 32..126 })
        assertNotNull(withOversizeNote("Imported 2 new file(s) - syncing", 3).takeIf { it.contains("\n") })
    }
}
