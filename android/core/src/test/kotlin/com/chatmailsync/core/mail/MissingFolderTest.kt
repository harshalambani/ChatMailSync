package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.msg
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BUG-28: a chat whose mail folder is gone from the server used to fail every run. Now a [TRYCREATE]
 * refusal creates the folder once and sends again, a definite NO leaves no pending row, and a
 * folder that is not there reads as "not found" in the F-02 lookup. A lookup that really cannot tell
 * (dropped connection, a throttle, a refused EXAMINE on a folder that exists) still blocks a re-send.
 */
class MissingFolderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val folder = "WhatsApp/Meera Iyer"
    private val chat = "chat-a"
    private val m1 = msg(chat, "2025-03-20T09:00:00", "Meera Iyer", "one")

    /** A scripted server: the folder exists or not; APPEND answers can be scripted. */
    private class Server : ImapConnection {
        override val capabilities: List<String> = emptyList()
        var folderExists = false
        var listFails = false
        var delim = "/"
        var abortFirstExamine = false
        var examineReply: ImapResult? = null // null = derive from folderExists
        var examineThrows: Throwable? = null
        val appendReplies = ArrayDeque<ImapResult>()
        val creates = mutableListOf<String>()
        var appends = 0
        val stored = mutableSetOf<String>()

        override fun list(reference: String, pattern: String): ImapResult {
            if (listFails) return ImapResult("NO", listOf("busy"))
            val lines = mutableListOf("(\\HasNoChildren) \"$delim\" \"INBOX\"")
            if (folderExists) lines.add("(\\HasNoChildren) \"$delim\" \"WhatsApp${delim}Meera Iyer\"")
            return ImapResult("OK", lines)
        }
        override fun create(mailboxWireArg: String): ImapResult {
            creates.add(mailboxWireArg)
            folderExists = true
            return ImapResult("OK", listOf("Completed"))
        }
        override fun subscribe(mailboxWireArg: String) = ImapResult("OK", listOf("Completed"))
        override fun append(mailboxWireArg: String, flags: String?, internalDate: OffsetDateTime?, message: ByteArray): ImapResult {
            appends++
            val scripted = if (appendReplies.isEmpty()) null else appendReplies.removeFirst()
            if (scripted != null) return scripted
            if (!folderExists) return ImapResult("NO", listOf("[TRYCREATE] no such folder"))
            stored.add(MessageHeaders.parse(normalizeCrlf(message))["Message-ID"].orEmpty())
            return ImapResult("OK", listOf("Completed"))
        }
        override fun examine(mailboxWireArg: String): ImapResult {
            if (abortFirstExamine) {
                abortFirstExamine = false
                throw ImapAbortError("dropped")
            }
            examineThrows?.let { throw it }
            examineReply?.let { return it }
            return if (folderExists) ImapResult("OK", listOf("0 EXISTS")) else ImapResult("NO", listOf("Mailbox does not exist"))
        }
        override fun uidSearchMessageId(messageId: String) =
            ImapResult("OK", if (messageId in stored) listOf("7") else emptyList())
        override fun logout() {}
    }

    private fun transport(c: ImapConnection) = ImapTransport(
        host = "imap.example.com", port = 993, email = "rohan.mehta@example.com", password = "pw-FAKE-4471",
        connectionFactory = { c },
    )

    private fun raw(id: String = "<wa-sync-bug28@local>"): ByteArray {
        val chunk = listOf(
            ParsedMessage(
                chatId = "c", timestamp = java.time.LocalDateTime.of(2019, 5, 3, 10, 15, 0),
                sender = "Meera Iyer", body = "hello",
            ),
        )
        val (b64, _) = MimeBuilder.buildMimeMessage(
            displayName = "Meera Iyer", chunk = chunk, chunkSize = ChunkSize.Day, labelId = folder, messageId = id,
        )
        return java.util.Base64.getUrlDecoder().decode(b64)
    }

    private fun repo(): Pair<StateRepository, String> {
        val path = File(tmp.newFolder(), "state.db").absolutePath
        val r = StateRepository(clock = { SyncTestSupport.FROZEN_NOW }) { SqliteJdbcStateDb(path) }
        r.initDb()
        return r to path
    }

    private fun pendingIds(path: String): List<String> =
        SyncRunSupport.rows(path, "SELECT message_id FROM pending_messages", listOf("message_id")).map { it[0].toString() }

    private fun pushOne(t: MailTransport, r: StateRepository): List<ParsedMessage> {
        val delivered = mutableListOf<ParsedMessage>()
        pushChunks(
            transport = t, displayName = "Meera Iyer", chunks = listOf(listOf(m1)), labelId = folder,
            sleeper = { }, pendingUploads = r, pendingChatId = chat,
            onChunk = { _, _, _, _, ms -> delivered += ms },
        )
        return delivered
    }

    // TRYCREATE: the folder is created once and the email goes in on the second try.
    @Test
    fun tryCreateCreatesTheFolderOnceAndSendsAgain() {
        val s = Server()
        val result = transport(s).messagesInsert(raw(), folder, null)
        assertEquals(1, s.creates.size)
        assertEquals(2, s.appends)
        assertTrue(s.folderExists)
        assertTrue("<wa-sync-bug28@local>" in s.stored)
        assertTrue(result.id.isNotEmpty())
    }

    // Negative: CREATE is tried once, never in a loop, even if the server keeps saying TRYCREATE.
    @Test
    fun createIsTriedOnlyOnceEvenIfTheServerKeepsRefusing() {
        val s = Server()
        repeat(5) { s.appendReplies.add(ImapResult("NO", listOf("[TRYCREATE] no such folder"))) }
        try {
            transport(s).messagesInsert(raw(), folder, null)
            fail("expected the refusal")
        } catch (e: MailTransportError) {
            assertTrue(e.definiteRefusal)
        }
        assertEquals(1, s.creates.size)
        assertTrue("never more than the per-email upload cap", s.appends <= MAX_UPLOADS_PER_EMAIL)
    }

    // Negative: a refusal that is not about a missing folder never triggers a CREATE.
    @Test
    fun otherRefusalsDoNotCreateAnything() {
        val s = Server()
        s.folderExists = true
        s.appendReplies.add(ImapResult("NO", listOf("[PERMISSIONDENIED] read-only")))
        try {
            transport(s).messagesInsert(raw(), folder, null)
            fail("expected the refusal")
        } catch (e: MailTransportError) {
            assertTrue(e.definiteRefusal)
        }
        assertEquals(0, s.creates.size)
        assertEquals(1, s.appends)
    }

    // The lookup reads a missing folder as "not found", whatever the server's words.
    @Test
    fun lookupOfAMissingFolderIsNotFound() {
        val s = Server()
        assertFalse(transport(s).lookupStored("<old@x>", folder))
        val generic = Server()
        generic.examineReply = ImapResult("NO", listOf("EXAMINE failed"))
        assertFalse("generic wording, folder absent from LIST", transport(generic).lookupStored("<old@x>", folder))
    }

    // Negative: "cannot tell" is still "cannot tell" -- never read as "not found".
    @Test
    fun aRefusedExamineOnAFolderThatExistsIsStillCannotTell() {
        val s = Server()
        s.folderExists = true
        s.examineReply = ImapResult("NO", listOf("EXAMINE failed"))
        try {
            transport(s).lookupStored("<old@x>", folder)
            fail("expected cannot-tell")
        } catch (e: MailTransportError) {
            assertEquals(503, e.status)
        }
    }

    @Test
    fun aDroppedConnectionDuringTheLookupIsStillCannotTell() {
        val s = Server()
        s.folderExists = true
        s.examineThrows = IOException("reset")
        try {
            transport(s).lookupStored("<old@x>", folder)
            fail("expected cannot-tell")
        } catch (e: MailTransportError) {
            assertEquals(503, e.status)
        }
    }

    @Test
    fun aThrottledExamineIsNotAMissingFolder() {
        val s = Server() // folder absent, but the refusal is a throttle
        s.examineReply = ImapResult("NO", listOf("Too many simultaneous connections"))
        try {
            transport(s).lookupStored("<old@x>", folder)
            fail("expected cannot-tell")
        } catch (e: MailTransportError) {
            assertEquals(THROTTLE_STATUS, e.status)
        }
    }

    @Test
    fun aFailingListLeavesAMissingFolderUnknown() {
        val s = Server()
        s.listFails = true
        s.examineReply = ImapResult("NO", listOf("EXAMINE failed"))
        try {
            transport(s).lookupStored("<old@x>", folder)
            fail("expected cannot-tell")
        } catch (e: MailTransportError) {
            assertFalse("a lookup that cannot tell is not a definite refusal", e.definiteRefusal)
        }
    }

    // End to end, the stuck state from the Nord run: a leftover pending row and a missing folder.
    @Test
    fun aMissingFolderNoLongerFailsEveryRun() {
        val (r, path) = repo()
        val s = Server()
        r.record(chat, "<old1@x>", listOf(messageHashOf(m1)))
        val delivered = pushOne(transport(s), r)
        assertEquals(listOf(m1), delivered)
        assertEquals("the email went in once, after the folder was created", 1, s.stored.size)
        assertEquals(emptyList<String>(), pendingIds(path))
    }

    // A definite NO leaves no pending row.
    @Test
    fun aDefiniteNoLeavesNoPendingRow() {
        val (r, path) = repo()
        val s = Server()
        s.folderExists = true
        s.appendReplies.add(ImapResult("NO", listOf("[PERMISSIONDENIED] read-only")))
        try {
            pushOne(transport(s), r)
            fail("expected the refusal")
        } catch (_: MailTransportError) {
        }
        assertEquals(emptyList<String>(), pendingIds(path))
        assertEquals(0, s.stored.size)
    }

    // Negative: an unknown outcome keeps its row, and a lookup that breaks blocks any re-send.
    @Test
    fun anUnknownOutcomeKeepsItsRowAndABrokenLookupBlocksTheResend() {
        val (r, path) = repo()
        val s = Server()
        s.folderExists = true
        val dropping = object : ImapConnection by s {
            var first = true
            override fun append(mailboxWireArg: String, flags: String?, internalDate: OffsetDateTime?, message: ByteArray): ImapResult {
                if (first) {
                    first = false
                    s.stored.add(MessageHeaders.parse(normalizeCrlf(message))["Message-ID"].orEmpty())
                    throw ImapAbortError("dropped")
                }
                return s.append(mailboxWireArg, flags, internalDate, message)
            }
            override fun examine(mailboxWireArg: String): ImapResult = throw IOException("dropped mid-lookup")
        }
        try {
            pushOne(transport(dropping), r)
            fail("expected cannot-tell")
        } catch (_: MailTransportError) {
        }
        assertEquals("the row stays", 1, pendingIds(path).size)
        assertEquals("the stored copy is the only one: no second upload", 1, s.stored.size)
        assertEquals("no second APPEND reached the server", 0, s.appends)
    }

    // Negative: a dropped connection is never marked as a definite refusal.
    @Test
    fun aDroppedConnectionIsNeverMarkedAsADefiniteRefusal() {
        val s = Server()
        s.folderExists = true
        val dropping = object : ImapConnection by s {
            override fun append(mailboxWireArg: String, flags: String?, internalDate: OffsetDateTime?, message: ByteArray): ImapResult =
                throw ImapAbortError("dropped")
            override fun examine(mailboxWireArg: String): ImapResult = throw IOException("dropped")
        }
        try {
            transport(dropping).messagesInsert(raw(), folder, null)
            fail("expected an error")
        } catch (e: MailTransportError) {
            assertFalse(e.definiteRefusal)
        }
    }

    // Negative: after a reconnect the transport forgets the folder delimiter. On a server whose delimiter
    // is not "/" (here "."), an existing folder must still read as "cannot tell", never "not found" --
    // otherwise a stored email could be sent again.
    @Test
    fun aReconnectOnADotDelimiterServerDoesNotMakeAnExistingFolderLookMissing() {
        val s = Server()
        s.delim = "."
        s.folderExists = true
        val t = transport(s)
        t.labelsList() // learns the "." delimiter, as an earlier step of a real run does
        s.abortFirstExamine = true // the lookup's first EXAMINE drops: call() reconnects and clears the delimiter
        s.examineReply = ImapResult("NO", listOf("EXAMINE failed"))
        try {
            t.lookupStored("<old@x>", folder)
            fail("expected cannot-tell, not 'not found'")
        } catch (e: MailTransportError) {
            assertEquals(503, e.status)
        }
    }
}
