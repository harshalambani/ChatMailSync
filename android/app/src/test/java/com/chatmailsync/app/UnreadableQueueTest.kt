package com.chatmailsync.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** BUG-19: wording for exports moved out of the queue, and the watched-folder delivery rule. */
class UnreadableQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun theNoticeSaysWhereTheFileWentAndThatRemoveClearsIt() {
        val line = exportsKeptLine(1, 1)!!
        assertTrue(line.contains("moved out of the queue"))
        assertTrue(line.contains("Cannot be read"))
        assertTrue(line.contains("Remove clears it"))
        assertTrue(!line.contains("was kept"))
    }

    @Test
    fun keptAndMovedFilesAreBothReported() {
        val line = exportsKeptLine(3, 1)!!
        assertTrue(line.contains("2 exports could not be read and were kept"))
        assertTrue(line.contains("1 export could not be read and was moved"))
    }

    @Test
    fun withNothingMovedTheOldWordingIsUnchanged() {
        assertEquals("1 export could not be read and was kept - see Sync log", exportsKeptLine(1, 0))
        assertNull(exportsKeptLine(0, 0))
        assertNull(exportsKeptLine(0, 2))
    }

    @Test
    fun aMovedFileStillAlertsTheUser() {
        assertTrue(syncResultNotice(false, false, 5, 1, 1).alert)
        assertTrue(!syncResultNotice(false, false, 5, 0, 0).alert)
    }

    @Test
    fun aFileSittingInTheUnreadableFolderIsNeverTreatedAsDelivered() {
        val inbox = tmp.newFolder("inbox")
        val unreadable = tmp.newFolder("unreadable")
        File(unreadable, "a.txt").writeText("x")
        File(inbox, "b.txt").writeText("x")
        val delivered = WatchFolderWorker.deliveredPendingNames(listOf("a.txt", "b.txt", "c.txt"), inbox, unreadable)
        assertEquals("only the file that really left both folders", listOf("c.txt"), delivered)
    }

    @Test
    fun aFileInTheQueueIsNotDelivered() {
        val inbox = tmp.newFolder("inbox")
        val unreadable = tmp.newFolder("unreadable")
        File(inbox, "b.txt").writeText("x")
        assertEquals(emptyList<String>(), WatchFolderWorker.deliveredPendingNames(listOf("b.txt"), inbox, unreadable))
    }
}
