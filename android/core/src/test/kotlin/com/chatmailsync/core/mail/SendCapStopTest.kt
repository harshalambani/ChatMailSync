package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BUG-26: one failing email used to be uploaded up to ten times and Stop could not cut an upload.
 * Now an email's bytes go on the wire at most twice per run (counted across the transport loop and
 * the backoff loop), Stop is checked before every send and before every wait, and Stop closes the
 * live connection so a blocked upload ends at once as "stopped", not as a failure.
 */
class SendCapStopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val folder = "WhatsApp/Meera Iyer"

    /** One scripted server. [script] has one entry per APPEND: ok | abort | unavailable | toobig | block. */
    private class Server(val script: ArrayDeque<String> = ArrayDeque()) {
        val log = mutableListOf<String>()
        val stored = mutableListOf<String>()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        var aborts = 0
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
                return when (val step = script.removeFirstOrNull() ?: "ok") {
                    "ok" -> { stored.add(id); ImapResult("OK", listOf("Completed")) }
                    "abort" -> throw ImapAbortError("connection dropped")
                    "unavailable" -> ImapResult("NO", listOf("[UNAVAILABLE] try later"))
                    "toobig" -> ImapResult("NO", listOf("[TOOBIG] message too large"))
                    "block" -> {
                        entered.countDown()
                        released.await(20, TimeUnit.SECONDS)
                        throw IOException("socket closed")
                    }
                    else -> error("bad script step $step")
                }
            }
            override fun examine(mailboxWireArg: String): ImapResult {
                log.add("EXAMINE")
                return ImapResult("OK", listOf("3 EXISTS"))
            }
            override fun uidSearchMessageId(messageId: String): ImapResult {
                log.add("SEARCH $messageId")
                val uids = stored.withIndex().filter { it.value == messageId }.map { (it.index + 1).toString() }
                return ImapResult("OK", if (uids.isEmpty()) emptyList() else listOf(uids.joinToString(" ")))
            }
            override fun abort() {
                aborts++
                released.countDown()
            }
            override fun logout() {}
        }
    }

    private fun transport(server: Server) = ImapTransport(
        host = "imap.example.com", port = 993, email = "me@example.com", password = "s3cret",
        connectionFactory = { server.connect() },
    )

    private fun raw(id: String = "<wa-sync-bbb1@local>"): ByteArray {
        val chunk = listOf(
            ParsedMessage(
                chatId = "c", timestamp = java.time.LocalDateTime.of(2019, 5, 3, 10, 15, 0),
                sender = "Meera Iyer", body = "hello",
            ),
        )
        val (b64, _) = MimeBuilder.buildMimeMessage(
            displayName = "Meera Iyer", chunk = chunk, chunkSize = ChunkSize.Day,
            labelId = folder, messageId = id,
        )
        return Base64.getUrlDecoder().decode(b64)
    }

    /** Records every wait; one that is asked to stop early records nothing. */
    private class FakeClockSleeper : Sleeper {
        val waited = mutableListOf<Double>()
        override fun sleep(seconds: Double) {
            waited.add(seconds)
        }
        override fun sleep(seconds: Double, shouldStop: () -> Boolean) {
            if (!shouldStop()) waited.add(seconds)
        }
    }

    // ---- (c) at most two uploads of one email's bytes per run

    @Test
    fun aFailingEmailIsSentExactlyTwiceNeverMore() {
        val s = Server(ArrayDeque(List(20) { "unavailable" }))
        val sleeper = FakeClockSleeper()
        try {
            insertWithBackoff(transport(s), raw(), folder, null, sleeper)
            fail("must fail")
        } catch (exc: MailTransportError) {
            assertEquals("a final error, not a retryable one", 400, exc.status)
            assertTrue(exc.message.orEmpty().contains("kept failing"))
            assertTrue(exc.message.orEmpty().contains("Nothing more was sent for this chat."))
        }
        assertEquals("exactly two uploads", 2, s.appends())
        // The old behaviour (up to ten uploads) is absent.
        assertFalse("never three or more", s.appends() >= 3)
        assertEquals(0, s.stored.size)
    }

    @Test
    fun anUnknownOutcomeFailureIsAlsoCappedAtTwo() {
        val s = Server(ArrayDeque(List(20) { "abort" }))
        try {
            insertWithBackoff(transport(s), raw(), folder, null, FakeClockSleeper())
            fail("must fail")
        } catch (exc: MailTransportError) {
            assertEquals(400, exc.status)
        }
        assertEquals(2, s.appends())
        assertTrue("the lookup between the two sends is not an upload", s.lookups() >= 1)
    }

    @Test
    fun aSecondAttemptThatWorksStillSucceeds() {
        val s = Server(ArrayDeque(listOf("unavailable", "ok")))
        val r = insertWithBackoff(transport(s), raw(), folder, null, FakeClockSleeper())
        assertNotNull(r)
        assertEquals(2, s.appends())
        assertEquals(1, s.stored.size)
    }

    @Test
    fun theCapIsPerEmailNotPerTransport() {
        val s = Server(ArrayDeque(List(2) { "unavailable" }))
        val t = transport(s)
        try {
            insertWithBackoff(t, raw("<wa-sync-bbb1@local>"), folder, null, FakeClockSleeper())
            fail("first email must hit the cap")
        } catch (_: MailTransportError) {
        }
        // A different email on the same transport still gets its own two uploads.
        insertWithBackoff(t, raw("<wa-sync-bbb2@local>"), folder, null, FakeClockSleeper())
        assertEquals(listOf("<wa-sync-bbb2@local>"), s.stored)
    }

    // ---- (d) a size refusal still goes back to the caller to split

    @Test
    fun aSizeRefusalStillReachesTheCallerAsTooLargeAfterOneUpload() {
        val s = Server(ArrayDeque(listOf("toobig")))
        try {
            insertWithBackoff(transport(s), raw(), folder, null, FakeClockSleeper())
            fail("must be refused")
        } catch (exc: MailTransportError) {
            assertTrue("caller splits on this", isTooLarge(exc))
            assertEquals(413, exc.status)
        }
        assertEquals(1, s.appends())
    }

    // ---- (a) Stop before every send and every wait

    @Test
    fun stopBetweenAttemptsStartsNoNewSendAndNoWaitRunsOut() {
        val s = Server(ArrayDeque(List(20) { "unavailable" }))
        var stop = false
        val sleeper = FakeClockSleeper()
        val t = transport(s)
        val counting = object : MailTransport by t {
            override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
                try {
                    return t.messagesInsert(rawMessageBytes, folder, threadId)
                } finally {
                    stop = true // Stop arrives while attempt 1 is failing
                }
            }
        }
        try {
            insertWithBackoff(counting, raw(), folder, null, sleeper, { stop })
            fail("must stop")
        } catch (_: StopRequestedException) {
        }
        assertEquals("no second send", 1, s.appends())
        assertTrue("no backoff wait ran out: ${sleeper.waited}", sleeper.waited.isEmpty())
    }

    @Test
    fun stopBeforeTheFirstSendSendsNothing() {
        val s = Server()
        try {
            insertWithBackoff(transport(s), raw(), folder, null, FakeClockSleeper(), { true })
            fail("must stop")
        } catch (_: StopRequestedException) {
        }
        assertEquals(0, s.appends())
        assertEquals(0, s.log.size)
    }

    @Test
    fun theRealSleeperEndsEarlyOnStopAndStillWaitsWithoutIt() {
        val t0 = System.nanoTime()
        SystemSleeper.sleep(30.0) { true }
        assertTrue("returned at once", (System.nanoTime() - t0) < 2_000_000_000L)

        var calls = 0
        val t1 = System.nanoTime()
        SystemSleeper.sleep(0.6) { ++calls > 100 } // never asks to stop
        assertTrue("waited about 0.6 s", (System.nanoTime() - t1) >= 500_000_000L)
        assertTrue(calls >= 2)
    }

    // ---- (b) Stop cuts an upload in progress

    @Test
    fun stopDuringAnAppendCutsItOffAndNothingMoreIsSent() {
        val s = Server(ArrayDeque(listOf("block")))
        val t = transport(s)
        val sleeper = FakeClockSleeper()
        var stop = false
        val outcome = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                insertWithBackoff(t, raw(), folder, null, sleeper, { stop })
            } catch (e: Throwable) {
                outcome.set(e)
            }
        }
        worker.start()
        assertTrue("the upload is under way", s.entered.await(10, TimeUnit.SECONDS))
        stop = true
        t.abort() // what CoreApi.requestStop does from the UI thread
        worker.join(10_000)
        assertFalse("the worker ended", worker.isAlive)
        assertTrue("ended as stopped, not as a failure: ${outcome.get()}", outcome.get() is StopRequestedException)
        assertEquals(1, s.aborts)
        assertEquals("no further send", 1, s.appends())
        assertEquals("no lookup either, it was a stop", 0, s.lookups())
        assertTrue("no backoff wait ran out: ${sleeper.waited}", sleeper.waited.isEmpty())
        // A transport that has been aborted never sends again.
        try {
            t.messagesInsert(raw(), folder, null)
            fail("must stay stopped")
        } catch (_: StopRequestedException) {
        }
        assertEquals(1, s.appends())
    }

    @Test
    fun anAbortWithNothingInFlightIsHarmless() {
        val s = Server()
        val t = transport(s)
        t.abort()
        assertEquals(0, s.aborts)
        assertEquals(0, s.appends())
    }

    // ---- run level: the cap on one chat, and a stopped run

    private fun chatText(who: String, vararg days: Int): String =
        days.joinToString("\n") { "%02d/03/25, 09:00 - %s: message %d".format(it, who, it) } + "\n"

    private class Inbox(val root: File, val inbox: File, val processed: File)

    private fun inboxWith(vararg chats: Pair<String, String>): Inbox {
        val root = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        for ((who, text) in chats) File(inbox, "WhatsApp Chat with $who.txt").writeText(text)
        return Inbox(root, inbox, File(root, "processed"))
    }

    private class RunTransport(val onInsert: (String) -> Unit) : MailTransport {
        val folderInserts = mutableMapOf<String, Int>()
        override fun labelsList(): List<ImapTransport.Label> = emptyList()
        override fun labelsCreate(name: String): String = name
        override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
            folderInserts.merge(folder, 1, Int::plus)
            onInsert(folder)
            return ImapTransport.InsertResult(id = "m", threadId = threadId?.takeIf { it.isNotEmpty() } ?: "t1")
        }
    }

    private fun capError() = MailTransportError(
        "Uploading this email kept failing, so it was not sent again. Nothing more was sent for this chat.", 400,
    )

    private fun manager(i: Inbox, t: MailTransport, stop: () -> Boolean = { false }) = SyncManager(
        repo = newRepo(i.root, "state.db"), transport = t, inboxDir = i.inbox,
        processedDir = i.processed, sleeper = { }, shouldStop = stop,
    )

    @Test
    fun theCapOnOneChatDoesNotStopTheNextChat() {
        val i = inboxWith(
            "Meera Iyer" to chatText("Meera Iyer", 20, 21),
            "Rohan Mehta" to chatText("Rohan Mehta", 20, 21),
        )
        val t = RunTransport { if (it.contains("Meera")) throw capError() }
        val stats = manager(i, t).run()
        assertEquals("the capped chat is one failure", 1, stats.filesFailed)
        assertEquals("the other chat was delivered", 1, stats.filesSynced)
        assertTrue(stats.errors.single().contains("kept failing"))
        assertTrue(stats.errors.single().contains("Nothing more was sent for this chat."))
        assertTrue("the next chat really was sent", (t.folderInserts.entries.firstOrNull { it.key.contains("Rohan") }?.value ?: 0) >= 1)
        assertTrue("the capped chat keeps its file for a later run", File(i.inbox, "WhatsApp Chat with Meera Iyer.txt").exists())
        assertFalse(File(i.inbox, "WhatsApp Chat with Rohan Mehta.txt").exists())
    }

    @Test
    fun stopStillStopsEverythingAfterACap() {
        val i = inboxWith(
            "Meera Iyer" to chatText("Meera Iyer", 20),
            "Rohan Mehta" to chatText("Rohan Mehta", 20),
        )
        var stop = false
        val t = RunTransport { stop = true; throw capError() }
        val stats = manager(i, t, stop = { stop }).run()
        assertEquals("only the first chat was tried", 1, t.folderInserts.values.sum())
        assertEquals(0, stats.filesSynced)
    }

    @Test
    fun aStoppedRunIsNotReportedAsAFailure() {
        val i = inboxWith("Meera Iyer" to chatText("Meera Iyer", 20, 21, 22))
        var stop = false
        val t = RunTransport { stop = true; throw StopRequestedException() }
        val stats = manager(i, t, stop = { stop }).run()
        assertEquals("not a failure", 0, stats.filesFailed)
        assertTrue("no error banner: ${stats.errors}", stats.errors.isEmpty())
        assertEquals(1, t.folderInserts.values.sum())
        assertTrue("the file stays for the next run", File(i.inbox, "WhatsApp Chat with Meera Iyer.txt").exists())
        assertEquals(0, stats.filesSynced)
    }
}
