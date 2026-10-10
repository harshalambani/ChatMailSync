package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.golden
import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Recovery of runs a crash left pending, against the real Python 3.13 code
 * (`sync_recovery_golden.json`), plus the places where this port keeps Python's
 * behaviour on purpose and the error-text boundary on the recovery paths.
 */
class SyncRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val secret = "hunter2xyz"
    private val fileName = "WhatsApp Chat with Meera Iyer.txt"

    @Test
    fun everyRecoveryScenarioMatchesPython() {
        SyncRunSupport.checkScenarios(tmp, golden("sync_recovery_golden.json"))
    }

    private fun chatText(vararg days: Int): String =
        days.joinToString("\n") { "%02d/03/25, 09:00 - Meera Iyer: message $it".format(it) } + "\n"

    private class Setup(val root: File, val inbox: File, val processed: File, val db: String)

    private fun setup(): Setup {
        val root = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        return Setup(root, inbox, File(root, "processed"), File(root, "state.db").absolutePath)
    }

    private fun manager(s: Setup, transport: MailTransport?, redact: String? = null) = SyncManager(
        repo = newRepo(s.root, "state.db"), transport = transport, inboxDir = s.inbox,
        processedDir = s.processed, redact = redact, sleeper = { },
    )

    /** Leaves a pending run behind: the second chunk's write kills the "process". */
    private fun crashFirstRun(s: Setup, text: String, name: String = fileName) {
        File(s.inbox, name).writeText(text)
        try {
            manager(s, CountingTransport(crashAt = 2)).run()
            fail("the simulated process death should escape run()")
        } catch (_: ProcessDeath) {
            // expected
        }
    }

    private fun storedErrors(db: String): List<String?> =
        SyncRunSupport.rows(db, "SELECT error_message FROM sync_runs ORDER BY run_id", listOf("error_message"))
            .map { it[0] }

    // -- Python behaviour kept on purpose, not as a fix (8.2) ---------------------------------

    @Test
    fun pythonBehaviourKeptOnPurpose_recoveryDoesNotApplyTheOverlapRule() {
        val s = setup()
        // A completed run puts the overlap mark at day 23.
        File(s.inbox, fileName).writeText(chatText(23))
        manager(s, CountingTransport()).run()
        // A pending run for older, still unsent days (made by hand: no normal run can leave one).
        SqliteJdbcStateDb(s.db).use {
            it.exec(
                "INSERT INTO sync_runs (chat_id, status, trigger, started_at) " +
                    "VALUES ('meera_iyer', 'pending', 'manual', '2025-01-01T00:00:00')",
                emptyList(),
            )
        }
        File(s.inbox, fileName).writeText(chatText(20, 21, 23))
        val transport = CountingTransport()
        val stats = manager(s, transport).run()
        // Days 20 and 21 are at or before the overlap mark, yet recovery sends them.
        assertEquals(2, transport.insertCalls)
        assertEquals(2, stats.messagesSynced)
        assertEquals(1, stats.chatsRecovered)
    }

    @Test
    fun pythonBehaviourKeptOnPurpose_recoveryDoesNotCountFilesSyncedOrMessagesParsed() {
        val s = setup()
        crashFirstRun(s, chatText(20, 21, 22, 23))
        val stats = manager(s, CountingTransport()).run()
        assertEquals(1, stats.chatsRecovered)
        assertEquals("the resumed messages are counted", 3, stats.messagesSynced)
        assertEquals("a recovered file is not a synced file", 0, stats.filesSynced)
        assertEquals("a recovered file is not a parsed file", 0, stats.messagesParsed)
        assertEquals("the inbox was emptied by recovery itself", 0, stats.filesFound)
    }

    // -- Negatives ------------------------------------------------------------------------------

    @Test
    fun aRecoveredRunIsNotResentOnTheNextRun() {
        val s = setup()
        crashFirstRun(s, chatText(20, 21, 22, 23))
        manager(s, CountingTransport()).run()
        val again = CountingTransport()
        val stats = manager(s, again).run()
        assertEquals(0, again.insertCalls)
        assertEquals(0, stats.chatsRecovered)
        assertEquals(0, stats.messagesSynced)
    }

    @Test
    fun aMessageAlreadyDeliveredBeforeTheCrashIsNeverSentAgain() {
        val s = setup()
        crashFirstRun(s, chatText(20, 21, 22, 23))
        val transport = CountingTransport()
        manager(s, transport).run()
        // Day 20 went out before the crash; only 21, 22 and 23 are sent now.
        assertEquals(3, transport.insertCalls)
        val hashes = SyncRunSupport.rows(s.db, "SELECT hash FROM message_hashes", listOf("hash"))
        assertEquals(4, hashes.size)
    }

    @Test
    fun dryRunRecoveryWritesNothing() {
        val s = setup()
        crashFirstRun(s, chatText(20, 21, 22, 23))
        val transport = CountingTransport()
        val stats = SyncManager(
            repo = newRepo(s.root, "state.db"), transport = transport, inboxDir = s.inbox,
            processedDir = s.processed, dryRun = true, sleeper = { },
        ).run()
        assertEquals(0, transport.insertCalls)
        assertEquals(1, stats.chatsRecovered)
        assertTrue(File(s.inbox, fileName).exists())
        assertEquals(listOf("pending"), SyncRunSupport.rows(s.db, "SELECT status FROM sync_runs", listOf("status")).map { it[0] })
    }

    // -- Error text boundary on the recovery paths ----------------------------------------------

    @Test
    fun recoveryPushFailureTextNeverCarriesThePassword() {
        val s = setup()
        crashFirstRun(s, chatText(20, 21, 22, 23))
        val stats = manager(s, CountingTransport(1, "LOGIN meera $secret rejected, LOGIN \"meera\" \"$secret\""), secret).run()
        assertEquals(1, stats.filesFailed)
        val stored = storedErrors(s.db).filterNotNull().joinToString("\n")
        assertTrue(stored, stored.startsWith("recovery push failed:"))
        assertFalse(stored, stored.contains(secret))
        assertFalse(stats.errors.joinToString("\n").contains(secret))
    }

    @Test
    fun recoverySourceMissingAndParseErrorTextsAreScrubbedToo() {
        val s = setup()
        val name = "WhatsApp Chat with $secret.txt"
        crashFirstRun(s, chatText(20, 21, 22, 23), name)
        File(s.inbox, name).delete()
        manager(s, CountingTransport(), secret).run()
        val stored = storedErrors(s.db).filterNotNull().single()
        assertTrue(stored, stored.startsWith("source file not found:"))
        assertFalse(stored, stored.contains(secret))
    }

    @Test
    fun withoutAPasswordGivenTheRecoveryTextIsStoredAsPythonStoresIt() {
        val s = setup()
        crashFirstRun(s, chatText(20, 21, 22, 23))
        manager(s, CountingTransport(1, "simulated crash mid-push")).run()
        assertEquals("recovery push failed: simulated crash mid-push", storedErrors(s.db).filterNotNull().single())
    }
}
