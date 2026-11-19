package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * D177 + BUG-26 together, the device check in miniature: Stop cuts an upload whose outcome nobody
 * knows; the NEXT run (a new transport, as after the app is reopened) asks the server before it
 * sends that email again.
 */
class StopThenResumeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Server(val storeBeforeBlocking: Boolean) {
        val stored = mutableListOf<String>()
        val log = mutableListOf<String>()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        @Volatile var blockNext = true
        fun appends() = log.count { it == "APPEND" }
        fun lookups() = log.count { it == "SEARCH" }

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
                if (blockNext) {
                    blockNext = false
                    if (storeBeforeBlocking) stored.add(id)
                    entered.countDown()
                    released.await(20, TimeUnit.SECONDS)
                    throw IOException("socket closed")
                }
                stored.add(id)
                return ImapResult("OK", listOf("Completed"))
            }
            override fun examine(mailboxWireArg: String): ImapResult {
                log.add("EXAMINE")
                return ImapResult("OK", listOf("3 EXISTS"))
            }
            override fun uidSearchMessageId(messageId: String): ImapResult {
                log.add("SEARCH")
                val uids = stored.withIndex().filter { it.value == messageId }.map { (it.index + 1).toString() }
                return ImapResult("OK", if (uids.isEmpty()) emptyList() else listOf(uids.joinToString(" ")))
            }
            override fun abort() {
                released.countDown()
            }
            override fun logout() {}
        }
    }

    private class Setup(val root: File, val inbox: File, val processed: File, val db: String)

    private fun setup(): Setup {
        val root = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        File(inbox, "WhatsApp Chat with Meera Iyer.txt")
            .writeText("20/03/25, 09:00 - Meera Iyer: message one\n20/03/25, 09:05 - Rohan Mehta: message two\n")
        return Setup(root, inbox, File(root, "processed"), File(root, "state.db").absolutePath)
    }

    private fun transport(server: Server) = ImapTransport(
        host = "imap.example.com", port = 993, email = "me@example.com", password = "s3cret",
        connectionFactory = { server.connect() },
    )

    private fun pending(s: Setup) =
        SyncRunSupport.rows(s.db, "SELECT message_id FROM pending_uploads", listOf("message_id"))

    /** Run 1: Stop pressed during the upload. Returns the stats of that run. */
    private fun stoppedRun(s: Setup, server: Server): SyncStats {
        val t = transport(server)
        var stop = false
        val result = AtomicReference<SyncStats?>(null)
        val worker = Thread {
            result.set(
                SyncManager(
                    repo = newRepo(s.root, "state.db"), transport = t, inboxDir = s.inbox,
                    processedDir = s.processed, sleeper = { }, shouldStop = { stop },
                ).run(),
            )
        }
        worker.start()
        assertTrue(server.entered.await(10, TimeUnit.SECONDS))
        stop = true
        t.abort()
        worker.join(10_000)
        assertFalse(worker.isAlive)
        return result.get()!!
    }

    private fun resumedRun(s: Setup, server: Server) = SyncManager(
        repo = newRepo(s.root, "state.db"), transport = transport(server), inboxDir = s.inbox,
        processedDir = s.processed, sleeper = { },
    ).run()

    @Test
    fun aStoppedUploadThatTheServerKeptIsFoundNotResentByTheNextRun() {
        val s = setup()
        val server = Server(storeBeforeBlocking = true)
        val first = stoppedRun(s, server)
        assertEquals("a stop is not a failure", 0, first.filesFailed)
        assertTrue(first.errors.isEmpty())
        assertEquals(1, server.appends())
        assertEquals("the unknown outcome was written down", 1, pending(s).size)

        val second = resumedRun(s, server)
        assertEquals(1, second.filesSynced)
        assertEquals(0, second.filesFailed)
        assertEquals("looked up before anything was sent", 1, server.lookups())
        assertEquals("NOT re-sent: still the one upload", 1, server.appends())
        assertEquals(1, server.stored.size)
        assertTrue(pending(s).isEmpty())
    }

    @Test
    fun aStoppedUploadThatNeverArrivedIsSentOnceByTheNextRun() {
        val s = setup()
        val server = Server(storeBeforeBlocking = false)
        val first = stoppedRun(s, server)
        assertEquals(0, first.filesFailed)
        assertEquals(0, server.stored.size)
        assertEquals(1, pending(s).size)

        val second = resumedRun(s, server)
        assertEquals(1, second.filesSynced)
        assertEquals("looked up, not found", 1, server.lookups())
        assertEquals("then sent exactly once", 2, server.appends())
        assertEquals(1, server.stored.size)
        assertTrue(pending(s).isEmpty())
    }
}
