package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BUG-07: getLastSyncedTs is the MAXIMUM non-empty time across a chat's completed runs,
 * so a recovered run that closes with an older time can never step the baseline back.
 *
 * DELIBERATE DIFFERENCE from Python: state.py:get_last_synced_ts (frozen, D38) still reads
 * the latest non-empty run by run_id. The BUG-04 scenarios in StateLastSyncedTsTest are
 * monotone, so both implementations still agree on them.
 */
class StateMaxLastSyncedTsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val t1 = "2025-03-05T10:00:00"
    private val t2 = "2025-03-10T10:00:00"
    private val t3 = "2025-03-15T10:00:00"

    private fun repo() = newRepo(tmp.newFolder()).also {
        it.upsertChat("meera_iyer", "Meera Iyer", "WhatsApp Chat with Meera Iyer.txt")
        it.upsertChat("rohan_mehta", "Rohan Mehta", "WhatsApp Chat with Rohan Mehta.txt")
    }

    private fun StateRepository.complete(chat: String, ts: String?) {
        completeSyncRun(startSyncRun(chat), ts, null, 1, 1, 0)
    }

    @Test
    fun aRecoveredRunWithAnOlderTimeDoesNotStepTheBaselineBack() {
        val r = repo()
        r.complete("meera_iyer", t2)
        r.complete("meera_iyer", t1) // the recovered run, closed with an older time
        assertEquals(t2, r.getLastSyncedTs("meera_iyer"))
    }

    // NEGATIVE: a genuinely newer later run still moves the baseline forward.
    @Test
    fun aGenuinelyNewerLaterRunMovesTheBaselineForward() {
        val r = repo()
        r.complete("meera_iyer", t2)
        r.complete("meera_iyer", t1)
        r.complete("meera_iyer", t3)
        assertEquals(t3, r.getLastSyncedTs("meera_iyer"))
    }

    // NEGATIVE: empty, failed and pending runs never blank or move it.
    @Test
    fun emptyFailedAndPendingRunsNeverBlankOrMoveIt() {
        val r = repo()
        r.complete("meera_iyer", t2)
        r.complete("meera_iyer", null)
        r.complete("meera_iyer", "")
        r.failSyncRun(r.startSyncRun("meera_iyer"), "boom")
        r.startSyncRun("meera_iyer") // left pending
        assertEquals(t2, r.getLastSyncedTs("meera_iyer"))
    }

    // NEGATIVE: another chat's runs never affect this chat's value.
    @Test
    fun otherChatsRunsNeverAffectThisChat() {
        val r = repo()
        r.complete("meera_iyer", t1)
        r.complete("rohan_mehta", t3)
        assertEquals(t1, r.getLastSyncedTs("meera_iyer"))
        assertEquals(t3, r.getLastSyncedTs("rohan_mehta"))
    }

    // NEGATIVE: no usable history is null.
    @Test
    fun noUsableHistoryIsNull() {
        val r = repo()
        r.complete("meera_iyer", null)
        assertNull(r.getLastSyncedTs("meera_iyer"))
        assertNull(r.getLastSyncedTs("no_such_chat"))
    }

    // A reset deletes the chat's runs, so the baseline legitimately drops to null.
    @Test
    fun resetChatClearsTheBaselineSoMaxDoesNotResurrectAnOldTime() {
        val r = repo()
        r.complete("meera_iyer", t2)
        r.resetChat("meera_iyer", confirmedMailboxCleared = true)
        assertNull(r.getLastSyncedTs("meera_iyer"))
        r.complete("meera_iyer", t1)
        assertEquals(t1, r.getLastSyncedTs("meera_iyer"))
    }

    // END TO END: after a stepped-back recovered run, an older message the hash check
    // misses is NOT sent again, and a newer one IS.
    @Test
    fun endToEnd_olderMessageIsNotResentAndNewerOneIsSent() {
        val root = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        val processed = File(root, "processed")
        val r = newRepo(root, "state.db")
        r.upsertChat("meera_iyer", "Meera Iyer", "WhatsApp Chat with Meera Iyer.txt")
        r.complete("meera_iyer", t2)
        r.complete("meera_iyer", t1)
        File(inbox, "WhatsApp Chat with Meera Iyer.txt").writeText(
            "08/03/25, 09:00 - Meera Iyer: older than the baseline\n" +
                "12/03/25, 09:00 - Meera Iyer: newer than the baseline\n",
        )
        val transport = CountingTransport()
        val stats = SyncManager(
            repo = r, transport = transport, inboxDir = inbox, processedDir = processed, sleeper = { },
        ).run()
        assertEquals("only the newer message is sent", 1, stats.messagesSynced)
        assertEquals(1, transport.insertCalls)
        assertEquals(1, stats.messagesSkipped)
    }
}
