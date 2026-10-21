package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUG-15: a run the user stopped reads as stopped, never as failed, in every text the app shows. */
class StoppedRunTextTest {

    private fun summary(status: String, synced: Long) = SyncSummary(
        windowDays = 90, totalRuns = 1, failedRuns = 0, runningRuns = 0, lastStatus = status,
        lastDisplayName = "Meera Iyer", lastCompletedAt = null, lastMessagesSynced = synced, lastMessagesSkipped = 0,
    )

    @Test
    fun theLogRowSaysHowManyWereUploadedOutOfHowMany() {
        assertEquals(
            "Stopped by you - 54 of 120 uploaded",
            stoppedCountsText(54, "stopped by the user after 54 of 120 messages"),
        )
        assertEquals("Stopped by you - 54 uploaded", stoppedCountsText(54, null))
    }

    @Test
    fun homeNeverSaysNothingUploadedOrFailedForAStop() {
        val text = summaryCountsText(summary("stopped", 54))
        assertEquals("Stopped by you - 54 uploaded", text)
        assertFalse(text.contains("Nothing uploaded"))
        assertFalse(text.contains("failed"))
        // And a real failure still does.
        assertEquals("Nothing uploaded", summaryCountsText(summary("failed", 0)))
    }

    @Test
    fun theChatListShowsAStopAsItsOwnStateNotFailed() {
        assertEquals(ChatStatus.STOPPED, chatStatusOf("stopped"))
        assertEquals(ChatStatus.FAILED, chatStatusOf("failed"))
        assertTrue(ChatStatus.STOPPED.description.contains("stopped"))
    }
}
