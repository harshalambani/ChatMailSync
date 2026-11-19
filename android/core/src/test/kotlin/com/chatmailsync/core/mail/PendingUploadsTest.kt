package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * D177/D185: an upload whose outcome was never learned (cut connection, timeout, Stop mid-upload) is
 * remembered in the state database, so the NEXT run asks the server before sending it again.
 */
class PendingUploadsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileName = "WhatsApp Chat with Meera Iyer.txt"

    private class Server {
        val stored = mutableListOf<String>()
        val log = mutableListOf<String>()
        val appendScript = ArrayDeque<String>() // ok | abort | store-io
        val lookupScript = ArrayDeque<String>() // ok | io
        fun appends() = log.count { it == "APPEND" }
        fun lookups() = log.count { it.startsWith("SEARCH") }

        fun connect(): ImapConnection = object : ImapConnection {
            override val capabilities: List<String> = emptyList()
            override fun list(reference: String, pattern: String) =
                ImapResult("OK", listOf("(\\HasNoChildren) \"/\" \"INBOX\""))
            override fun create(mailboxWireArg: String) = ImapResult("OK", listOf("Completed"))
            override fun subscribe(mailboxWireArg: String) = ImapResult("OK", listOf("Completed"))
            override fun append(
                mailboxWireArg: String, flags: String?, internalDate: OffsetDateTime?, message: ByteArray,
            ): ImapResult {
                log.add("APPEND")
                val id = MessageHeaders.parse(normalizeCrlf(message))["Message-ID"].orEmpty()
                return when (appendScript.removeFirstOrNull() ?: "ok") {
                    "ok" -> { stored.add(id); ImapResult("OK", listOf("Completed")) }
                    "abort" -> throw ImapAbortError("connection dropped")
                    "store-io" -> { stored.add(id); throw IOException("connection reset") }
                    else -> error("bad step")
                }
            }
            override fun examine(mailboxWireArg: String): ImapResult {
                log.add("EXAMINE")
                return when (lookupScript.removeFirstOrNull() ?: "ok") {
                    "io" -> throw IOException("reset during lookup")
                    else -> ImapResult("OK", listOf("3 EXISTS"))
                }
            }
            override fun uidSearchMessageId(messageId: String): ImapResult {
                log.add("SEARCH")
                val uids = stored.withIndex().filter { it.value == messageId }.map { (it.index + 1).toString() }
                return ImapResult("OK", if (uids.isEmpty()) emptyList() else listOf(uids.joinToString(" ")))
            }
            override fun logout() {}
        }
    }

    private class Setup(val root: File, val inbox: File, val processed: File, val db: String)

    private fun setup(): Setup {
        val root = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        File(inbox, fileName).writeText("20/03/25, 09:00 - Meera Iyer: message one\n20/03/25, 09:05 - Rohan Mehta: message two\n")
        return Setup(root, inbox, File(root, "processed"), File(root, "state.db").absolutePath)
    }

    /** One run: a fresh transport (an empty in-memory set, as after a restart) on the same state file. */
    private fun run(s: Setup, server: Server): SyncStats {
        val transport = ImapTransport(
            host = "imap.example.com", port = 993, email = "me@example.com", password = "s3cret",
            connectionFactory = { server.connect() },
        )
        return SyncManager(
            repo = newRepo(s.root, "state.db"), transport = transport, inboxDir = s.inbox,
            processedDir = s.processed, sleeper = { },
        ).run()
    }

    private fun pendingRows(s: Setup) =
        SyncRunSupport.rows(s.db, "SELECT DISTINCT message_id FROM pending_messages", listOf("message_id"))

    // ---- the cross-run behaviour

    @Test
    fun anIdFoundOnTheServerIsNotSentAgainInTheNextRun() {
        val s = setup()
        val server = Server()
        server.appendScript.add("store-io") // the server kept it, the reply never came
        server.lookupScript.addAll(List(20) { "io" }) // and the run could not ask either
        val first = run(s, server)
        assertEquals("run 1 gave up", 1, first.filesFailed)
        assertEquals("the server holds one copy", 1, server.stored.size)
        assertEquals("the unknown outcome was written down", 1, pendingRows(s).size)
        val appendsAfterRun1 = server.appends()

        server.lookupScript.clear()
        val second = run(s, server)
        assertEquals("the second run succeeded", 0, second.filesFailed)
        assertEquals(1, second.filesSynced)
        assertEquals("found, so never re-sent", appendsAfterRun1, server.appends())
        assertEquals("still exactly one copy", 1, server.stored.size)
        assertTrue("it asked the server first", server.lookups() >= 1)
        assertTrue("nothing is pending any more", pendingRows(s).isEmpty())
    }

    @Test
    fun anIdNotOnTheServerIsSentExactlyOnceInTheNextRun() {
        val s = setup()
        val server = Server()
        server.appendScript.add("abort") // never stored
        server.lookupScript.addAll(List(20) { "io" })
        val first = run(s, server)
        assertEquals(1, first.filesFailed)
        assertEquals(0, server.stored.size)
        assertEquals(1, pendingRows(s).size)
        val appendsAfterRun1 = server.appends()

        server.lookupScript.clear()
        val second = run(s, server)
        assertEquals(1, second.filesSynced)
        assertEquals("looked up, not found, sent once", appendsAfterRun1 + 1, server.appends())
        assertEquals(1, server.stored.size)
        assertTrue(pendingRows(s).isEmpty())
    }

    // ---- negatives

    @Test
    fun aConfirmedSendLeavesNothingPersisted() {
        val s = setup()
        val server = Server()
        val stats = run(s, server)
        assertEquals(1, stats.filesSynced)
        assertEquals(1, server.stored.size)
        assertTrue(pendingRows(s).isEmpty())
    }

    @Test
    fun aFreshInstallBehavesAsBefore() {
        val s = setup()
        val server = Server()
        run(s, server)
        assertEquals("one upload", 1, server.appends())
        assertEquals("no lookup when nothing is pending", 0, server.lookups())
        assertFalse(server.log.contains("EXAMINE"))
    }

    @Test
    fun aPendingRowForAnotherSetOfMessagesIsIgnored() {
        val s = setup()
        newRepo(s.root, "state.db").also { it.initDb() }.record("some-other-chat", "<wa-sync-other@local>", listOf("deadbeef"))
        val server = Server()
        val stats = run(s, server)
        assertEquals(1, stats.filesSynced)
        assertEquals(0, server.lookups())
        assertEquals(1, server.appends())
        assertFalse("the unrelated id was never used", server.stored.contains("<wa-sync-other@local>"))
    }
}
