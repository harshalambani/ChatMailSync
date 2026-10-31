package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** UX-06: Home's red line counts only failures since the Sync log was last opened. */
class FailedSinceTest {

    private val seen = "2026-10-30T12:00:00"

    private fun run(id: Long, status: String, completed: String?, started: String? = "2026-10-01T00:00:00") =
        SyncRunLogEntry(
            runId = id, chatId = "c$id", displayName = "Meera Iyer", status = status,
            trigger = "manual", messagesParsed = 0, messagesSynced = 0, messagesSkipped = 0,
            messagesCutoff = 0, errorMessage = null, startedAt = started, completedAt = completed,
            lastSyncedTs = null, uneventful = false,
        )

    // NEGATIVE: a failure the user has already seen must not be counted.
    @Test
    fun failureCompletedBeforeSeenIsNotCounted() {
        assertEquals(0, failedSince(listOf(run(1, "failed", "2026-10-30T11:59:59")), seen))
    }

    @Test
    fun failureAfterSeenIsCounted() {
        assertEquals(1, failedSince(listOf(run(1, "failed", "2026-10-30T12:00:01")), seen))
    }

    // NEGATIVE: a later success must not hide or reduce an unseen failure.
    @Test
    fun laterSuccessDoesNotClearAnEarlierUnseenFailure() {
        val rows = listOf(
            run(2, "complete", "2026-10-31T09:00:00"),
            run(1, "failed", "2026-10-30T13:00:00"),
        )
        assertEquals(1, failedSince(rows, seen))
    }

    // Never opened: behaves as before, every failure counts.
    @Test
    fun noSeenAtCountsEveryFailure() {
        val rows = listOf(run(1, "failed", "2026-09-01T00:00:00"), run(2, "failed", "2026-10-31T00:00:00"), run(3, "complete", "2026-10-31T01:00:00"))
        assertEquals(2, failedSince(rows, null))
    }

    // NEGATIVE: an unparseable time is counted, never silently dropped.
    @Test
    fun unparseableTimestampIsCounted() {
        assertEquals(1, failedSince(listOf(run(1, "failed", "not a time")), seen))
        assertEquals(1, failedSince(listOf(run(2, "failed", null, started = null)), seen))
    }

    // A run with no finish time falls back to its start time.
    @Test
    fun missingCompletedUsesStartedAt() {
        assertEquals(0, failedSince(listOf(run(1, "failed", null, started = "2026-10-30T11:00:00")), seen))
        assertEquals(1, failedSince(listOf(run(2, "failed", null, started = "2026-10-30T12:30:00")), seen))
    }

    // NEGATIVE: only failures count - stopped and running runs after seenAt do not.
    @Test
    fun nonFailedRunsAreNeverCounted() {
        val rows = listOf(run(1, "stopped", "2026-10-31T00:00:00"), run(2, "running", null, started = "2026-10-31T00:00:00"))
        assertEquals(0, failedSince(rows, seen))
    }

    @Test
    fun lineWordingBeforeAndAfterFirstVisit() {
        assertEquals("3 runs failed in the last 90 days", failedRunsLine(90, 3, null, 0))
        assertEquals("1 run failed since you last checked", failedRunsLine(90, 3, seen, 1))
        // NEGATIVE: opened and nothing new -> no line, even though the window has failures.
        assertNull(failedRunsLine(90, 3, seen, 0))
        assertNull(failedRunsLine(90, 0, null, 0))
    }
}
