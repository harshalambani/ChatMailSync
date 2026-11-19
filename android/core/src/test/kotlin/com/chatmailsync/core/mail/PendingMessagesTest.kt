package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.msg
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * D185: an upload whose outcome was never learned is remembered PER MESSAGE with the Message-ID it
 * went out under. The next run asks the server about each old id, however the messages are packed
 * into emails by then, and sends only what the server does not hold.
 */
class PendingMessagesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val chat = "chat-a"
    private val m1 = msg(chat, "2025-03-20T09:00:00", "Meera Iyer", "one")
    private val m2 = msg(chat, "2025-03-20T09:05:00", "Rohan Mehta", "two")
    private val m3 = msg(chat, "2025-03-20T09:10:00", "Meera Iyer", "three")
    private val m4 = msg(chat, "2025-03-20T09:15:00", "Rohan Mehta", "four")

    private class Fake : MailTransport {
        val stored = mutableSetOf<String>()
        var appends = 0
        val lookups = mutableListOf<String>()
        var lookupFails = false
        var first413 = false
        var beforeInsert: () -> Unit = { }
        override fun labelsList() = emptyList<ImapTransport.Label>()
        override fun labelsCreate(name: String) = name
        override fun lookupStored(messageId: String, folder: String): Boolean {
            lookups.add(messageId)
            if (lookupFails) throw MailTransportError("lookup failed", 503)
            return messageId in stored
        }
        override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
            beforeInsert()
            if (first413) { first413 = false; throw MailTransportError("too big", 413) }
            appends++
            val id = MessageHeaders.parse(normalizeCrlf(rawMessageBytes))["Message-ID"].orEmpty()
            stored.add(id)
            return ImapTransport.InsertResult("1", threadId ?: id)
        }
    }

    private fun repo(clock: () -> String = { SyncTestSupport.FROZEN_NOW }): Pair<StateRepository, String> {
        val path = File(tmp.newFolder(), "state.db").absolutePath
        val r = StateRepository(clock = clock) { SqliteJdbcStateDb(path) }
        r.initDb()
        return r to path
    }

    private fun h(vararg m: ParsedMessage) = m.map { messageHashOf(it) }

    private fun ids(path: String): List<String> =
        SyncRunSupport.rows(path, "SELECT message_id FROM pending_messages", listOf("message_id")).map { it[0].toString() }

    private fun rowCount(path: String) = ids(path).size

    /** Returns the messages reported delivered (recorded), in order. */
    private fun push(t: Fake, r: StateRepository, chunks: List<List<ParsedMessage>>, chatId: String = chat): List<ParsedMessage> {
        val delivered = mutableListOf<ParsedMessage>()
        pushChunks(
            transport = t, displayName = "Meera Iyer", chunks = chunks, labelId = "Folder",
            sleeper = { }, pendingUploads = r, pendingChatId = chatId,
            onChunk = { _, _, _, _, ms -> delivered += ms },
        )
        return delivered
    }

    @Test
    fun foundAfterAResplitIntoSmallerEmailsIsNeverSentAgain() {
        val (r, path) = repo(); val t = Fake()
        r.record(chat, "<old1@x>", h(m1, m2, m3, m4)); t.stored.add("<old1@x>")
        val delivered = push(t, r, listOf(listOf(m1, m2), listOf(m3, m4)))
        assertEquals(0, t.appends)
        assertEquals("asked once for the one old id", listOf("<old1@x>"), t.lookups)
        assertEquals(setOf(m1, m2, m3, m4), delivered.toSet())
        assertEquals(0, rowCount(path))
    }

    @Test
    fun foundAfterAResplitIntoOneLargerEmailIsNeverSentAgain() {
        val (r, path) = repo(); val t = Fake()
        r.record(chat, "<oldA@x>", h(m1, m2)); r.record(chat, "<oldB@x>", h(m3, m4))
        t.stored.addAll(listOf("<oldA@x>", "<oldB@x>"))
        val delivered = push(t, r, listOf(listOf(m1, m2, m3, m4)))
        assertEquals(0, t.appends)
        assertEquals(listOf("<oldA@x>", "<oldB@x>"), t.lookups)
        assertEquals(4, delivered.size)
        assertEquals(0, rowCount(path))
    }

    @Test
    fun partialOverlapSendsOnlyTheMessagesTheServerLacks() {
        val (r, path) = repo(); val t = Fake()
        r.record(chat, "<old1@x>", h(m1, m2, m3)); t.stored.add("<old1@x>")
        val delivered = push(t, r, listOf(listOf(m3, m4)))
        assertEquals("m3 is stored, only m4 goes out", 1, t.appends)
        assertEquals(listOf(m3, m4), delivered)
        assertEquals(1, t.lookups.size)
        // m1 and m2 are not part of this run, so their rows stay until the 30 day prune; m3 is gone.
        assertEquals(2, rowCount(path))
        assertEquals(setOf("<old1@x>"), ids(path).toSet())
    }

    @Test
    fun notFoundIsSentExactlyOnceAndLeavesNothing() {
        val (r, path) = repo(); val t = Fake()
        r.record(chat, "<old1@x>", h(m1, m2))
        val delivered = push(t, r, listOf(listOf(m1, m2)))
        assertEquals(1, t.lookups.size)
        assertEquals(1, t.appends)
        assertFalse("the old id was never stored", "<old1@x>" in t.stored)
        assertEquals(listOf(m1, m2), delivered)
        assertEquals(0, rowCount(path))
    }

    @Test
    fun notFoundWithPartialOverlapSendsTheWholeNewEmailOnce() {
        val (r, path) = repo(); val t = Fake()
        r.record(chat, "<old1@x>", h(m1, m2))
        push(t, r, listOf(listOf(m2, m3)))
        assertEquals(1, t.lookups.size)
        assertEquals(1, t.appends)
        assertEquals(0, rowCount(path))
    }

    @Test
    fun aNormalRunDoesNoLookupsAndLeavesNothing() {
        val (r, path) = repo(); val t = Fake()
        val delivered = push(t, r, listOf(listOf(m1, m2), listOf(m3, m4)))
        assertEquals(0, t.lookups.size)
        assertEquals(2, t.appends)
        assertEquals(4, delivered.size)
        assertEquals(0, rowCount(path))
    }

    @Test
    fun anotherChatsRowsAreIgnoredAndKept() {
        val (r, path) = repo(); val t = Fake()
        r.record("other-chat", "<other@x>", h(m1, m2))
        push(t, r, listOf(listOf(m1, m2)))
        assertEquals(0, t.lookups.size)
        assertEquals(1, t.appends)
        assertEquals("the other chat row is untouched", 2, rowCount(path))
        assertEquals(setOf("<other@x>"), ids(path).toSet())
    }

    @Test
    fun aFailedLookupSendsNothingAndKeepsTheRows() {
        val (r, path) = repo(); val t = Fake(); t.lookupFails = true
        r.record(chat, "<old1@x>", h(m1, m2))
        try {
            push(t, r, listOf(listOf(m1, m2)))
            org.junit.Assert.fail("expected the lookup error")
        } catch (_: MailTransportError) { }
        assertEquals(0, t.appends)
        assertEquals(2, rowCount(path))
    }

    @Test
    fun aSizeRefusalClearsItsRowsAndTheResplitIsSentOnce() {
        val (r, path) = repo(); val t = Fake(); t.first413 = true
        val delivered = push(t, r, listOf(listOf(m1, m2, m3, m4)))
        assertEquals("every message delivered exactly once", 4, delivered.size)
        assertEquals("the refused email stored nothing, so no extra copy", t.stored.size, t.appends)
        assertEquals(true, t.appends >= 2)
        assertEquals(0, t.lookups.size)
        assertEquals(0, rowCount(path))
    }

    @Test
    fun rowsAreWrittenBeforeTheUploadStarts() {
        val (r, path) = repo(); val t = Fake()
        var seen = -1
        t.beforeInsert = { seen = rowCount(path) }
        push(t, r, listOf(listOf(m1, m2)))
        assertEquals("one row per message, already there at APPEND time", 2, seen)
        assertEquals(0, rowCount(path))
    }

    @Test
    fun rowsOlderThanThirtyDaysArePrunedOnTheNextRecord() {
        val (old, path) = repo { "2025-01-01T09:00:00" }
        old.record(chat, "<ancient@x>", h(m1))
        assertEquals(1, rowCount(path))
        val fresh = StateRepository(clock = { "2025-03-14T09:40:00" }) { SqliteJdbcStateDb(path) }
        fresh.record(chat, "<new@x>", h(m2))
        assertEquals(listOf("<new@x>"), ids(path))
    }

    @Test
    fun theTableKeepsHashesNotText() {
        val (r, path) = repo()
        r.record(chat, "<a@x>", h(m1))
        val v = SyncRunSupport.rows(path, "SELECT message_hash FROM pending_messages", listOf("message_hash")).single()[0].toString()
        assertEquals(64, v.length)
        assertFalse(v.contains("one"))
    }
}
