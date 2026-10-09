package com.chatmailsync.core.mail

import java.io.IOException
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The push path: split, "Part k/N" relabelling, backoff, size refusal, dry
 * run, callbacks, thread/anchor handling, and the password never reaching an
 * error. Everything runs over [FakeMailTransport] (contract-tested against
 * the real [ImapTransport]) and, for the wire-level claims, over the real one.
 */
class PushChunksTest {

    private val name = "Meera Iyer"

    private class RecordingSleeper : Sleeper {
        val delays = mutableListOf<Double>()
        override fun sleep(seconds: Double) {
            delays.add(seconds)
        }
    }

    private fun msgs(count: Int, bodyChars: Int = 40, day: Int = 5): List<ParsedMessage> =
        (0 until count).map {
            ParsedMessage(
                chatId = "chat-1",
                timestamp = LocalDateTime.of(2026, 1, day, 10, 0).plusMinutes(it.toLong()),
                sender = if (it % 2 == 0) "Meera Iyer" else "Rohan Mehta",
                body = "m$it " + "x".repeat(bodyChars),
            )
        }

    private fun subjectOf(bytes: ByteArray): String =
        MessageHeaders.parse(normalizeCrlf(bytes))["Subject"]!!

    private fun err(status: Int, text: String = "APPEND failed (NO): $status") = transportError(text, status)

    /** A limit that holds roughly [perEmail] messages of [bodyChars] characters. */
    private fun limitFor(perEmail: Int, bodyChars: Int): Long {
        val one = HtmlRenderer.renderChunk(msgs(perEmail, bodyChars), name, null, "").wireBytes +
            HtmlRenderer.encodedPartBytes(estimateIndexBytes(perEmail).toLong())
        return (one / MESSAGE_SIZE_SAFETY_FACTOR).toLong() + 2_000
    }

    // ---- split + Part k/N

    @Test
    fun anOversizedChunkIsSplitAndEveryPieceFitsTheBudget() {
        val limit = limitFor(perEmail = 2, bodyChars = 3000)
        val all = msgs(8, 3000)
        val pieces = sizeSplitCached(all, name, null, limit)
        assertTrue("must have split: ${pieces.size}", pieces.size > 1)
        assertEquals("nothing may be lost", all, pieces.flatMap { it.messages })
        for (p in pieces) {
            val projected = p.rendered.wireBytes + HtmlRenderer.encodedPartBytes(estimateIndexBytes(p.messages.size).toLong())
            assertTrue(
                "a piece of ${p.messages.size} projects to $projected over ${effectiveBudget(limit)}",
                projected <= effectiveBudget(limit) || p.messages.size == 1,
            )
        }
    }

    @Test
    fun aChunkThatFitsIsNotSplitAndGetsNoPartLabel() {
        val t = FakeMailTransport()
        val r = pushChat(t, name, msgs(3), sleeper = RecordingSleeper())
        assertEquals(1, r.results.size)
        assertFalse(subjectOf(t.inserted[0].bytes).contains("Part"))
    }

    @Test
    fun splitPiecesAreRelabelledPartKOfN() {
        val limit = limitFor(perEmail = 2, bodyChars = 3000)
        val t = FakeMailTransport(maxMessageBytes = limit)
        val prepared = prepareEmails(listOf(msgs(8, 3000)), name, null, limit)
        val r = pushChat(t, name, msgs(8, 3000), sleeper = RecordingSleeper())
        val n = r.results.size
        assertTrue(n > 1)
        for (k in 0 until n) {
            assertTrue("Part ${k + 1}/$n", prepared[k].rendered.htmlBody.contains("Part ${k + 1}/$n"))
        }
        // Parity with Python, reported as a finding: push_chunks never passes the
        // suffix to the builder, so "Part k/N" lives in the body pill only and
        // every part shares one Subject.
        assertEquals(1, t.inserted.map { subjectOf(it.bytes) }.toSet().size)
    }

    @Test
    fun aSingleMessageThatStillFitsIsNeverSplit() {
        val pieces = sizeSplitCached(msgs(1, 5000), name, null, limitFor(1, 5000))
        assertEquals(1, pieces.size)
        assertEquals(emptyList<ParsedMessage>(), sizeSplitCached(emptyList(), name, null))
    }

    // ---- backoff

    private fun scripted(vararg outcomes: Exception?): MailTransport = object : MailTransport {
        var calls = 0
        val queue = ArrayDeque(outcomes.toList())
        override fun labelsList() = emptyList<ImapTransport.Label>()
        override fun labelsCreate(name: String) = name
        override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
            calls++
            val o = if (queue.isEmpty()) null else queue.removeFirst()
            if (o != null) throw o
            return ImapTransport.InsertResult("id", "thread")
        }
    }

    @Test
    fun aTransientFailureIsRetriedWithDoublingDelays() {
        val s = RecordingSleeper()
        val t = FakeMailTransport()
        t.insertOutcomes.addAll(listOf(err(503), err(429), null))
        val r = insertWithBackoff(t, "Message-ID: <a@b>\n\nx\n".toByteArray(), "WhatsApp/x", null, s)
        assertNotNull(r)
        assertEquals(3, t.insertAttempts)
        assertEquals(listOf(1.0, 2.0, API_CALL_DELAY_SECONDS), s.delays)
    }

    @Test
    fun aFourHundredOtherThanTooManyRequestsIsNotRetried() {
        for (status in listOf(400, 401, 403, 404, 413)) {
            val s = RecordingSleeper()
            val t = FakeMailTransport()
            t.insertOutcomes.addAll(List(10) { err(status) })
            try {
                insertWithBackoff(t, ByteArray(0), "f", null, s)
                fail("must throw for $status")
            } catch (e: MailTransportError) {
                assertEquals(status, e.status)
            }
            assertEquals("no retry for $status", 1, t.insertAttempts)
            assertTrue("no wait for $status", s.delays.isEmpty())
        }
    }

    @Test
    fun retriesStopAtTheCapAndNeverRunForever() {
        val s = RecordingSleeper()
        val t = FakeMailTransport()
        t.insertOutcomes.addAll(List(50) { err(503) })
        try {
            insertWithBackoff(t, ByteArray(0), "f", null, s)
            fail("must give up")
        } catch (e: MailTransportError) {
            assertEquals(503, e.status)
        }
        assertEquals(BACKOFF_MAX_ATTEMPTS, t.insertAttempts)
        assertEquals(listOf(1.0, 2.0, 4.0, 8.0), s.delays)
    }

    @Test
    fun aNetworkErrorIsRetriedThenGivenUp() {
        val s = RecordingSleeper()
        val flaky = scripted(IOException("reset"), IOException("reset"), null)
        insertWithBackoff(flaky, ByteArray(0), "f", null, s)
        assertEquals(listOf(1.0, 2.0, API_CALL_DELAY_SECONDS), s.delays)

        val dead = scripted(*Array<Exception?>(20) { IOException("down") })
        val s2 = RecordingSleeper()
        try {
            insertWithBackoff(dead, ByteArray(0), "f", null, s2)
            fail("must give up")
        } catch (e: IOException) {
            assertEquals("down", e.message)
        }
        assertEquals(4, s2.delays.size)
    }

    @Test
    fun anUnrelatedExceptionIsNotRetried() {
        val s = RecordingSleeper()
        val t = scripted(IllegalStateException("bug"))
        try {
            insertWithBackoff(t, ByteArray(0), "f", null, s)
            fail("must throw")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertTrue(s.delays.isEmpty())
    }

    // ---- size refusal splits instead of retrying

    @Test
    fun aTooBigRefusalSplitsTheEmailInsteadOfRetryingIt() {
        val s = RecordingSleeper()
        val t = FakeMailTransport()
        t.insertOutcomes.add(err(413, "APPEND failed (NO): [TOOBIG] Message too large"))
        val seen = mutableListOf<List<ParsedMessage>>()
        val r = pushChat(t, name, msgs(6, 200), sleeper = s, onChunk = { _, _, _, _, sub -> seen.add(sub) })
        assertTrue("split into smaller emails: ${r.results.size}", r.results.size >= 2)
        assertEquals("one refused attempt, then each smaller email once", 1 + r.results.size, t.insertAttempts)
        assertEquals("no backoff wait: a size refusal is not a transient error", List(r.results.size) { API_CALL_DELAY_SECONDS }, s.delays)
        assertEquals("every message still archived, in order", msgs(6, 200), seen.flatten())
    }

    @Test
    fun aLoneMessageRefusedForItsTextIsAFailureNotALoop() {
        val t = FakeMailTransport()
        t.insertOutcomes.addAll(List(20) { err(413, "APPEND failed (NO): [TOOBIG] too big") })
        try {
            pushChat(t, name, msgs(1, 100), sleeper = RecordingSleeper())
            fail("must fail")
        } catch (e: MailTransportError) {
            assertTrue(isTooLarge(e))
        }
        assertEquals("one attempt, no rebuild-forever loop", 1, t.insertAttempts)
        assertTrue(t.inserted.isEmpty())
    }

    @Test
    fun aNonSizeFailureIsThrownAndNothingIsHalved() {
        val t = FakeMailTransport()
        t.insertOutcomes.add(err(401, "APPEND failed (NO): [AUTHENTICATIONFAILED] no"))
        try {
            pushChat(t, name, msgs(6, 100), sleeper = RecordingSleeper())
            fail("must fail")
        } catch (e: MailTransportError) {
            assertEquals(401, e.status)
        }
        assertEquals(1, t.insertAttempts)
    }

    // ---- dry run

    @Test
    fun aDryRunNeverTouchesTheTransport() {
        val t = FakeMailTransport()
        val r = pushChat(t, name, msgs(5), dryRun = true, sleeper = RecordingSleeper())
        assertEquals(0, t.insertAttempts)
        assertEquals(0, t.listCalls)
        assertTrue(t.createCalls.isEmpty())
        assertTrue(t.inserted.isEmpty())
        assertEquals("dry-run-label", r.labelId)
        assertEquals("dry-run-0", r.results[0].gmailMessageId)
        assertEquals("dry-run-thread", r.threadId)
    }

    @Test(timeout = 20_000)
    fun aDryRunWritesNoBytesToTheWire() {
        ScriptedImapServer { c -> c.serve { conn, tag, _ -> conn.send("$tag OK done") } }.use { server ->
            val t = ImapTransport(
                host = "127.0.0.1", port = server.port, email = "meera.iyer@example.com", password = "pw-FAKE",
                connectionFactory = {
                    JvmImapConnection.connectVia(server.plainOpener(), "127.0.0.1", server.port, "meera.iyer@example.com", "pw-FAKE", 5)
                },
            )
            val r = pushChat(t, name, msgs(5), dryRun = true, sleeper = RecordingSleeper())
            assertTrue(r.results.isNotEmpty())
            assertEquals("not one byte on the wire", "", server.received)
        }
    }

    @Test
    fun aDryRunKeepsAnExistingAnchorAndThread() {
        val t = FakeMailTransport()
        val r = pushChat(t, name, msgs(2), dryRun = true, anchorMessageId = "<a@local>", threadId = "T1", labelId = "L")
        assertEquals("T1", r.results[0].threadId)
        assertEquals("L", r.labelId)
        assertEquals("T1", r.threadId)
    }

    // ---- callbacks, thread, anchor

    @Test
    fun onChunkRunsAfterTheInsertWithTheMessagesThatLanded() {
        val t = FakeMailTransport()
        val calls = mutableListOf<String>()
        val all = msgs(2, 10, day = 5) + msgs(3, 10, day = 6)
        pushChat(
            t, name, all, sleeper = RecordingSleeper(),
            onChunk = { idx, total, done, totalMsgs, sub ->
                calls.add("$idx/$total $done/$totalMsgs ${sub.size} landed=${t.inserted.size}")
            },
        )
        assertEquals(listOf("1/2 2/5 2 landed=1", "2/2 5/5 3 landed=2"), calls)
    }

    @Test
    fun aFailedInsertNeverReachesOnChunk() {
        val t = FakeMailTransport()
        t.insertOutcomes.add(err(401))
        var called = false
        try {
            pushChat(t, name, msgs(2), sleeper = RecordingSleeper(), onChunk = { _, _, _, _, _ -> called = true })
            fail("must fail")
        } catch (e: MailTransportError) {
            // expected
        }
        assertFalse(called)
    }

    @Test
    fun theFirstEmailBecomesTheAnchorAndLaterOnesReplyToIt() {
        val t = FakeMailTransport()
        val all = msgs(1, 10, day = 5) + msgs(1, 10, day = 6)
        val r = pushChat(t, name, all, sleeper = RecordingSleeper())
        val first = MessageHeaders.parse(normalizeCrlf(t.inserted[0].bytes))
        val second = MessageHeaders.parse(normalizeCrlf(t.inserted[1].bytes))
        assertNull(first["In-Reply-To"])
        assertEquals(r.results[0].messageId, second["In-Reply-To"])
        assertEquals(r.results[0].messageId, second["References"])
        assertNull("the first insert starts a thread", t.inserted[0].threadId)
        assertEquals("later inserts join the thread the first returned", r.results[0].threadId, t.inserted[1].threadId)
        assertEquals(r.results.last().threadId, r.threadId)
    }

    @Test
    fun aKnownAnchorAndThreadAreUsedFromTheStart() {
        val t = FakeMailTransport()
        pushChat(t, name, msgs(1), anchorMessageId = "<anchor@local>", threadId = "T9", labelId = "WhatsApp/Meera Iyer", sleeper = RecordingSleeper())
        val h = MessageHeaders.parse(normalizeCrlf(t.inserted[0].bytes))
        assertEquals("<anchor@local>", h["In-Reply-To"])
        assertEquals("T9", t.inserted[0].threadId)
    }

    @Test
    fun noMessagesPushesNothingAndKeepsTheStoredIds() {
        val t = FakeMailTransport()
        val r = pushChat(t, name, emptyList(), labelId = "L", threadId = "T")
        assertTrue(r.results.isEmpty())
        assertEquals("L", r.labelId)
        assertEquals("T", r.threadId)
        assertEquals(0, t.listCalls)
        val none = pushChat(t, name, emptyList())
        assertEquals("", none.labelId)
        assertEquals("", none.threadId)
    }

    // ---- label handling inside pushChat

    @Test
    fun aStoredLabelFromAnotherBackendIsReplacedNotPassedThrough() {
        val t = FakeMailTransport()
        val r = pushChat(t, name, msgs(1), labelId = "Label_5512345", sleeper = RecordingSleeper())
        assertEquals("WhatsApp/Meera Iyer", r.labelId)
        assertEquals("WhatsApp/Meera Iyer", t.inserted[0].folder)
        assertTrue(t.createCalls.contains("WhatsApp/Meera Iyer"))
    }

    @Test
    fun anOwnedStoredLabelIsUsedWithoutAnyListing() {
        val t = FakeMailTransport()
        pushChat(t, name, msgs(1), labelId = "WhatsApp/Meera Iyer", sleeper = RecordingSleeper())
        assertEquals(0, t.listCalls)
        assertTrue(t.createCalls.isEmpty())
    }

    // ---- self sender

    @Test
    fun selfSenderDecidesWhichSideAMessageIsDrawnOn() {
        val t1 = FakeMailTransport()
        pushChat(t1, name, msgs(2), selfSender = "Rohan Mehta", sleeper = RecordingSleeper())
        val t2 = FakeMailTransport()
        pushChat(t2, name, msgs(2), selfSender = "Meera Iyer", sleeper = RecordingSleeper())
        assertEquals(1, t1.inserted.size)
        assertEquals(1, t2.inserted.size)
        val a = renderedHtml(msgs(2), "Rohan Mehta")
        val b = renderedHtml(msgs(2), "Meera Iyer")
        assertTrue(a != b)
        assertTrue(a == renderedHtml(msgs(2), "Rohan Mehta"))
    }

    private fun renderedHtml(m: List<ParsedMessage>, self: String?) =
        HtmlRenderer.renderChunk(m, name, null, "", selfSender = self).htmlBody

    // ---- no secret in an error from a push

    @Test(timeout = 20_000)
    fun aRefusalEchoingThePasswordLeaksNothingThroughAPush() {
        val password = "hunter2-FAKE-pw"
        ScriptedImapServer { c ->
            c.serve { conn, tag, cmd ->
                when {
                    cmd.startsWith("LIST ") -> conn.send("$tag OK LIST completed")
                    cmd.startsWith("CREATE ") -> conn.send("$tag OK Completed")
                    cmd.startsWith("SUBSCRIBE ") -> conn.send("$tag OK Completed")
                    cmd.startsWith("APPEND ") -> {
                        val size = Regex("\\{(\\d+)}$").find(cmd)!!.groupValues[1].toInt()
                        conn.send("+ Ready")
                        conn.readExactly(size)
                        conn.readLine()
                        conn.send("$tag NO [PERMISSIONDENIED] rejected $password")
                    }
                    else -> conn.send("$tag OK done")
                }
            }
        }.use { server ->
            val t = ImapTransport(
                host = "127.0.0.1", port = server.port, email = "meera.iyer@example.com", password = password,
                connectionFactory = {
                    JvmImapConnection.connectVia(server.plainOpener(), "127.0.0.1", server.port, "meera.iyer@example.com", password, 5)
                },
            )
            try {
                pushChat(t, name, msgs(1), sleeper = RecordingSleeper())
                fail("must fail")
            } catch (e: MailTransportError) {
                assertFalse(e.message, (e.message ?: "").contains(password))
                assertTrue(e.message, (e.message ?: "").contains("PERMISSIONDENIED"))
            }
        }
    }

    // ---- end to end over the real transport

    @Test(timeout = 30_000)
    fun aPushOverTheRealTransportPutsOneMessagePerEmailOnTheWire() {
        val appended = mutableListOf<String>()
        ScriptedImapServer { c ->
            c.serve { conn, tag, cmd ->
                when {
                    cmd.startsWith("LIST ") -> conn.send("$tag OK LIST completed")
                    cmd.startsWith("CREATE ") || cmd.startsWith("SUBSCRIBE ") -> conn.send("$tag OK Completed")
                    cmd.startsWith("APPEND ") -> {
                        val size = Regex("\\{(\\d+)}$").find(cmd)!!.groupValues[1].toInt()
                        conn.send("+ Ready")
                        appended.add(String(conn.readExactly(size), Charsets.ISO_8859_1))
                        conn.readLine()
                        conn.send("$tag OK Append completed")
                    }
                    else -> conn.send("$tag OK done")
                }
            }
        }.use { server ->
            val t = ImapTransport(
                host = "127.0.0.1", port = server.port, email = "meera.iyer@example.com", password = "pw-FAKE",
                connectionFactory = {
                    JvmImapConnection.connectVia(server.plainOpener(), "127.0.0.1", server.port, "meera.iyer@example.com", "pw-FAKE", 5)
                },
            )
            val all = msgs(1, 10, day = 5) + msgs(1, 10, day = 6)
            val r = pushChat(t, name, all, sleeper = RecordingSleeper())
            t.close()
            assertEquals(2, r.results.size)
            assertEquals(2, appended.size)
            assertTrue(appended[0].contains("Subject: "))
            assertEquals("every sent message carries exactly one Subject", 1, Regex("(?m)^Subject: ").findAll(appended[0].substringBefore("\r\n\r\n")).count())
        }
    }
}
