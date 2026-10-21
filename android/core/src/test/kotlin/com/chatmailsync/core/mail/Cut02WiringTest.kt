package com.chatmailsync.core.mail

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * CUT-02: the three behaviours the app wiring depends on, proven in :core where they live.
 * (1) the app's version reaches every archived email, (2) a Stop that landed before the engine
 * started still stops the run and nothing is pushed, (3) two overlapping syncs never both push.
 */
class Cut02WiringTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val chatName = "WhatsApp Chat with Meera Iyer.txt"

    private fun chatText(vararg days: Int): String =
        days.joinToString("") { "%02d/03/25, 09:00 - Meera Iyer: message %d\n".format(it, it) }

    private fun api(root: File, version: String? = null): CoreApi =
        if (version == null) CoreApi(root, { SqliteJdbcStateDb(it.absolutePath) })
        else CoreApi(root, { SqliteJdbcStateDb(it.absolutePath) }, appVersion = version)

    private fun queue(a: CoreApi, text: String = chatText(20, 21)) {
        a.paths.inboxDir.mkdirs()
        File(a.paths.inboxDir, chatName).writeText(text)
    }

    /** Every raw message as readable text, quoted-printable / base64 parts decoded where possible. */
    private fun rawText(t: FakeMailTransport): String =
        t.inserted.joinToString("\n") { String(it.bytes, Charsets.ISO_8859_1) }

    // ---- C: appVersion ---------------------------------------------------

    @Test
    fun theAppVersionIsStampedIntoEveryArchivedEmail() {
        val a = api(tmp.newFolder(), "2.9.0")
        queue(a)
        val t = FakeMailTransport()
        a.sync(transport = t)
        assertTrue(t.inserted.isNotEmpty())
        val text = t.inserted.joinToString("\n") { decodedText(it.bytes) }
        assertTrue("the version is in the index: $text", "\"app_version\": \"2.9.0\"" in text)
    }

    // NEGATIVE: when a version is given, a built message never says "unknown".
    @Test
    fun aBuiltMessageNeverSaysUnknownWhenAVersionIsGiven() {
        val a = api(tmp.newFolder(), "2.9.0")
        queue(a)
        val t = FakeMailTransport()
        a.sync(transport = t)
        val text = t.inserted.joinToString("\n") { decodedText(it.bytes) }
        assertFalse("unknown leaked: $text", "\"app_version\": \"unknown\"" in text)
    }

    /** The index part is attached; decode every base64 / plain part so the JSON is searchable. */
    private fun decodedText(bytes: ByteArray): String {
        val raw = String(bytes, Charsets.ISO_8859_1)
        val out = StringBuilder(raw)
        // Base64 bodies: decode each long base64 run and append, so JSON inside is visible.
        Regex("(?:[A-Za-z0-9+/]{4}\\r?\\n?){8,}={0,2}").findAll(raw).forEach { m ->
            try {
                out.append('\n').append(String(java.util.Base64.getMimeDecoder().decode(m.value), Charsets.UTF_8))
            } catch (_: IllegalArgumentException) {
                // not base64
            }
        }
        return out.toString()
    }

    // ---- D: the Stop race ------------------------------------------------

    // NEGATIVE: a stop requested before sync() starts still stops the run; nothing is pushed.
    @Test
    fun aStopThatLandedBeforeSyncStartedStillStopsAndPushesNothing() {
        val a = api(tmp.newFolder())
        queue(a, chatText(20, 21, 22))
        val t = FakeMailTransport()
        // The persisted Stop mark is already set: the external signal says stop from the start.
        val result = a.sync(transport = t, externalStop = { true })
        assertEquals(true, result["stopped"])
        assertEquals("nothing may be pushed after the stop", 0, t.inserted.size)
        assertEquals(0, t.insertAttempts)
        assertTrue("the export stays for the next run", File(a.paths.inboxDir, chatName).exists())
    }

    // The in-memory request is cleared when sync starts (as in Python), which is exactly why the
    // persisted signal exists: this documents that requestStop() alone, before the start, is lost.
    @Test
    fun anInMemoryStopBeforeTheStartIsClearedByTheStartButTheExternalSignalIsNot() {
        val a = api(tmp.newFolder())
        queue(a, chatText(20, 21))
        a.requestStop()
        val t = FakeMailTransport()
        val result = a.sync(transport = t, externalStop = { true })
        assertEquals(true, result["stopped"])
        assertEquals(0, t.insertAttempts)
    }

    // ---- E: single flight ------------------------------------------------

    /** Holds the first insert open until released, counting every append. */
    private class SlowTransport(val entered: CountDownLatch, val release: CountDownLatch) : MailTransport {
        val inserts = AtomicInteger(0)
        override fun labelsList(): List<ImapTransport.Label> = emptyList()
        override fun labelsCreate(name: String): String = name
        override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
            val n = inserts.incrementAndGet()
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            return ImapTransport.InsertResult(id = "m$n", threadId = threadId?.takeIf { it.isNotEmpty() } ?: "t1")
        }
    }

    // NEGATIVE: two overlapping sync() calls never both push (no duplicate sends).
    @Test
    fun twoOverlappingSyncsNeverBothPush() {
        val a = api(tmp.newFolder())
        queue(a, chatText(20, 21, 22))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = SlowTransport(entered, release)
        val second = FakeMailTransport()

        var firstResult: Map<String, Any?>? = null
        val th1 = Thread { firstResult = a.sync(transport = first) }
        th1.start()
        assertTrue("the first sync reached its first append", entered.await(10, TimeUnit.SECONDS))

        var secondResult: Map<String, Any?>? = null
        val th2 = Thread { secondResult = a.sync(transport = second) }
        th2.start()
        // While the first is mid-append the second must not have pushed anything.
        Thread.sleep(400)
        assertEquals("the second sync pushed while the first was running", 0, second.insertAttempts)

        release.countDown()
        th1.join(20_000)
        th2.join(20_000)
        assertFalse(th1.isAlive)
        assertFalse(th2.isAlive)

        // The first run sent the whole file; the second found nothing left to send.
        assertEquals(3, first.inserts.get())
        assertEquals("the second sync must not re-send what the first sent", 0, second.insertAttempts)
        assertEquals(3, firstResult!!["messages_synced"])
        assertEquals(0, secondResult!!["messages_synced"])
    }

    // A waiting sync gives up (stopped, nothing sent) when its own stop arrives first.
    @Test
    fun aWaitingSyncStopsWithoutSendingWhenItsOwnStopArrives() {
        val a = api(tmp.newFolder())
        queue(a, chatText(20, 21))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = SlowTransport(entered, release)
        val th1 = Thread { a.sync(transport = first) }
        th1.start()
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        val second = FakeMailTransport()
        val stopSecond = java.util.concurrent.atomic.AtomicBoolean(false)
        var secondResult: Map<String, Any?>? = null
        val th2 = Thread { secondResult = a.sync(transport = second, externalStop = { stopSecond.get() }) }
        th2.start()
        Thread.sleep(250)
        stopSecond.set(true)
        th2.join(10_000)
        assertFalse("the waiter must give up once stopped", th2.isAlive)
        assertEquals(true, secondResult!!["stopped"])
        assertEquals(0, second.insertAttempts)

        release.countDown()
        th1.join(20_000)
    }
}
