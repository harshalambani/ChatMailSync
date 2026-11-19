package com.chatmailsync.core.mail

import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * D182: Stop is tapped on the UI thread, where Android refuses network I/O. Closing a TLS socket
 * writes to the network, so [CoreApi.requestStop] must hand the cut to another thread.
 */
class RequestStopThreadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun api(root: File) = CoreApi(root, { SqliteJdbcStateDb(it.absolutePath) }, { null })

    private fun chatText(days: List<Int>): String =
        days.joinToString("") { "%02d/03/25, 09:00 - Meera Iyer: message %d\n".format(it, it) }

    private fun withChat(name: String, days: List<Int>): CoreApi {
        val a = api(tmp.newFolder(name))
        a.paths.inboxDir.mkdirs()
        File(a.paths.inboxDir, "WhatsApp Chat with Meera Iyer.txt").writeText(chatText(days))
        return a
    }

    /** A transport whose upload blocks until abort is called, and which records where abort ran. */
    private class BlockingTransport : MailTransport {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val abortThread = AtomicReference<Thread?>(null)
        @Volatile var abortCalls = 0
        override fun labelsList(): List<ImapTransport.Label> = emptyList()
        override fun labelsCreate(name: String): String = "Label_WA"
        override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
            entered.countDown()
            released.await(20, TimeUnit.SECONDS)
            throw StopRequestedException()
        }
        override fun abort() {
            abortCalls++
            abortThread.set(Thread.currentThread())
            released.countDown()
        }
    }

    @Test
    fun requestStopNeverCutsTheConnectionOnTheCallingThread() {
        val a = withChat("r1", listOf(20, 21))
        val t = BlockingTransport()
        val result = AtomicReference<Map<String, Any?>?>(null)
        val worker = Thread { result.set(a.sync(transport = t)) }
        worker.start()
        assertTrue("the upload is under way", t.entered.await(10, TimeUnit.SECONDS))

        val caller = Thread.currentThread()
        a.requestStop()
        worker.join(10_000)

        assertFalse("the run ended", worker.isAlive)
        assertEquals(1, t.abortCalls)
        assertNotNull(t.abortThread.get())
        assertNotEquals("abort must not run on the thread that called requestStop", caller, t.abortThread.get())
        assertEquals("a stop, not a failure", true, result.get()!!["stopped"])
        assertEquals(0, result.get()!!["files_failed"])
    }

    @Test
    fun theCallerIsNotHeldUpByASlowClose() {
        val a = withChat("r2", listOf(20))
        val slowClose = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val t = object : MailTransport {
            override fun labelsList(): List<ImapTransport.Label> = emptyList()
            override fun labelsCreate(name: String): String = "Label_WA"
            override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
                entered.countDown()
                slowClose.await(20, TimeUnit.SECONDS)
                throw StopRequestedException()
            }
            override fun abort() {
                Thread.sleep(1500) // a TLS close that takes a while
                slowClose.countDown()
            }
        }
        val worker = Thread { a.sync(transport = t) }
        worker.start()
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        a.requestStop()
        assertTrue("requestStop returned at once", (System.nanoTime() - t0) < 500_000_000L)
        worker.join(10_000)
        assertFalse(worker.isAlive)
    }

    @Test
    fun aRealUploadStopsWithinAFractionOfASecondOfRequestStop() {
        val a = withChat("r3", listOf(20, 21))
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        var appends = 0
        val conn = object : ImapConnection {
            override val capabilities: List<String> = emptyList()
            override fun list(reference: String, pattern: String) =
                ImapResult("OK", listOf("(\\HasNoChildren) \"/\" \"INBOX\""))
            override fun create(mailboxWireArg: String) = ImapResult("OK", listOf("Completed"))
            override fun subscribe(mailboxWireArg: String) = ImapResult("OK", listOf("Completed"))
            override fun append(
                mailboxWireArg: String, flags: String?, internalDate: OffsetDateTime?, message: ByteArray,
            ): ImapResult {
                appends++
                entered.countDown()
                released.await(20, TimeUnit.SECONDS)
                throw IOException("socket closed")
            }
            override fun examine(mailboxWireArg: String) = ImapResult("OK", listOf("0 EXISTS"))
            override fun uidSearchMessageId(messageId: String) = ImapResult("OK", emptyList())
            override fun abort() {
                released.countDown()
            }
            override fun logout() {}
        }
        val t = ImapTransport(
            host = "imap.example.com", port = 993, email = "me@example.com", password = "s3cret",
            connectionFactory = { conn },
        )
        val result = AtomicReference<Map<String, Any?>?>(null)
        val worker = Thread { result.set(a.sync(transport = t)) }
        worker.start()
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        a.requestStop()
        worker.join(10_000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertFalse(worker.isAlive)
        assertTrue("ended within 1 s of Stop, took $ms ms", ms < 1000)
        assertEquals("no second send", 1, appends)
        assertEquals(true, result.get()!!["stopped"])
        assertEquals(0, result.get()!!["files_failed"])
    }

    @Test
    fun repeatedRequestStopsAreHarmless() {
        val a = withChat("r4", listOf(20))
        val t = BlockingTransport()
        val worker = Thread { a.sync(transport = t) }
        worker.start()
        assertTrue(t.entered.await(10, TimeUnit.SECONDS))
        a.requestStop()
        a.requestStop()
        a.requestStop()
        worker.join(10_000)
        assertFalse(worker.isAlive)
        assertTrue("never an error; at most one cut per request", t.abortCalls in 1..3)
    }

    @Test
    fun requestStopWithNoRunInProgressDoesNothingAndDoesNotThrow() {
        val a = withChat("r5", listOf(20))
        a.requestStop()
        a.requestStop()
        // And it does not poison the next run: the flag is reset when a run starts.
        val r = a.sync(transport = CountingTransport())
        assertEquals(false, r["stopped"])
        assertEquals(1, r["files_synced"])
    }

    @Test
    fun aTransportWhoseAbortThrowsDoesNotBreakStop() {
        val a = withChat("r6", listOf(20, 21))
        val entered = CountDownLatch(1)
        val go = CountDownLatch(1)
        val t = object : MailTransport {
            override fun labelsList(): List<ImapTransport.Label> = emptyList()
            override fun labelsCreate(name: String): String = "Label_WA"
            override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
                entered.countDown()
                go.await(20, TimeUnit.SECONDS)
                return ImapTransport.InsertResult("id", "t1")
            }
            override fun abort() {
                go.countDown()
                throw IllegalStateException("boom")
            }
        }
        val result = AtomicReference<Map<String, Any?>?>(null)
        val worker = Thread { result.set(a.sync(transport = t)) }
        worker.start()
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        a.requestStop() // must not throw on the caller
        worker.join(10_000)
        assertFalse(worker.isAlive)
        assertEquals(true, result.get()!!["stopped"])
    }
}
