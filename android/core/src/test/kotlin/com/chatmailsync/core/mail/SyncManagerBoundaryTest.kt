package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The error-text boundary and the attachment rule, driven through the whole
 * run loop. The "password" below is a made-up placeholder, not a credential.
 */
class SyncManagerBoundaryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val secret = "hunter2xyz"

    private fun chatText(vararg days: Int): String =
        days.joinToString("\n") { "%02d/03/25, 09:00 - Meera Iyer: message $it".format(it) } + "\n"

    private class Setup(val root: File, val inbox: File, val processed: File, val db: String)

    private fun setup(): Setup {
        val root = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        return Setup(root, inbox, File(root, "processed"), File(root, "state.db").absolutePath)
    }

    private fun storedErrors(db: String): List<String?> =
        SyncRunSupport.rows(db, "SELECT error_message FROM sync_runs ORDER BY run_id", listOf("error_message"))
            .map { it[0] }

    @Test
    fun pushFailureTextNeverCarriesThePassword_inErrorsStoredRowOrProgress() {
        val s = setup()
        File(s.inbox, "WhatsApp Chat with Meera Iyer.txt").writeText(chatText(1, 2))
        val events = mutableListOf<Map<String, Any?>>()
        // Both the bare form and the quoted form a server may echo back.
        val transport = CountingTransport(1, "LOGIN meera $secret rejected; sent LOGIN \"meera\" \"$secret\"")
        val stats = SyncManager(
            repo = newRepo(s.root, "state.db"), transport = transport, inboxDir = s.inbox, processedDir = s.processed,
            onProgress = { events.add(it) }, redact = secret, sleeper = { },
        ).run()

        assertEquals(1, stats.filesFailed)
        assertTrue("the failure is still reported", stats.errors.single().contains("Mail push failed"))
        assertFalse(stats.errors.joinToString("\n").contains(secret))
        assertFalse(stats.toString().contains(secret))
        val stored = storedErrors(s.db).single()
        assertNotNull(stored)
        assertFalse("stored row: $stored", stored!!.contains(secret))
        assertTrue("the rest of the text is kept", stored.contains("rejected"))
        assertFalse(events.joinToString("\n").contains(secret))
    }

    @Test
    fun withoutAPasswordGivenTheTextIsStoredAsPythonStoresIt() {
        // Negative: nothing is removed when there is nothing to remove, so the
        // scrub does not change what a plain error looks like.
        val s = setup()
        File(s.inbox, "WhatsApp Chat with Meera Iyer.txt").writeText(chatText(1))
        SyncManager(
            repo = newRepo(s.root, "state.db"), transport = CountingTransport(1, "simulated crash mid-push"),
            inboxDir = s.inbox, processedDir = s.processed, sleeper = { },
        ).run()
        assertEquals("simulated crash mid-push", storedErrors(s.db).single())
    }

    @Test
    fun progressStringsAreScrubbedToo() {
        val s = setup()
        // A chat whose own name holds the placeholder reaches the "syncing" and "chunk" events.
        File(s.inbox, "WhatsApp Chat with $secret.txt").writeText(chatText(1))
        val events = mutableListOf<Map<String, Any?>>()
        SyncManager(
            repo = newRepo(s.root, "state.db"), transport = CountingTransport(), inboxDir = s.inbox,
            processedDir = s.processed, onProgress = { events.add(it) }, redact = secret, sleeper = { },
        ).run()
        assertTrue(events.any { it["type"] == "syncing" })
        assertFalse(events.joinToString("\n") { SyncRunSupport.eventText(it) }.contains(secret))
    }

    @Test
    fun anAbortedRunThrowsATextWithoutThePasswordAndWithoutACause() {
        val s = setup()
        val name = "WhatsApp Chat with $secret.txt"
        File(s.inbox, name).writeText(chatText(1))
        // A non-empty folder where the finished file must go makes the move fail.
        File(s.processed, name).apply { mkdirs() }.resolve("keep.txt").writeText("x")
        val mgr = SyncManager(
            repo = newRepo(s.root, "state.db"), transport = CountingTransport(), inboxDir = s.inbox,
            processedDir = s.processed, redact = secret, sleeper = { },
        )
        try {
            mgr.run()
            fail("the move should have failed")
        } catch (e: SyncAbortedException) {
            assertFalse(e.message!!, e.message!!.contains(secret))
            assertNull("no wrapped exception may carry the text back", e.cause)
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun theSameAbortWithoutAPasswordGivenKeepsTheText() {
        // Negative: the scrub is what removes the placeholder, not the wrapping.
        val s = setup()
        val name = "WhatsApp Chat with $secret.txt"
        File(s.inbox, name).writeText(chatText(1))
        File(s.processed, name).apply { mkdirs() }.resolve("keep.txt").writeText("x")
        try {
            SyncManager(
                repo = newRepo(s.root, "state.db"), transport = CountingTransport(), inboxDir = s.inbox,
                processedDir = s.processed, sleeper = { },
            ).run()
            fail("the move should have failed")
        } catch (e: SyncAbortedException) {
            assertTrue(e.message!!.contains(secret))
        }
    }

    @Test
    fun aMissingTransportIsAPerFileFailureNotACrash() {
        val s = setup()
        File(s.inbox, "WhatsApp Chat with Meera Iyer.txt").writeText(chatText(1))
        val stats = SyncManager(
            repo = newRepo(s.root, "state.db"), transport = null, inboxDir = s.inbox, processedDir = s.processed,
            sleeper = { },
        ).run()
        assertEquals(1, stats.filesFailed)
        assertTrue(File(s.inbox, "WhatsApp Chat with Meera Iyer.txt").exists())
    }

    @Test
    fun d35_attachmentWithALineBreakInItsNameIsSkippedAndTheRestStillGoes() {
        // Deliberate difference from Python, which fails the whole chat here.
        val s = setup()
        val zip = File(s.inbox, "WhatsApp Chat with Meera Iyer.zip")
        val bad = "IMG-1\nX.jpg"
        SyncRunSupport.writeZip(
            zip,
            mapOf(
                "_chat.txt" to "01/03/25, 09:00 - Meera Iyer: first\n" +
                    "02/03/25, 09:00 - Rohan Mehta: <attached: $bad>\n" +
                    "03/03/25, 09:00 - Meera Iyer: third\n",
                bad to "not really an image",
            ),
        )
        val transport = CountingTransport()
        val stats = SyncManager(
            repo = newRepo(s.root, "state.db"), transport = transport, inboxDir = s.inbox,
            processedDir = s.processed, sleeper = { },
        ).run()
        assertEquals("the chat is not failed", 0, stats.filesFailed)
        assertEquals(1, stats.filesSynced)
        assertEquals(3, stats.messagesSynced)
        assertTrue("three days, three mails", transport.insertCalls == 3)
        assertTrue(File(s.processed, zip.name).exists())
    }
}
