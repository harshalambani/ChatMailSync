package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** BUG-13: the final notification must outlive the foreground notification. */
class SyncResultNotificationTest {

    @Test
    fun theResultIdIsNeitherTheForegroundIdNorTheWatchFolderId() {
        assertNotEquals(SyncWorker.NOTIFICATION_ID, SyncWorker.RESULT_NOTIFICATION_ID)
        assertNotEquals(WatchFolderWorker.NOTIFICATION_ID, SyncWorker.RESULT_NOTIFICATION_ID)
        assertNotEquals(SyncWorker.NOTIFICATION_ID, WatchFolderWorker.NOTIFICATION_ID)
    }

    @Test
    fun theResultChannelIsNewAndTheSyncChannelIsUnchanged() {
        assertEquals("sync_channel", SyncWorker.NOTIFICATION_CHANNEL_ID)
        assertNotEquals("sync_channel", SyncWorker.RESULT_CHANNEL_ID)
        assertNotEquals(WatchFolderWorker.NOTIFICATION_CHANNEL_ID, SyncWorker.RESULT_CHANNEL_ID)
    }

    @Test
    fun theResultIdIsNeverUsedMidRunAndProgressStaysOnTheForegroundId() {
        val src = File("src/main/java/com/chatmailsync/app/SyncWorker.kt").readText()
        // manager.notify is called with the foreground id (progress) and the result id (final) only.
        val calls = Regex("""manager\.notify\((\w+),""").findAll(src).map { it.groupValues[1] }.toList()
        assertEquals(listOf("NOTIFICATION_ID", "RESULT_NOTIFICATION_ID"), calls)
        // The progress poll uses notify(text); the result is posted only through postResult.
        assertEquals(1, Regex("""postResult\(\s*syncResultNotice""").findAll(src).count())
        assertEquals(1, Regex("""postResult\(syncFailedNotice""").findAll(src).count())
        assertFalse(src.substringBefore("private fun notify(").contains("manager.notify(RESULT"))
    }

    @Test
    fun aCleanRunHasNoFailureLineAndIsQuiet() {
        val n = syncResultNotice(dryRun = false, stopped = false, messagesSynced = 12, filesFailed = 0)
        assertEquals("Sync complete \u2014 12 message(s) synced", n.text)
        assertFalse(n.text.contains("kept"))
        assertFalse(n.alert)
        assertNull(exportsKeptLine(0))
    }

    @Test
    fun aKeptExportAlertsAndKeepsTheBug12Text() {
        val n = syncResultNotice(dryRun = false, stopped = false, messagesSynced = 12, filesFailed = 1)
        assertEquals(
            "Sync complete \u2014 12 message(s) synced; 1 export could not be read and was kept - see Sync log",
            n.text,
        )
        assertTrue(n.alert)
        assertTrue(syncResultNotice(true, false, 0, 3).text.startsWith("Test run complete"))
    }

    @Test
    fun aStoppedRunSaysStoppedAndIsQuiet() {
        val n = syncResultNotice(dryRun = false, stopped = true, messagesSynced = 4, filesFailed = 0)
        assertEquals("Sync stopped \u2014 4 message(s) synced", n.text)
        assertFalse(n.alert)
        assertTrue(syncResultNotice(true, true, 0, 0).text.startsWith("Test run stopped"))
    }

    @Test
    fun aFailureAlertsAndNoResultTextNamesAChat() {
        assertTrue(syncFailedNotice("boom").alert)
        assertEquals("Sync failed: boom", syncFailedNotice("boom").text)
        for (n in 0..3) {
            val t = syncResultNotice(false, false, 5, n).text
            assertFalse(t.contains("Meera"))
            assertFalse(t.contains("WhatsApp Chat with"))
        }
    }

    @Test
    fun aCancellationIsQuietAndNeverSaysFailed() {
        for (dry in listOf(false, true)) {
            val n = syncCancelledNotice(dry)
            assertFalse(n.alert)
            assertFalse(n.text.startsWith("Sync failed"))
            assertEquals(if (dry) "Test run stopped" else "Sync stopped", n.text)
        }
        // A genuine exception is unchanged.
        assertTrue(syncFailedNotice("boom").alert)
        assertTrue(syncFailedNotice("boom").text.startsWith("Sync failed: "))
    }

    @Test
    fun theCancellationCatchComesFirstAndRethrows() {
        val src = File("src/main/java/com/chatmailsync/app/SyncWorker.kt").readText()
        val c = src.indexOf("catch (e: CancellationException)")
        val g = src.indexOf("catch (e: Exception)")
        assertTrue(c in 0 until g)
        assertTrue(src.substring(c, g).contains("throw e"))
        assertTrue(src.substring(c, g).contains("syncCancelledNotice(dryRun)"))
        assertFalse(src.substring(c, g).contains("syncFailedNotice"))
    }
}
