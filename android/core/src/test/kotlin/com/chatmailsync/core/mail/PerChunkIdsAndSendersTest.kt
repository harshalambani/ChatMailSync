package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BUG-11: the thread id, label id and first anchor Message-ID are saved as each email is
 * accepted, and each email's sender counts are saved in the same transaction as its hashes.
 * Before, both were written only at the end of a push, so a push interrupted at email k put
 * the rest of the chat in a NEW thread and left the first k emails' senders uncounted.
 */
class PerChunkIdsAndSendersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileName = "WhatsApp Chat with Meera Iyer.txt"

    /** One message per day (so one email per message); the senders alternate. */
    private fun chatText(vararg days: Int): String = days.joinToString("\n") {
        val who = if (it % 2 == 0) "Meera Iyer" else "Rohan Mehta"
        "%02d/03/25, 09:00 - %s: message %d".format(it, who, it)
    } + "\n"

    private class Setup(val root: File, val inbox: File, val processed: File, val db: String)

    private fun setup(text: String): Setup {
        val root = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        File(inbox, fileName).writeText(text)
        return Setup(root, inbox, File(root, "processed"), File(root, "state.db").absolutePath)
    }

    /** Keeps every raw email; thread ids are "t<n>" unless the caller passes one. */
    private class Capture(private val failAt: Int? = null, private val crashAt: Int? = null) : MailTransport {
        var inserts = 0
        val raws = mutableListOf<String>()
        val threadsSeen = mutableListOf<String?>()
        override fun labelsList(): List<ImapTransport.Label> = emptyList()
        override fun labelsCreate(name: String): String = "Label_WA"
        override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
            inserts++
            if (crashAt != null && inserts == crashAt) throw ProcessDeath()
            if (failAt != null && inserts == failAt) throw RuntimeException("simulated failure")
            raws.add(String(rawMessageBytes, Charsets.ISO_8859_1))
            threadsSeen.add(threadId)
            return ImapTransport.InsertResult(
                id = "m$inserts",
                threadId = threadId?.takeIf { it.isNotEmpty() } ?: "t$inserts",
            )
        }
    }

    private fun manager(s: Setup, t: MailTransport, stop: () -> Boolean = { false }, dry: Boolean = false) = SyncManager(
        repo = newRepo(s.root, "state.db"), transport = t, inboxDir = s.inbox,
        processedDir = s.processed, sleeper = { }, shouldStop = stop, dryRun = dry,
    )

    private fun counts(db: String): Map<String, String?> =
        SyncRunSupport.rows(db, "SELECT sender, msg_count FROM chat_senders ORDER BY sender", listOf("sender", "msg_count"))
            .associate { it[0]!! to it[1] }

    private fun ids(db: String) = SyncRunSupport.rows(
        db, "SELECT gmail_thread_id, gmail_label_id, anchor_message_id FROM chats",
        listOf("gmail_thread_id", "gmail_label_id", "anchor_message_id"),
    ).single()

    private fun hashCount(db: String) = SyncRunSupport.rows(db, "SELECT hash FROM message_hashes", listOf("h")).size

    private fun header(raw: String, name: String): String? =
        Regex("(?im)^$name:[ \\t]*(.+)$").find(raw)?.groupValues?.get(1)?.trim()

    /** Reference result: the same chat pushed once, uninterrupted. */
    private fun cleanRun(vararg days: Int): Triple<Capture, Map<String, String?>, List<String?>> {
        val s = setup(chatText(*days))
        val t = Capture()
        manager(s, t).run()
        return Triple(t, counts(s.db), ids(s.db))
    }

    @Test
    fun aFailedPushResumesUnderTheOriginalAnchorAndThreadAndEndsWithOneThread() {
        val s = setup(chatText(20, 21, 22, 23, 24))
        val first = Capture(failAt = 3)
        manager(s, first).run()
        assertEquals(2, first.raws.size)
        val (thread1, _, anchor1) = ids(s.db)
        assertEquals("t1", thread1)
        assertNotNull("the anchor is saved after the first accepted email", anchor1)

        val second = Capture()
        manager(s, second).run()
        assertEquals(3, second.raws.size)
        for (raw in second.raws) {
            assertEquals("in the original thread", true, raw.contains(anchor1!!))
            assertEquals(anchor1, header(raw, "In-Reply-To"))
            assertTrue(header(raw, "References")!!.contains(anchor1))
        }
        assertEquals("every resumed append targets the original thread", listOf("t1", "t1", "t1"), second.threadsSeen)
        val (thread2, _, anchor2) = ids(s.db)
        assertEquals("t1", thread2)
        assertEquals(anchor1, anchor2)
        assertEquals("one thread for the whole chat", 1, setOf(thread2).size)
    }

    @Test
    fun aResumedChatHasTheSameSenderCountsAsAnUninterruptedRun() {
        val (_, cleanCounts, _) = cleanRun(20, 21, 22, 23, 24)
        val s = setup(chatText(20, 21, 22, 23, 24))
        manager(s, Capture(failAt = 3)).run()
        manager(s, Capture()).run()
        assertEquals(cleanCounts, counts(s.db))
        assertEquals(mapOf("Meera Iyer" to "3", "Rohan Mehta" to "2"), counts(s.db))
    }

    @Test
    fun theFirstChunksSendersAreNotCountedTwiceAfterAResume() {
        val s = setup(chatText(20, 21, 22, 23, 24))
        manager(s, Capture(failAt = 3)).run()
        // After the failure only the two delivered emails (days 20, 21) are counted.
        assertEquals(mapOf("Meera Iyer" to "1", "Rohan Mehta" to "1"), counts(s.db))
        manager(s, Capture()).run()
        assertEquals(mapOf("Meera Iyer" to "3", "Rohan Mehta" to "2"), counts(s.db))
        assertEquals("every message hashed once", 5, hashCount(s.db))
    }

    @Test
    fun aCrashedRunRecoversInTheSameThreadWithTheSameCounts() {
        val (_, cleanCounts, _) = cleanRun(20, 21, 22, 23, 24)
        val s = setup(chatText(20, 21, 22, 23, 24))
        try {
            manager(s, Capture(crashAt = 3)).run()
            fail("the simulated process death should escape run()")
        } catch (_: ProcessDeath) {
            // expected
        }
        val (_, _, anchor1) = ids(s.db)
        val recovery = Capture()
        manager(s, recovery).run()
        assertEquals(3, recovery.raws.size)
        for (raw in recovery.raws) assertEquals(anchor1, header(raw, "In-Reply-To"))
        assertEquals(listOf("t1", "t1", "t1"), recovery.threadsSeen)
        assertEquals("one thread", "t1", ids(s.db)[0])
        assertEquals(cleanCounts, counts(s.db))
    }

    @Test
    fun aSecondInterruptionDuringRecoveryStillKeepsOneThreadAndExactCounts() {
        val (_, cleanCounts, _) = cleanRun(20, 21, 22, 23, 24)
        val s = setup(chatText(20, 21, 22, 23, 24))
        try {
            manager(s, Capture(crashAt = 2)).run()
        } catch (_: ProcessDeath) {
        }
        try {
            manager(s, Capture(crashAt = 2)).run()
        } catch (_: ProcessDeath) {
        }
        val last = Capture()
        manager(s, last).run()
        assertEquals("t1", ids(s.db)[0])
        assertTrue(last.threadsSeen.all { it == "t1" })
        assertEquals(cleanCounts, counts(s.db))
    }

    @Test
    fun aCleanRunGivesTheSameIdsAndCountsAsBefore() {
        val s = setup(chatText(20, 21, 22, 23, 24))
        val t = Capture()
        manager(s, t).run()
        val (thread, label, anchor) = ids(s.db)
        assertEquals("t1", thread)
        assertEquals("Label_WA", label)
        // The anchor is the first email's Message-ID; every later email replies to it.
        val firstMid = header(t.raws[0], "Message-ID")
        assertEquals(firstMid, anchor)
        assertEquals(null, header(t.raws[0], "In-Reply-To"))
        for (raw in t.raws.drop(1)) assertEquals(anchor, header(raw, "In-Reply-To"))
        assertEquals(listOf<String?>(null, "t1", "t1", "t1", "t1"), t.threadsSeen)
        assertEquals(mapOf("Meera Iyer" to "3", "Rohan Mehta" to "2"), counts(s.db))
        assertEquals(5, hashCount(s.db))
        // The pushed counts are added once, not once per email AND again at the end.
        assertFalse(counts(s.db).values.any { it == "6" || it == "4" })
    }

    @Test
    fun aStopAfterChunkTwoThenALaterRunKeepsOneThreadAndEqualCounts() {
        val (_, cleanCounts, _) = cleanRun(20, 21, 22, 23, 24)
        val s = setup(chatText(20, 21, 22, 23, 24))
        var stop = false
        val first = object : MailTransport {
            val inner = Capture()
            override fun labelsList() = inner.labelsList()
            override fun labelsCreate(name: String) = inner.labelsCreate(name)
            override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
                val r = inner.messagesInsert(rawMessageBytes, folder, threadId)
                if (inner.inserts == 2) stop = true
                return r
            }
        }
        manager(s, first, stop = { stop }).run()
        assertEquals(2, first.inner.inserts)
        assertEquals("a stopped run keeps its delivered chunks counted", mapOf("Meera Iyer" to "1", "Rohan Mehta" to "1"), counts(s.db))
        val anchor1 = ids(s.db)[2]
        assertNotNull(anchor1)

        val second = Capture()
        manager(s, second).run()
        assertEquals(listOf("t1", "t1", "t1"), second.threadsSeen)
        for (raw in second.raws) assertEquals(anchor1, header(raw, "In-Reply-To"))
        assertEquals("t1", ids(s.db)[0])
        assertEquals(cleanCounts, counts(s.db))
    }

    @Test
    fun aDryRunWritesNoIdsAndNoCounts() {
        val s = setup(chatText(20, 21, 22))
        val t = Capture()
        manager(s, t, dry = true).run()
        assertEquals(0, t.inserts)
        assertEquals(0, hashCount(s.db))
        assertEquals(emptyMap<String, String?>(), counts(s.db))
        assertEquals(0, SyncRunSupport.rows(s.db, "SELECT chat_id FROM chats", listOf("c")).size)
    }

    private fun repoWithChat(): Pair<StateRepository, String> {
        val root = tmp.newFolder()
        val repo = newRepo(root, "state.db")
        repo.upsertChat("meera_iyer", "Meera Iyer", fileName)
        return repo to File(root, "state.db").absolutePath
    }

    private fun entries(runId: Long = 1) =
        listOf(StateRepository.HashEntry("h1", "meera_iyer", "2025-03-20T09:00:00", runId))

    private fun sql(db: String, stmt: String) = SqliteJdbcStateDb(db).use { it.exec(stmt, emptyList()) }

    @Test
    fun ifTheHashInsertFailsTheChunkSendersAndIdsAreNotRecordedEither() {
        val (repo, db) = repoWithChat()
        sql(db, "CREATE TRIGGER no_hash BEFORE INSERT ON message_hashes BEGIN SELECT RAISE(ABORT, 'boom'); END")
        try {
            repo.recordChunkDelivered("meera_iyer", entries(), mapOf("Meera Iyer" to 1), "t9", "Label_WA", "<a@x>")
            fail("the hash failure must surface")
        } catch (_: Exception) {
            // expected
        }
        assertEquals(emptyMap<String, String?>(), counts(db))
        assertEquals(listOf<String?>(null, null, null), ids(db))
    }

    @Test
    fun ifTheSenderCountFailsTheHashesAndIdsStillCommitAndNothingThrows() {
        val (repo, db) = repoWithChat()
        sql(db, "CREATE TRIGGER no_sender BEFORE INSERT ON chat_senders BEGIN SELECT RAISE(ABORT, 'boom'); END")
        repo.recordChunkDelivered("meera_iyer", entries(), mapOf("Meera Iyer" to 1), "t9", "Label_WA", "<a@x>")
        assertEquals(1, hashCount(db))
        assertEquals(listOf<String?>("t9", "Label_WA", "<a@x>"), ids(db))
        assertEquals(emptyMap<String, String?>(), counts(db))
    }

    @Test
    fun theAnchorAndThreadAreOnlyWrittenWhileTheChatHasNone() {
        val (repo, db) = repoWithChat()
        repo.recordChunkDelivered("meera_iyer", entries(1), mapOf("Meera Iyer" to 1), "t1", "Label_WA", "<a@x>")
        repo.recordChunkDelivered(
            "meera_iyer",
            listOf(StateRepository.HashEntry("h2", "meera_iyer", "2025-03-21T09:00:00", 1)),
            mapOf("Meera Iyer" to 1), "t2", "Label_WA", "<b@x>",
        )
        assertEquals("the first anchor and thread win", listOf<String?>("t1", "Label_WA", "<a@x>"), ids(db))
        assertEquals(mapOf("Meera Iyer" to "2"), counts(db))
    }
}
