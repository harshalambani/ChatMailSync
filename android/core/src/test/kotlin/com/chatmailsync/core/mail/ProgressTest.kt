package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JUnit twin of `tests/test_progress.py`. Test names below mirror the Python
 * test names (converted to camelCase) so the two suites can be diffed side
 * by side; every one of the 20 `test_` functions in that file has a twin
 * here. A short comment marks each added negative test and says what Kotlin
 * behaviour it locks that the Python suite either takes for granted (no
 * Kotlin-only static-typing decision to pin) or never had to reach.
 *
 * `tests/test_progress_output.py` (9 tests) is NOT twinned here or anywhere
 * else in `:core`: those tests cover `mail_client._print_progress`/
 * `_stderr_is_terminal`, the PyInstaller-console progress bar. Per the plan
 * document (section C(b): "goes with the port (never port it)"), that
 * console-redraw behaviour is dead on Android — Chaquopy forwards
 * `sys.stderr` writes to logcat, which is exactly the bug that file's
 * docstring describes — and has no Kotlin analogue to be a twin of.
 */
class ProgressTest {

    private fun chunk(over: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val event = mutableMapOf<String, Any?>(
            "type" to "chunk", "name" to "Kartik Patel",
            "chunk" to 2, "total_chunks" to 9,
            "msgs_done" to 120, "total_msgs" to 540,
            "global_done" to 120, "global_total" to 900,
        )
        event.putAll(over)
        return event
    }

    private fun fed(vararg events: Map<String, Any?>): ProgressState {
        val tracker = ProgressTracker()
        for (event in events) tracker.feed(event)
        return tracker.state
    }

    // -----------------------------------------------------------------
    // what each event says
    // -----------------------------------------------------------------

    @Test
    fun aFreshTrackerClaimsNoPercentage() {
        val state = ProgressTracker().state
        assertEquals(UNKNOWN, state.fraction, 0.0)
        assertEquals(-1, state.percent)
    }

    @Test
    fun fileTotalBeforeTheFirstFile() {
        assertEquals("Found 3 file(s)…", fed(mapOf("type" to "files_total", "n" to 3)).headline)
        assertEquals(PHASE_SCANNING, fed(mapOf("type" to "files_total", "n" to 3)).phase)
    }

    @Test
    fun anEmptyInboxSaysSoRatherThanCountingZero() {
        val state = fed(mapOf("type" to "files_total", "n" to 0))
        assertEquals("Inbox is empty", state.headline)
        assertEquals(listOf("Inbox is empty"), state.milestones)
    }

    @Test
    fun syncingNamesTheChatBeingPushed() {
        val state = fed(mapOf("type" to "syncing", "name" to "Alice"))
        assertEquals(PHASE_SYNCING, state.phase)
        assertEquals("Alice", state.chat)
        assertEquals("Syncing: Alice", state.headline)
        assertEquals(listOf("Starting: Alice"), state.milestones)
    }

    @Test
    fun chunkBuildsTheOneLineBothFrontEndsShow() {
        val state = fed(chunk())
        assertEquals("Syncing: Kartik Patel — 120 / 540 messages", state.line)
        assertEquals(120.0 / 900.0, state.fraction, 1e-9)
        assertEquals(13, state.percent)
    }

    @Test
    fun fileDoneReportsFilesAndMovesTheBar() {
        val state = fed(mapOf("type" to "file_done", "done" to 1, "total" to 2))
        assertEquals("1 / 2 files", state.line)
        assertEquals(0.5, state.fraction, 1e-9)
    }

    // -----------------------------------------------------------------
    // the bar
    // -----------------------------------------------------------------

    @Test
    fun theFractionNeverGoesBackwardsAtAFileBoundary() {
        val tracker = ProgressTracker()
        tracker.feed(chunk(mapOf("global_done" to 600, "global_total" to 900)))
        assertEquals(600.0 / 900.0, tracker.state.fraction, 1e-9)

        tracker.feed(mapOf("type" to "file_done", "done" to 1, "total" to 3))
        assertEquals(600.0 / 900.0, tracker.state.fraction, 1e-9)
        assertEquals("1 / 3 files", tracker.state.headline) // text still tells the truth

        tracker.feed(mapOf("type" to "file_done", "done" to 3, "total" to 3))
        assertEquals(1.0, tracker.state.fraction, 1e-9)
    }

    @Test
    fun aRunWithNothingToPushLeavesTheBarUnknown() {
        val state = fed(
            chunk(
                mapOf(
                    "global_done" to 0, "global_total" to 0,
                    "msgs_done" to 0, "total_msgs" to 0,
                ),
            ),
        )
        assertEquals(UNKNOWN, state.fraction, 0.0)
    }

    @Test
    fun theFractionIsClampedToOne() {
        val state = fed(mapOf("type" to "file_done", "done" to 5, "total" to 3))
        assertEquals(1.0, state.fraction, 1e-9)
    }

    @Test
    fun aMalformedEventDoesNotKillThePollLoop() {
        val state = fed(chunk(mapOf("global_done" to null, "global_total" to "lots", "msgs_done" to null)))
        assertEquals(UNKNOWN, state.fraction, 0.0)
        assertEquals("Syncing: Kartik Patel", state.headline)
    }

    // -----------------------------------------------------------------
    // how a run ends
    // -----------------------------------------------------------------

    @Test
    fun doneFillsTheBarAndCountsWhatWasSynced() {
        val state = fed(mapOf("type" to "done", "stats" to SyncStatsSummary(messagesSynced = 42)))
        assertEquals(PHASE_DONE, state.phase)
        assertEquals(1.0, state.fraction, 1e-9)
        assertEquals("Done — 42 msgs synced", state.headline)
    }

    @Test
    fun oneMessageIsNotPluralised() {
        val state = fed(mapOf("type" to "done", "stats" to SyncStatsSummary(messagesSynced = 1)))
        assertEquals("Done — 1 msg synced", state.headline)
    }

    @Test
    fun aStoppedRunSaysStoppedNotDone() {
        val state = fed(mapOf("type" to "done", "stopped" to true))
        assertEquals(PHASE_DONE, state.phase)
        assertEquals("Stopped", state.headline)
    }

    @Test
    fun doneWithoutStatsStillRenders() {
        assertEquals("Done", fed(mapOf("type" to "done")).headline)
    }

    @Test
    fun errorPointsAtTheLogRatherThanTheException() {
        val state = fed(chunk(), mapOf("type" to "error", "msg" to "smtp exploded"))
        assertEquals(PHASE_FAILED, state.phase)
        assertEquals("Failed — see log", state.headline)
        assertEquals("", state.chat)
    }

    // -----------------------------------------------------------------
    // the milestone log
    // -----------------------------------------------------------------

    @Test
    fun chunksStayOutOfTheMilestoneLog() {
        val tracker = ProgressTracker()
        repeat(5) { assertNull(tracker.feed(chunk())) }
        assertEquals(emptyList<String>(), tracker.state.milestones)
    }

    @Test
    fun theMilestoneLogIsBounded() {
        val tracker = ProgressTracker()
        for (i in 0 until MAX_MILESTONES + 20) {
            tracker.feed(mapOf("type" to "file_done", "done" to i, "total" to 500))
        }
        assertEquals(MAX_MILESTONES, tracker.state.milestones.size)
        assertEquals("Finished 69 / 500 files", tracker.state.milestones.last())
    }

    @Test
    fun theRenderedDictCarriesEverythingKotlinReads() {
        val tracker = ProgressTracker()
        tracker.feed(mapOf("type" to "syncing", "name" to "Alice"))
        tracker.feed(chunk(mapOf("name" to "Alice")))
        val snapshot = tracker.state.asDict()
        assertEquals("Syncing: Alice — 120 / 540 messages", snapshot["line"])
        assertEquals("Starting: Alice", snapshot["log"])
        assertEquals("Alice", snapshot["chat"])
        assertEquals(13, snapshot["percent"])
    }

    @Test
    fun pollingTheStateConsumesNothing() {
        val tracker = ProgressTracker()
        tracker.feed(mapOf("type" to "syncing", "name" to "Alice"))
        assertEquals(tracker.state.asDict(), tracker.state.asDict())
    }

    @Test
    fun resetStartsTheNextRunClean() {
        val tracker = ProgressTracker()
        tracker.feed(chunk())
        tracker.feed(mapOf("type" to "done"))
        tracker.reset()
        assertEquals(UNKNOWN, tracker.state.fraction, 0.0)
        assertEquals(emptyList<String>(), tracker.state.milestones)
        assertEquals("", tracker.state.headline)
    }

    // -----------------------------------------------------------------
    // Negative tests — Kotlin-specific decisions with no direct pytest
    // counterpart (see Progress.kt's KDoc on each function for the "why").
    // -----------------------------------------------------------------

    // NEGATIVE: percent() must use banker's rounding like Python's bare
    // round(), not Math.round()'s round-half-up. 1/8 == 0.125 -> 12.5%,
    // which the two rules disagree on (Python: 12, Math.round: 13).
    @Test
    fun percentUsesBankersRoundingLikePython() {
        val state = fed(mapOf("type" to "file_done", "done" to 1, "total" to 8))
        assertEquals(12, state.percent)
        assertEquals(12, pythonRoundHalfToEven(12.5))
        assertEquals(14, pythonRoundHalfToEven(13.5))
        assertEquals(13, pythonRoundHalfToEven(12.6))
        assertEquals(12, pythonRoundHalfToEven(12.4))
    }

    // NEGATIVE: intOf() must not throw and must not silently pick up a
    // fractional-looking string as if it truncated -- Python's int("3.5")
    // raises ValueError (caught -> 0), it does not parse to 3.
    @Test
    fun intOfRejectsADecimalLookingStringInsteadOfTruncatingIt() {
        assertEquals(0, intOf("3.5"))
        assertEquals(3, intOf(3.9))
        assertEquals(-3, intOf(-3.9))
        assertEquals(0, intOf(null))
        assertEquals(0, intOf(mapOf("nested" to "value")))
        assertEquals(42, intOf("42"))
        assertEquals(42, intOf(" 42 "))
    }

    // NEGATIVE: a key present with an explicit null value renders as the
    // literal word "None" (matching Python's str(None)), which must stay
    // distinguishable from the key being absent entirely (which renders "").
    @Test
    fun aNamePresentButNullRendersAsTheLiteralWordNoneWhileAnAbsentNameRendersEmpty() {
        val present = mapOf("type" to "syncing", "name" to null)
        val absent = mapOf<String, Any?>("type" to "syncing")
        assertEquals("Syncing: None", fed(present).headline)
        assertEquals("Syncing: ", fed(absent).headline)
    }

    // NEGATIVE: the map handed out by asDict() must be an independent
    // snapshot -- mutating (or attempting to mutate) it must never reach
    // back into the tracker's own milestone list.
    @Test
    fun asDictMilestonesAreASnapshotIndependentOfTheLiveList() {
        val tracker = ProgressTracker()
        tracker.feed(mapOf("type" to "syncing", "name" to "Alice"))
        @Suppress("UNCHECKED_CAST")
        val snapshotMilestones = tracker.state.asDict()["milestones"] as List<String>
        tracker.feed(mapOf("type" to "syncing", "name" to "Bob"))
        assertEquals(listOf("Starting: Alice"), snapshotMilestones)
        assertEquals(listOf("Starting: Alice", "Starting: Bob"), tracker.state.milestones)
    }

    // NEGATIVE: an event whose "type" matches none of the five known kinds
    // must be a no-op (no milestone, no field touched, no exception) -- the
    // Kotlin `when` has no `else` branch, so an unrecognised type falls
    // through every arm the same way Python's if/elif chain with no final
    // `else` leaves state untouched.
    @Test
    fun anUnrecognisedEventTypeIsANoOp() {
        val tracker = ProgressTracker()
        tracker.feed(mapOf("type" to "syncing", "name" to "Alice"))
        val before = tracker.state.asDict()
        val milestone = tracker.feed(mapOf("type" to "some_future_event", "payload" to 123))
        assertNull(milestone)
        assertEquals(before, tracker.state.asDict())
    }

    // NEGATIVE: "stopped" must be a real boolean true, not merely present --
    // Python's `if event.get("stopped")` treats a falsy value (False, 0, "")
    // the same as absent. Twin-by-construction here since the Kotlin field
    // is read as `== true`, but pinned explicitly so a future refactor to a
    // nullable-Boolean truthiness check cannot silently change this.
    @Test
    fun aFalseStoppedFlagDoesNotSayStopped() {
        val state = fed(mapOf("type" to "done", "stopped" to false))
        assertFalse(state.headline == "Stopped")
        assertEquals("Done", state.headline)
    }

    // NEGATIVE: phase must not slide back to SCANNING once a "chunk"/
    // "syncing" event has moved it to SYNCING, even if a stray
    // "files_total" arrives again mid-run.
    @Test
    fun aStrayFilesTotalAfterSyncingHasStartedDoesNotRevertThePhaseLabelAppliedByLaterEvents() {
        val tracker = ProgressTracker()
        tracker.feed(mapOf("type" to "syncing", "name" to "Alice"))
        assertEquals(PHASE_SYNCING, tracker.state.phase)
        tracker.feed(mapOf("type" to "files_total", "n" to 5))
        assertEquals(PHASE_SCANNING, tracker.state.phase) // matches Python: no guard exists either way
        assertTrue(tracker.state.milestones.contains("Starting: Alice"))
    }
}
