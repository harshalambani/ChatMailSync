package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.OffsetDateTime
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * F-02: an APPEND whose outcome is unknown (abort / I/O error / timeout) is never re-sent blind.
 * The transport asks the server (EXAMINE + UID SEARCH HEADER Message-ID) first.
 */
class AppendIdempotencyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A tiny in-memory IMAP server: one shared mailbox, scripted faults, and a log of every command. */
    private class Server {
        val stored = mutableListOf<String>() // Message-IDs, in arrival order
        val log = mutableListOf<String>()
        /** One entry per APPEND: ok | store-abort | abort | store-io | store-timeout | no. Empty = ok. */
        val appendScript = ArrayDeque<String>()
        /** One entry per lookup (EXAMINE): ok | io | abort | no. Empty = ok. */
        val lookupScript = ArrayDeque<String>()
        var connections = 0

        fun appends() = log.count { it == "APPEND" }
        fun lookups() = log.count { it.startsWith("SEARCH") }

        fun connect(): ImapConnection {
            connections++
            return object : ImapConnection {
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
                    return when (val step = appendScript.removeFirstOrNull() ?: "ok") {
                        "ok" -> { stored.add(id); ImapResult("OK", listOf("Completed")) }
                        "no" -> ImapResult("NO", listOf("[OVERQUOTA] over quota"))
                        "abort" -> throw ImapAbortError("connection dropped")
                        "store-abort" -> { stored.add(id); throw ImapAbortError("connection dropped after store") }
                        "store-io" -> { stored.add(id); throw IOException("connection reset") }
                        "store-timeout" -> { stored.add(id); throw SocketTimeoutException("read timed out") }
                        else -> error("bad script step $step")
                    }
                }
                override fun examine(mailboxWireArg: String): ImapResult {
                    log.add("EXAMINE")
                    return when (val step = lookupScript.removeFirstOrNull() ?: "ok") {
                        "ok" -> ImapResult("OK", listOf("3 EXISTS"))
                        "io" -> throw IOException("reset during lookup")
                        "abort" -> throw ImapAbortError("dropped during lookup")
                        "no" -> ImapResult("NO", listOf("busy"))
                        else -> error("bad lookup step $step")
                    }
                }
                override fun uidSearchMessageId(messageId: String): ImapResult {
                    log.add("SEARCH $messageId")
                    val uids = stored.withIndex().filter { it.value == messageId }.map { (it.index + 1).toString() }
                    return ImapResult("OK", if (uids.isEmpty()) emptyList() else listOf(uids.joinToString(" ")))
                }
                override fun logout() {}
            }
        }
    }

    private fun transport(server: Server) = ImapTransport(
        host = "imap.example.com", port = 993, email = "me@example.com", password = "s3cret",
        connectionFactory = { server.connect() },
    )

    private fun raw(id: String = "<wa-sync-aaa1@local>"): ByteArray {
        val chunk = listOf(
            ParsedMessage(
                chatId = "c", timestamp = java.time.LocalDateTime.of(2019, 5, 3, 10, 15, 0),
                sender = "Meera Iyer", body = "hello",
            ),
        )
        val (b64, _) = MimeBuilder.buildMimeMessage(
            displayName = "Meera Iyer", chunk = chunk, chunkSize = ChunkSize.Day,
            labelId = "WhatsApp/Meera Iyer", messageId = id,
        )
        return Base64.getUrlDecoder().decode(b64)
    }

    private val folder = "WhatsApp/Meera Iyer"

    // (1) reply lost after the server stored the message
    @Test
    fun replyLostAfterStoreLeavesExactlyOneCopyAndReportsSuccess() {
        val s = Server().also { it.appendScript.add("store-abort") }
        val result = transport(s).messagesInsert(raw(), folder, null)
        assertEquals(listOf("<wa-sync-aaa1@local>"), s.stored)
        assertEquals("found, so never re-sent", 1, s.appends())
        assertEquals("1", result.id) // the UID the search returned
        assertEquals("<wa-sync-aaa1@local>", result.threadId)
    }

    @Test
    fun ioErrorAndTimeoutAreTreatedLikeAnAbort() {
        for (kind in listOf("store-io", "store-timeout")) {
            val s = Server().also { it.appendScript.add(kind) }
            transport(s).messagesInsert(raw(), folder, null)
            assertEquals(kind, 1, s.stored.size)
            assertEquals(kind, 1, s.appends())
        }
    }

    // (2) abort before the server stored it
    @Test
    fun abortBeforeStoreIsReSentOnceAfterAnEmptyLookup() {
        val s = Server().also { it.appendScript.add("abort") }
        transport(s).messagesInsert(raw(), folder, null)
        assertEquals(1, s.stored.size)
        assertEquals(2, s.appends())
        assertEquals(1, s.lookups())
        assertEquals(
            "the lookup comes between the two sends",
            listOf("APPEND", "EXAMINE", "SEARCH <wa-sync-aaa1@local>", "APPEND"), s.log,
        )
    }

    // (3) the lookup itself fails
    @Test
    fun aFailedLookupIsRetryableAndTheNextAttemptLooksUpBeforeSending() {
        val s = Server().also {
            it.appendScript.add("store-abort")
            it.lookupScript.add("io")
        }
        val t = transport(s)
        val bytes = raw()
        try {
            t.messagesInsert(bytes, folder, null)
            fail("expected a retryable error")
        } catch (exc: MailTransportError) {
            assertEquals(503, exc.status)
        }
        assertEquals("no blind re-send after a failed lookup", 1, s.appends())
        // The next attempt (insertWithBackoff would make it) looks up first and finds the copy.
        val result = t.messagesInsert(bytes, folder, null)
        assertEquals(1, s.appends())
        assertEquals(1, s.stored.size)
        assertEquals("1", result.id)
    }

    @Test
    fun aFailedLookupThatFindsNothingStillResendsExactlyOnceLater() {
        val s = Server().also {
            it.appendScript.add("abort")
            // Two aborts: the read-only lookup itself reconnects once, so it takes two to fail it.
            it.lookupScript.addAll(listOf("abort", "abort"))
        }
        val t = transport(s)
        val bytes = raw()
        try {
            t.messagesInsert(bytes, folder, null)
            fail("expected 503")
        } catch (exc: MailTransportError) {
            assertEquals(503, exc.status)
        }
        assertEquals(0, s.stored.size)
        t.messagesInsert(bytes, folder, null)
        assertEquals(1, s.stored.size)
        assertEquals(
            "one search before the re-send",
            listOf("APPEND", "EXAMINE", "EXAMINE", "EXAMINE", "SEARCH <wa-sync-aaa1@local>", "APPEND"), s.log,
        )
    }

    @Test
    fun anExamineRefusalIsCannotTellNotNotFound() {
        val s = Server().also {
            it.appendScript.add("store-abort")
            it.lookupScript.add("no")
        }
        try {
            transport(s).messagesInsert(raw(), folder, null)
            fail("expected 503")
        } catch (exc: MailTransportError) {
            assertEquals(503, exc.status)
        }
        assertEquals("never assumed delivered, never re-sent blind", 1, s.appends())
        assertEquals(0, s.lookups())
    }

    // (4) repeated aborts through the real retry loop
    @Test
    fun repeatedAbortsUpToTheAttemptLimitNeverStoreMoreThanOneCopy() {
        val s = Server().also { srv ->
            srv.appendScript.add("store-abort")
            repeat(BACKOFF_MAX_ATTEMPTS * 2) { srv.appendScript.add("abort") }
            repeat(BACKOFF_MAX_ATTEMPTS * 2) { srv.lookupScript.add("io") }
        }
        try {
            insertWithBackoff(transport(s), raw(), folder, null, sleeper = { })
            fail("expected the retry loop to give up")
        } catch (exc: MailTransportError) {
            assertEquals(503, exc.status)
        }
        assertEquals(1, s.stored.size)
        assertEquals("only the first send ever reached the server", 1, s.appends())
        assertEquals(BACKOFF_MAX_ATTEMPTS, s.log.count { it == "EXAMINE" })
    }

    @Test
    fun repeatedAbortsWithAWorkingLookupEndWithExactlyOneCopyOnceTheServerRecovers() {
        val s = Server().also { srv -> repeat(BACKOFF_MAX_ATTEMPTS * 2) { srv.appendScript.add("abort") } }
        try {
            insertWithBackoff(transport(s), raw(), folder, null, sleeper = { })
            fail("expected the retry loop to give up")
        } catch (exc: MailTransportError) {
            assertEquals(503, exc.status)
        }
        assertEquals(0, s.stored.size)
        // Now the server recovers: still exactly one copy in the end.
        s.appendScript.clear()
        transport(s).messagesInsert(raw(), folder, null)
        assertEquals(1, s.stored.size)
    }

    // (6) a definite refusal
    @Test
    fun aTaggedNoIsNotRetriedAndNotLookedUp() {
        val s = Server().also { it.appendScript.add("no") }
        try {
            insertWithBackoff(transport(s), raw(), folder, null, sleeper = { })
            fail("expected a refusal")
        } catch (exc: MailTransportError) {
            assertEquals(403, exc.status)
        }
        assertEquals(listOf("APPEND"), s.log)
        assertTrue(s.stored.isEmpty())
    }

    @Test
    fun aCleanAppendNeverTriggersALookup() {
        val s = Server()
        transport(s).messagesInsert(raw(), folder, null)
        assertEquals(listOf("APPEND"), s.log)
    }

    // (7) unsafe Message-IDs
    @Test
    fun aMessageIdWithAQuoteOrControlCharacterIsRefusedAndNothingIsSent() {
        for (bad in listOf("<a\"b@local>", "<a\\b@local>", "<a\u0001b@local>", "<a\u007Fb@local>", "<a b@local>", "<é@local>")) {
            val s = Server()
            val bytes = ("Message-ID: $bad\r\nSubject: x\r\n\r\nbody").toByteArray(Charsets.UTF_8)
            try {
                transport(s).messagesInsert(bytes, folder, null)
                fail("expected a refusal for an id of ${bad.length} chars")
            } catch (exc: MailTransportError) {
                assertEquals(400, exc.status)
            }
            assertTrue("nothing reaches the wire", s.log.isEmpty())
            assertEquals(0, s.connections)
        }
    }

    @Test
    fun theSafetyCheckAcceptsGeneratedIdsAndRejectsInjection() {
        assertTrue(isSafeMessageId(MimeBuilder.newMessageId()))
        assertTrue(isSafeMessageId("<wa-sync-0123abcd@local>"))
        assertFalse(isSafeMessageId(""))
        assertFalse(isSafeMessageId("<a\r\nb@x>"))
        assertFalse(isSafeMessageId("<a\" ALL@x>"))
    }

    @Test
    fun aMessageWithoutAMessageIdIsNeverReSentAfterAnUncertainFailure() {
        val s = Server().also { it.appendScript.add("store-abort") }
        val bytes = "Subject: x\r\n\r\nbody".toByteArray()
        try {
            transport(s).messagesInsert(bytes, folder, null)
            fail("expected an error")
        } catch (exc: MailTransportError) {
            assertEquals(400, exc.status)
        }
        assertEquals(1, s.appends())
    }

    @Test
    fun nonAppendCommandsKeepTheirReconnectOnce() {
        val s = Server()
        val first = s.connect()
        var calls = 0
        val t = ImapTransport(
            host = "h", port = 993, email = "me@example.com", password = "x",
            connectionFactory = {
                calls++
                if (calls == 1) {
                    object : ImapConnection by first {
                        override fun list(reference: String, pattern: String): ImapResult = throw ImapAbortError("drop")
                    }
                } else {
                    s.connect()
                }
            },
        )
        assertEquals(listOf("INBOX"), t.labelsList().map { it.name })
        assertEquals(2, calls)
    }

    // ---- sync level: (1) and (5) ----

    private val fileName = "WhatsApp Chat with Meera Iyer.txt"

    private fun chatText(vararg days: Int): String =
        days.joinToString("\n") { "%02d/03/25, 09:00 - Meera Iyer: message $it".format(it) } + "\n"

    private fun sync(root: File, server: Server): SyncStats =
        SyncManager(
            repo = newRepo(root, "state.db"), transport = transport(server), inboxDir = File(root, "inbox"),
            processedDir = File(root, "processed"), sleeper = { },
        ).run()

    private fun hashes(root: File) =
        SyncRunSupport.rows(File(root, "state.db").absolutePath, "SELECT hash FROM message_hashes", listOf("hash")).map { it[0] }

    private fun statuses(root: File) =
        SyncRunSupport.rows(File(root, "state.db").absolutePath, "SELECT status FROM sync_runs ORDER BY run_id", listOf("status")).map { it[0] }

    private fun setupRoot(text: String): File {
        val root = tmp.newFolder()
        File(root, "inbox").also { it.mkdirs() }.resolve(fileName).writeText(text)
        return root
    }

    @Test
    fun syncWithALostReplyDeliversEachEmailOnceAndRecordsEachHashOnce() {
        val root = setupRoot(chatText(20, 21, 22, 23, 24))
        val s = Server().also { it.appendScript.addAll(listOf("ok", "store-abort")) }
        val stats = sync(root, s)
        assertEquals(5, s.stored.size)
        assertEquals("no duplicate Message-ID in the mailbox", 5, s.stored.toSet().size)
        assertEquals(5, stats.messagesSynced)
        assertEquals(0, stats.filesFailed)
        assertEquals(5, hashes(root).size)
        assertEquals(5, hashes(root).toSet().size)
        assertEquals(listOf("complete"), statuses(root))
    }

    @Test
    fun syncStoppedByAHardFailureMidChatResumesWithoutDuplicates() {
        val root = setupRoot(chatText(20, 21, 22, 23, 24))
        val s = Server().also { it.appendScript.addAll(listOf("ok", "ok", "ok", "no")) }
        val first = sync(root, s)
        assertEquals("emails 1..3 delivered", 3, s.stored.size)
        assertEquals("hashes for 1..3 only", 3, hashes(root).size)
        assertEquals(1, first.filesFailed)
        assertEquals(listOf("failed"), statuses(root))
        assertTrue("the file stays for the next run", File(root, "inbox/$fileName").exists())

        val second = sync(root, s)
        assertEquals("the next run sends only 4 and 5", 5, s.stored.size)
        assertEquals("no duplicate of 1..3", 5, s.stored.toSet().size)
        assertEquals(2, second.messagesSynced)
        assertEquals(5, hashes(root).toSet().size)
        assertFalse(File(root, "inbox/$fileName").exists())
    }
}
