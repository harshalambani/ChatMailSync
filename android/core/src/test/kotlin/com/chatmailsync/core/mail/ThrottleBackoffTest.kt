package com.chatmailsync.core.mail

import java.io.IOException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SEC-10: a provider throttle reply is waited out (30, 60, 120 s), cancellably, then fails loudly.
 * Every wait here runs on a fake clock: no test sleeps for real.
 */
class ThrottleBackoffTest {

    /** Virtual time: sleep() just moves the clock. */
    private class FakeClock : Sleeper {
        var now = 0.0
        val sleeps = mutableListOf<Double>()
        override fun sleep(seconds: Double) {
            now += seconds
            sleeps.add(seconds)
        }
    }

    private val raw = "Message-ID: <wa-sync-throttle@local>\n\nx\n".toByteArray()

    private fun throttle(text: String = "APPEND failed (NO): [LIMIT] throttled") =
        transportError(text, statusForImapText(text))

    private fun waitsOf(c: FakeClock) = c.sleeps.filter { it != API_CALL_DELAY_SECONDS }

    // ---- the mapping

    @Test
    fun throttleRepliesMapToTheThrottleClass() {
        listOf(
            "NO [THROTTLED] slow down",
            "NO [LIMIT] too many requests",
            "NO Rate limit exceeded for this account",
            "NO Too many simultaneous connections",
            "NO [UNAVAILABLE] Too many simultaneous connections",
        ).forEach { assertEquals(it, THROTTLE_STATUS, statusForImapText(it)) }
    }

    @Test
    fun otherRepliesAreNotThrottles() {
        assertEquals(413, statusForImapText("NO [TOOBIG] message too large"))
        assertEquals("a size limit is a size refusal", 413, statusForImapText("NO Message size limit exceeded"))
        assertEquals("a full mailbox is not a throttle", 403, statusForImapText("NO [OVERQUOTA] mailbox is over its quota limit"))
        assertEquals(401, statusForImapText("NO [AUTHENTICATIONFAILED] bad credentials"))
        assertEquals("a bare NO stays 400", 400, statusForImapText("NO something odd"))
        assertEquals(503, statusForImapText("NO [UNAVAILABLE] try later"))
        assertFalse("whole words only: accurate / separate", isThrottleReply("NO the accurate and separate answer"))
        assertFalse(isThrottleReply("NO file too large for this mailbox limit of size"))
    }

    // ---- the schedule

    @Test
    fun aThrottleIsWaitedOutAt30Then60Then120SecondsAndThenSucceeds() {
        val clock = FakeClock()
        val t = FakeMailTransport()
        t.insertOutcomes.addAll(listOf(throttle(), throttle(), throttle(), null))
        val r = insertWithBackoff(t, raw, "WhatsApp/x", null, clock)
        assertNotNull(r)
        assertEquals(4, t.insertAttempts)
        assertEquals("30 + 60 + 120 s of virtual time", 210.0, waitsOf(clock).sum(), 0.001)
    }

    @Test
    fun theWaitsAreSlicedSoStopCanLandAnywhere() {
        val clock = FakeClock()
        val t = FakeMailTransport()
        t.insertOutcomes.addAll(listOf(throttle(), null))
        insertWithBackoff(t, raw, "WhatsApp/x", null, clock)
        val slices = waitsOf(clock)
        assertTrue("sliced to at most 0.25 s", slices.all { it <= STOP_POLL_SECONDS + 1e-9 })
        assertEquals(30.0, slices.sum(), 0.001)
    }

    @Test
    fun afterTheLastRetryTheChatFailsWithAClearMessage() {
        val clock = FakeClock()
        val t = FakeMailTransport()
        t.insertOutcomes.addAll(List(10) { throttle() })
        try {
            insertWithBackoff(t, raw, "WhatsApp/x", null, clock)
            fail("should have failed loudly")
        } catch (e: MailTransportError) {
            assertEquals(THROTTLE_STATUS, e.status)
            assertTrue(e.message!!, "30, 60, 120" in e.message!!)
            assertTrue(e.message!!, "Nothing was lost" in e.message!!)
        }
        assertEquals("first try + 3 retries, no more", 4, t.insertAttempts)
        assertEquals(210.0, waitsOf(clock).sum(), 0.001)
        assertEquals("nothing was delivered", 0, t.inserted.size)
    }

    // ---- negatives

    @Test
    fun otherErrorsStillFailFastWithNoWait() {
        for (status in listOf(400, 401, 403, 413)) {
            val clock = FakeClock()
            val t = FakeMailTransport()
            t.insertOutcomes.add(transportError("APPEND failed: $status", status))
            try {
                insertWithBackoff(t, raw, "WhatsApp/x", null, clock)
                fail("$status should throw")
            } catch (e: MailTransportError) {
                assertEquals(status, e.status)
            }
            assertEquals("status $status: one attempt", 1, t.insertAttempts)
            assertTrue("status $status: no wait of any kind", clock.sleeps.isEmpty())
        }
    }

    @Test
    fun serverErrorsKeepTheirOwnShortBackoff() {
        val clock = FakeClock()
        val t = FakeMailTransport()
        t.insertOutcomes.addAll(listOf(transportError("x", 503), null))
        insertWithBackoff(t, raw, "WhatsApp/x", null, clock)
        assertEquals(listOf(1.0, API_CALL_DELAY_SECONDS), clock.sleeps)
    }

    @Test
    fun stopDuringABackoffSendsNothingMoreAndEndsWithinAQuarterSecond() {
        val clock = FakeClock()
        val t = FakeMailTransport()
        t.insertOutcomes.addAll(List(10) { throttle() })
        val stopAt = 12.0
        try {
            insertWithBackoff(t, raw, "WhatsApp/x", null, clock, shouldStop = { clock.now >= stopAt })
            fail("should have been stopped")
        } catch (_: ThrottleWaitStopped) {
        }
        assertEquals("the one refused attempt, then nothing", 1, t.insertAttempts)
        assertEquals("nothing delivered", 0, t.inserted.size)
        assertTrue("ended within one slice of Stop: ${clock.now}", clock.now - stopAt <= STOP_POLL_SECONDS + 1e-9)
    }

    @Test
    fun stopBeforeTheWaitStartsWaitsNotAtAll() {
        val clock = FakeClock()
        val t = FakeMailTransport()
        t.insertOutcomes.addAll(List(10) { throttle() })
        try {
            insertWithBackoff(t, raw, "WhatsApp/x", null, clock, shouldStop = { true })
            fail("should have been stopped")
        } catch (_: ThrottleWaitStopped) {
        }
        assertTrue(clock.sleeps.isEmpty())
        assertEquals(1, t.insertAttempts)
    }

    @Test
    fun pushChunksEndsQuietlyOnStopDuringABackoffAndKeepsWhatWasDelivered() {
        val clock = FakeClock()
        val t = FakeMailTransport()
        // email 1 goes through, email 2 is throttled for ever, Stop arrives mid-wait
        t.insertOutcomes.add(null)
        t.insertOutcomes.addAll(List(20) { throttle() })
        val msgs = listOf(
            ParsedMessage("c", LocalDateTime.of(2025, 3, 20, 9, 0), "Meera Iyer", "one"),
            ParsedMessage("c", LocalDateTime.of(2025, 3, 21, 9, 0), "Rohan Mehta", "two"),
        )
        val done = mutableListOf<Int>()
        val results = pushChunks(
            transport = t, displayName = "Meera Iyer", chunks = msgs.map { listOf(it) }, labelId = "WhatsApp/x",
            sleeper = clock, shouldStop = { clock.now >= 20.0 },
            onChunk = { _, _, _, _, m -> done.add(m.size) },
        )
        assertEquals("email 1 was delivered and recorded", 1, results.size)
        assertEquals(listOf(1), done)
        assertEquals("only email 1 is on the server", 1, t.inserted.size)
        assertEquals("email 2 was tried once, then Stop", 2, t.insertAttempts)
    }

    @Test
    fun theFailureTextNeverCarriesTheServersWordsOrThePassword() {
        val clock = FakeClock()
        val t = FakeMailTransport()
        t.insertOutcomes.addAll(List(10) { throttle("APPEND failed (NO): [LIMIT] throttled for s3cret-PW") })
        try {
            insertWithBackoff(t, raw, "WhatsApp/x", null, clock)
            fail()
        } catch (e: MailTransportError) {
            assertFalse(e.message!!, "s3cret-PW" in e.message!!)
        }
    }

    // ---- the whole stack: an APPEND of unknown outcome, then a throttle

    private class Server(val password: String) {
        val stored = mutableListOf<String>()
        val log = mutableListOf<String>()
        val appendSteps = ArrayDeque(listOf("store-io")) // the reply is lost after the server kept it
        var throttledLookups = 1
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
                stored.add(id)
                if ((appendSteps.removeFirstOrNull() ?: "ok") == "store-io") throw IOException("connection reset")
                return ImapResult("OK", listOf("Completed"))
            }
            override fun examine(mailboxWireArg: String): ImapResult {
                log.add("EXAMINE")
                if (throttledLookups > 0) {
                    throttledLookups--
                    return ImapResult("NO", listOf("[LIMIT] Too many simultaneous connections for $password"))
                }
                return ImapResult("OK", listOf("3 EXISTS"))
            }
            override fun uidSearchMessageId(messageId: String): ImapResult {
                log.add("SEARCH")
                val uids = stored.withIndex().filter { it.value == messageId }.map { (it.index + 1).toString() }
                return ImapResult("OK", if (uids.isEmpty()) emptyList() else listOf(uids.joinToString(" ")))
            }
            override fun logout() {}
        }
    }

    private fun transportFor(server: Server) = ImapTransport(
        host = "imap.example.com", port = 993, email = "me@example.com", password = server.password,
        connectionFactory = { server.connect() },
    )

    @Test
    fun aThrottleAfterAnUnknownOutcomeGoesThroughTheLookupNeverABlindResend() {
        val server = Server("s3cret-PW")
        val clock = FakeClock()
        val r = insertWithBackoff(transportFor(server), raw, "WhatsApp/x", null, clock)
        assertNotNull(r)
        assertEquals("exactly one APPEND, ever", 1, server.appends())
        assertEquals("one copy on the server", 1, server.stored.size)
        assertTrue("it waited 30 s for the throttle", waitsOf(clock).sum() >= 30.0 - 1e-9)
        assertTrue("and looked up again after the wait", server.lookups() >= 1)
    }

    @Test
    fun theExhaustedThrottleTextAfterAnUnknownOutcomeHasNoPassword() {
        val server = Server("s3cret-PW")
        server.throttledLookups = 100
        try {
            insertWithBackoff(transportFor(server), raw, "WhatsApp/x", null, FakeClock())
            fail()
        } catch (e: MailTransportError) {
            assertEquals(THROTTLE_STATUS, e.status)
            assertFalse(e.message!!, "s3cret-PW" in e.message!!)
        }
        assertEquals("never sent twice while the lookup could not answer", 1, server.appends())
    }
}
