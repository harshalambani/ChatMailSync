package com.chatmailsync.core.mail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BUG-04 parity: reads the SAME scenarios database as Python's
 * `tests/test_state.py` (`tests/fixtures/state_last_synced_ts_scenarios.db`), so both
 * implementations are proven to give one answer on one file.
 */
class StateLastSyncedTsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun repoOn(fixtureName: String): StateRepository {
        val src = File(repoRoot(), "tests/fixtures/$fixtureName")
        val dest = File(tmp.newFolder(), "sync_state.db")
        src.copyTo(dest)
        return StateRepository { SqliteJdbcStateDb(dest.absolutePath) }
    }

    private fun scenarios() = repoOn("state_last_synced_ts_scenarios.db")

    @Test
    fun theLatestCompletedRunWithATimeWins() {
        assertEquals("2025-03-12T10:00:00", scenarios().getLastSyncedTs("c_normal"))
    }

    // NEGATIVE: completed runs that synced nothing (empty, NULL) do not blank the time.
    @Test
    fun completedRunsThatSyncedNothingDoNotBlankTheTime() {
        val repo = scenarios()
        assertNull("the old rule's answer is the gap", repo.getLastSuccessfulRun("c_gap")!!.lastSyncedTs)
        assertEquals("2025-03-10T10:00:00", repo.getLastSyncedTs("c_gap"))
    }

    // NEGATIVE: a later failed or pending run never blanks or moves it.
    @Test
    fun aLaterFailedOrPendingRunNeverBlanksOrMovesTheTime() {
        assertEquals("2025-03-10T10:00:00", scenarios().getLastSyncedTs("c_later_failed"))
    }

    // NEGATIVE: no completed history is null, not a crash.
    @Test
    fun noCompletedHistoryIsNullNotACrash() {
        val repo = scenarios()
        assertNull(repo.getLastSyncedTs("c_no_history"))
        assertNull(repo.getLastSyncedTs("c_blank_only"))
        assertNull(repo.getLastSyncedTs("no_such_chat"))
    }

    // Existing 2.2.0 data reads unchanged: the Kotlin-written fixture.
    @Test
    fun existing220StateDataReadsUnchanged() {
        val repo = repoOn("state_fixture_kotlin_written.db")
        assertEquals("2025-03-14T09:41:30", repo.getLastSuccessfulRun("chat_priya")!!.lastSyncedTs)
        assertEquals("2025-03-14T09:41:30", repo.getLastSyncedTs("chat_priya"))
        assertNull(repo.getLastSyncedTs("chat_rohan"))
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "tests/fixtures/state_last_synced_ts_scenarios.db").isFile) return dir!!
            dir = dir?.parentFile
        }
        error("could not find tests/fixtures from ${System.getProperty("user.dir")}")
    }
}
