package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** BUG-17 (a failed "same chat?" answer is shown, not hidden) and UX-02 (held-export notice). */
class LinkAnswerAndHeldNoticeTest {

    @Test
    fun aSuccessfulAnswerReturnsTrueAndLogsNothing() {
        val logs = mutableListOf<String>()
        var saved = 0
        assertTrue(saveLinkAnswer({ saved++ }, { logs += it }))
        assertEquals(1, saved)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun aFailedAnswerReturnsFalseAndLogsTheTypeButNotTheMessage() {
        val logs = mutableListOf<String>()
        val ok = saveLinkAnswer({ throw IllegalStateException("disk full") }, { logs += it })
        assertFalse(ok)
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("IllegalStateException"))
        assertFalse(logs[0].contains("disk full"))
    }

    @Test
    fun aMissingExportErrorNeverPutsTheChatNameInTheLog() {
        val logs = mutableListOf<String>()
        saveLinkAnswer(
            { throw java.io.FileNotFoundException("/x/WhatsApp Chat with Meera Iyer.txt (No such file or directory)") },
            { logs += it },
        )
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("FileNotFoundException"))
        for (bad in listOf("Meera Iyer", ".txt", "WhatsApp Chat with")) assertFalse(logs[0].contains(bad))
    }

    @Test
    fun openingTheAppClearsOnlyTheHeldNotice() {
        val src = File("src/main/java/com/chatmailsync/app/MainActivity.kt").readText()
        val body = src.substringAfter("override fun onResume()").substringBefore("override fun onCreate")
        assertTrue(body.contains("cancel(SyncWorker.HELD_NOTIFICATION_ID)"))
        assertTrue(body.contains("catch (_: Exception)"))
        assertFalse(src.contains("cancelAll"))
        assertFalse(Regex("""cancel\(\s*(SyncWorker\.)?(NOTIFICATION_ID|RESULT_NOTIFICATION_ID)""").containsMatchIn(src))
    }

    @Test
    fun aFailedConfirmIsNotTreatedAsLinkedAndADeclineIsNotRecorded() {
        var linked = false
        var declined = false
        val confirmOk = saveLinkAnswer({ error("boom"); linked = true }, {})
        val declineOk = saveLinkAnswer({ error("boom"); declined = true }, {})
        assertFalse(confirmOk)
        assertFalse(declineOk)
        assertFalse(linked)
        assertFalse(declined)
    }

    @Test
    fun theFailureTextNamesNoChatAndBothAnswersShowIt() {
        assertEquals("Couldn't save your answer. Please try again.", LINK_ANSWER_FAILED_TEXT)
        val src = File("src/main/java/com/chatmailsync/app/MainActivity.kt").readText()
        assertEquals(2, Regex("""saveLinkAnswer\(""").findAll(src).count())
        assertEquals(2, Regex("""LINK_ANSWER_FAILED_TEXT""").findAll(src).count())
        // The old silent swallow around the two answers is gone.
        assertFalse(Regex("""confirmLink\([^)]*\)\s*\}\s*catch \(_: Exception\)""").containsMatchIn(src))
        assertFalse(Regex("""declineLink\([^)]*\)\s*\}\s*catch \(_: Exception\)""").containsMatchIn(src))
    }

    @Test
    fun noNoticeWhenNothingIsHeld() {
        assertNull(heldNotice(0, "watched_folder", false))
    }

    @Test
    fun noNoticeForAForegroundManualSyncOrADryRun() {
        assertNull(heldNotice(3, "manual", false))
        assertNull(heldNotice(3, "watched_folder", true))
    }

    @Test
    fun aBackgroundSyncThatHeldSomethingGetsOneGenericNotice() {
        val n = heldNotice(2, "watched_folder", false)
        assertNotNull(n)
        assertEquals("A chat is waiting for your answer", n!!.title)
        assertEquals("Open Chat Mail Sync to confirm whether it's a renamed chat.", n!!.text)
        for (name in listOf("Meera Iyer", "Rohan Mehta", "@", ".txt", ".zip")) {
            assertFalse(n!!.title.contains(name))
            assertFalse(n!!.text.contains(name))
        }
    }

    @Test
    fun theHeldNoticeIsPrivateSingleIdAndNeverThrowsWithoutPermission() {
        val src = File("src/main/java/com/chatmailsync/app/SyncWorker.kt").readText()
        val body = src.substringAfter("private fun postHeld(").substringBefore("private fun createForegroundInfo")
        assertTrue(body.contains("VISIBILITY_PRIVATE"))
        assertTrue(body.contains("manager.notify(HELD_NOTIFICATION_ID"))
        assertTrue(body.contains("areNotificationsEnabled()"))
        assertTrue(body.contains("catch (_: Exception)"))
        assertFalse(body.contains("retry"))
        assertEquals(1, Regex("""postHeld\(it\)""").findAll(src).count())
    }
}
