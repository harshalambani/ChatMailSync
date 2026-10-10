package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.golden
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The run loop against the real `src/sync_manager.py` (ProgressSyncManager) on
 * Python 3.13: the same files, the same deterministic transport, the same
 * options. `sync_run_golden.json` holds, per step, the stats, the progress
 * events, the number of mailbox writes and what the run left in the state
 * store and in the two folders. Clock values are the only thing left out.
 */
class SyncRunGoldenParityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun everyScenarioMatchesPython() {
        SyncRunSupport.checkScenarios(tmp, golden("sync_run_golden.json"))
    }
}
