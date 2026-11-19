package com.chatmailsync.core.mail

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** UX-11: the MB line and the bar inside one email. Kotlin only: src/progress.py has no such line. */
class Ux11UploadProgressTest {

    private fun upload(
        sent: Long,
        total: Long = 16_200_000L,
        email: Int = 3,
        emails: Int = 3,
        before: Int = 20,
        share: Int = 10,
        all: Int = 30,
    ): Map<String, Any?> = mapOf(
        "type" to "upload", "name" to "Meera Iyer", "email" to email, "total_emails" to emails,
        "email_msgs" to share, "sent" to sent, "total" to total, "global_before" to before, "global_total" to all,
    )

    private fun chunk(done: Int, all: Int = 30): Map<String, Any?> = mapOf(
        "type" to "chunk", "name" to "Meera Iyer", "chunk" to 1, "total_chunks" to 3,
        "msgs_done" to done, "total_msgs" to all, "global_done" to done, "global_total" to all,
    )

    private fun tracker(): ProgressTracker {
        val t = ProgressTracker()
        t.feed(mapOf("type" to "syncing", "name" to "Meera Iyer"))
        t.feed(chunk(20))
        return t
    }

    @Test
    fun theSketchTextIsShownAsASecondLine() {
        val t = tracker()
        t.feed(upload(sent = 7_500_000L))
        assertEquals("Syncing: Meera Iyer — 20 / 30 messages\nSending email 3 of 3: 7 / 17 MB", t.state.line)
        assertEquals("Sending email 3 of 3: 7 / 17 MB", t.state.upload)
    }

    // NEGATIVE: the bar never goes backwards and never passes the value reached once the email is confirmed.
    @Test
    fun theBarMovesInsideTheEmailButNeverBackwardsOrPastConfirmed() {
        val t = tracker()
        val confirmed = 30.0 / 30.0
        val atStart = t.state.fraction
        var last = atStart
        for (sent in listOf(0L, 1_000_000L, 8_000_000L, 3_000_000L, 16_200_000L, 99_000_000L)) {
            t.feed(upload(sent))
            assertTrue("went back: ${t.state.fraction} < $last", t.state.fraction >= last)
            assertTrue("passed confirmed: ${t.state.fraction}", t.state.fraction <= confirmed + 1e-9)
            last = t.state.fraction
        }
        assertTrue("it moved inside the email", last > atStart)
        val u = tracker()
        u.feed(upload(sent = 8_100_000L))
        assertTrue(u.state.fraction < confirmed)
        assertEquals((20 + 10 * 0.5) / 30.0, u.state.fraction, 1e-9)
    }

    // NEGATIVE: the MB line is gone once the email has landed (and when an upload ends any other way).
    @Test
    fun theMbLineClearsWhenTheEmailLandsOrTheUploadEnds() {
        val landed = tracker()
        landed.feed(upload(sent = 5_000_000L))
        assertTrue(landed.state.line.contains("Sending email"))
        landed.feed(chunk(30))
        assertFalse(landed.state.line, landed.state.line.contains("Sending email"))
        assertFalse(landed.state.line.contains("\n"))

        val ended = tracker()
        ended.feed(upload(sent = 5_000_000L))
        ended.feed(upload(sent = 0L, total = 0L))
        assertEquals("", ended.state.upload)

        val done = tracker()
        done.feed(upload(sent = 5_000_000L))
        done.feed(mapOf("type" to "done", "stopped" to true))
        assertEquals("", done.state.upload)
    }

    @Test
    fun noUploadEventMeansTheLineIsExactlyTheOldOne() {
        assertEquals("Syncing: Meera Iyer — 20 / 30 messages", tracker().state.line)
        assertFalse(tracker().state.asDict().containsKey("upload"))
    }

    // NEGATIVE: updates are capped at 4 a second.
    @Test
    fun throttlingCapsTheUpdateRate() {
        var now = 0L
        val throttle = UploadThrottle(250_000_000L) { now }
        var allowed = 0
        for (i in 0 until 100) { // a call every 10 ms for one second
            now = i * 10_000_000L
            if (throttle.allow()) allowed++
        }
        assertEquals(4, allowed)
        now += 1_000_000_000L
        assertTrue("after a pause it lets one through", throttle.allow())
    }

    // NEGATIVE: a cut upload counts only what was written, never the unsent part.
    @Test(timeout = 30_000)
    fun aCutUploadDoesNotCountUnsentBytes() {
        val total = 8_000_000
        val message = ByteArray(total) { 'a'.code.toByte() }
        ScriptedImapServer { c ->
            c.send("* OK [CAPABILITY IMAP4rev1] hi")
            val login = c.readLine()!!
            c.send("${login.substringBefore(' ')} OK LOGIN completed")
            c.readLine() // the APPEND command
            c.send("+ go")
            c.readExactly(20_000)
            c.close() // cut: the rest is never read
        }.use { server ->
            val conn = JvmImapConnection.connectVia(
                server.plainOpener(), "mail.example.test", server.port,
                JvmImapConnectionWireTest.EMAIL, JvmImapConnectionWireTest.FAKE_PASSWORD, 5,
            )
            val seen = ArrayList<Long>()
            conn.setUploadListener { seen.add(it) }
            try {
                conn.append("\"Box\"", null, null, message)
                fail("a cut upload must fail")
            } catch (_: Exception) {
                // expected
            }
            assertTrue("some bytes were written", seen.isNotEmpty())
            assertTrue("counts only grow: $seen", seen.zipWithNext().all { (a, b) -> b > a })
            assertTrue("never the whole literal: ${seen.last()}", seen.last() < total)
        }
    }

    private class Uploading(private val failOn: Int?) : MailTransport {
        var listener: ((Long, Long) -> Unit)? = null
        var calls = 0
        override fun labelsList() = emptyList<ImapTransport.Label>()
        override fun labelsCreate(name: String) = name
        override fun setUploadListener(listener: ((sent: Long, total: Long) -> Unit)?) {
            this.listener = listener
        }

        override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
            calls++
            val total = rawMessageBytes.size.toLong()
            listener?.invoke(total / 2, total)
            if (calls == failOn) throw transportError("APPEND failed (NO): refused", 400)
            listener?.invoke(total, total)
            return ImapTransport.InsertResult("id$calls", "thread")
        }
    }

    private object NoSleep : Sleeper {
        override fun sleep(seconds: Double) {}
    }

    private fun msgs(count: Int, day: Int) = (0 until count).map {
        ParsedMessage("chat-1", LocalDateTime.of(2026, 1, day, 10, 0).plusMinutes(it.toLong()), "Meera Iyer", "m$it hello")
    }

    @Test
    fun pushChunksReportsEachEmailAndAlwaysEndsTheLine() {
        val events = ArrayList<UploadProgress>()
        val t = Uploading(failOn = null)
        pushChunks(t, "Meera Iyer", listOf(msgs(3, 5), msgs(2, 6)), "WhatsApp/x", sleeper = NoSleep, onUpload = { events.add(it) })
        assertEquals(2, events.count { it.total == 0L })
        val live = events.filter { it.total > 0L }
        assertTrue(live.all { it.sent in 0..it.total })
        assertEquals(listOf(1, 1, 2, 2), live.map { it.emailNumber })
        assertEquals(listOf(3, 3, 2, 2), live.map { it.emailMsgs })
        assertEquals(listOf(0, 0, 3, 3), live.map { it.msgsBefore })
        assertNull("the transport is left with no listener", t.listener)
    }

    // NEGATIVE: a failed email still ends the line, and its unsent half is never reported.
    @Test
    fun aFailedEmailEndsTheLineWithoutCountingTheUnsentPart() {
        val events = ArrayList<UploadProgress>()
        val t = Uploading(failOn = 1)
        try {
            pushChunks(t, "Meera Iyer", listOf(msgs(3, 5)), "WhatsApp/x", sleeper = NoSleep, onUpload = { events.add(it) })
            fail("the refusal must surface")
        } catch (_: MailTransportError) {
            // expected
        }
        val live = events.filter { it.total > 0L }
        assertEquals("only the written half was reported", 1, live.size)
        assertTrue(live[0].sent < live[0].total)
        assertEquals(1, events.count { it.total == 0L })
        assertEquals(0L, events.last().total)
    }
}
