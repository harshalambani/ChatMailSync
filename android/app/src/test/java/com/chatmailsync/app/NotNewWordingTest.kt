package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** UX-08: the skipped count reads "not new", never "already there" (which sounded like a mailbox check). */
class NotNewWordingTest {

    private fun summary(synced: Long, skipped: Long) = SyncSummary(
        windowDays = 90, totalRuns = 1, failedRuns = 0, runningRuns = 0, lastStatus = "success",
        lastDisplayName = "Meera Iyer", lastCompletedAt = null, lastMessagesSynced = synced, lastMessagesSkipped = skipped,
    )

    private fun run(synced: Long, skipped: Long) = SyncRunLogEntry(
        runId = 1, chatId = "c", displayName = "Meera Iyer", status = "success", trigger = "manual",
        messagesParsed = synced + skipped, messagesSynced = synced, messagesSkipped = skipped, messagesCutoff = 0,
        errorMessage = null, startedAt = null, completedAt = null, lastSyncedTs = null, uneventful = false,
    )

    @Test
    fun homeAndLogSayHowManyWereNotNew() {
        assertEquals("3 synced, 5 not new", summaryCountsText(summary(3, 5)))
        assertEquals("3 synced, 5 not new", runCountsText(run(3, 5)))
        assertEquals("Not new, so skipped", SKIPPED_DETAIL_LABEL)
    }

    @Test
    fun noWordingSaysAlreadyThere() {
        val all = listOf(summaryCountsText(summary(3, 5)), runCountsText(run(3, 5)), SKIPPED_DETAIL_LABEL)
        for (t in all) assertFalse(t, t.contains("already there", ignoreCase = true))
    }

    @Test
    fun theOtherCountTextsAreUnchanged() {
        assertEquals("Nothing new", summaryCountsText(summary(0, 0)))
        assertEquals("4 synced", summaryCountsText(summary(4, 0)))
        assertEquals("4 synced", runCountsText(run(4, 0)))
    }
}
