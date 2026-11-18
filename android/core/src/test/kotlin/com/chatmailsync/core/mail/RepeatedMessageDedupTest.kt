package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.msg
import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * F-05 (audit): the dedup key is `computeMessageHash(chatId, ts, sender, body)` and WhatsApp
 * stamps to the minute, so two genuinely separate messages with the same sender, minute and text
 * ("ok" twice, "This message was deleted" twice) have ONE fingerprint. The table stores it once
 * (`hash` is the primary key, INSERT OR IGNORE). If the two copies travel in different emails and
 * the run ends after the first, the next run (or recovery) sees the second copy as already sent.
 *
 * Every scenario is driven end to end through [SyncManager] with a recording mailbox, and the
 * copies are counted in what reached that mailbox (the traceability index attached to each email
 * lists exactly the messages it carries). BUG-24 / D167 = A: two copies with one fingerprint
 * are never split across emails (size split, server size refusal, message-count chunks), and a
 * renamed chat no longer skips a DIFFERENT text stamped in the mark's minute. Each scenario
 * asserts that both copies reach the mailbox, once, and never twice. The one scenario option A
 * cannot close (an export taken inside the repeated minute) stays @Ignore'd with its
 * `currentBehaviour...` pin. Synthetic messages, placeholder names only.
 */
class RepeatedMessageDedupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileName = "WhatsApp Chat with Meera Iyer.txt"
    private val chatId = extractChatInfo(fileName).chatId
    private val meera = "Meera Iyer"
    private val rohan = "Rohan Mehta"
    private val minute = "10:10"
    private val minuteIso = "2025-03-14T10:10:00"
    private val repeated = "ok"

    /** Big enough that two of them do not fit one email under [SPLIT_LIMIT], one of them does. */
    private val filler = "lorem ipsum dolor ".repeat(330)

    /** The fingerprint both copies of the repeated line share. */
    private fun repeatedHash(chat: String = chatId) = computeMessageHash(chat, minuteIso, rohan, repeated)

    private fun line(time: String, who: String, text: String, day: String = "14/03/25") = "$day, $time - $who: $text"

    /**
     * A day whose one email is too big, so it is halved between the two copies:
     * [long note, ok] | [ok, long note]. All four are on one day, two in the same minute.
     */
    private val splitDay = listOf(
        line("10:09", meera, "Note A $filler"),
        line(minute, rohan, repeated),
        line(minute, rohan, repeated),
        line("10:11", meera, "Note B $filler"),
    )

    // ---- the mailbox ---------------------------------------------------------------------

    /** Records every accepted email; can die, fail or refuse for size on a chosen insert. */
    private class Mailbox(override val maxMessageBytes: Long = DEFAULT_MAX_MESSAGE_BYTES) : MailTransport {
        val emails = mutableListOf<ByteArray>()
        var attempts = 0
            private set

        /** The process dies during the insert attempted when this many emails are stored. */
        var dieWhenStored: Int? = null

        /** A hard (non-retryable, non-size) failure on the insert attempted when this many are stored. */
        var failWhenStored: Int? = null

        /** The server refuses this insert attempt (1-based, counting every attempt) as too large. */
        var refuseTooLargeAttempt: Int? = null

        override fun labelsList(): List<ImapTransport.Label> = emptyList()
        override fun labelsCreate(name: String): String = "Label_WA"
        override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
            attempts++
            if (dieWhenStored == emails.size) throw ProcessDeath()
            if (failWhenStored == emails.size) throw RuntimeException("simulated connection reset")
            if (refuseTooLargeAttempt == attempts) throw transportError("APPEND failed (NO): [TOOBIG] Message too large", 413)
            emails.add(rawMessageBytes)
            return ImapTransport.InsertResult(id = "m$attempts", threadId = threadId?.takeIf { it.isNotEmpty() } ?: "t1")
        }

        /** The decoded `chatmailsync-index.json` of each stored email. */
        fun indexes(): List<String> = emails.map { indexOf(it) }

        /** How many times [hash] appears across every stored email (one entry per carried message). */
        fun copies(hash: String): Int = indexes().sumOf { Regex("\"hash\":\"$hash\"").findAll(it).count() }

        /** Copies of [hash] per stored email, in order. */
        fun copiesPerEmail(hash: String): List<Int> = indexes().map { Regex("\"hash\":\"$hash\"").findAll(it).count() }

        private fun indexOf(raw: ByteArray): String {
            val text = String(raw, Charsets.UTF_8)
            val at = text.indexOf("filename=\"$INDEX_FILENAME\"")
            check(at >= 0) { "no index part in an email" }
            val start = text.indexOf("\n\n", at) + 2
            val end = text.indexOf("\n--", start)
            val b64 = text.substring(start, end).filterNot { it == '\n' || it == '\r' }
            return String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
        }
    }

    private inner class Env(val maxBytes: Long = DEFAULT_MAX_MESSAGE_BYTES) {
        val root: File = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        val processed = File(root, "processed")
        val repo = newRepo(root)
        val mailbox = Mailbox(maxBytes)

        /** Stop (the user's Cancel) once this many emails are stored. Null = never. */
        var stopWhenStored: Int? = null

        fun write(lines: List<String>, name: String = fileName) {
            File(inbox, name).writeText(lines.joinToString("\n") + "\n")
        }

        fun manager(chunkSize: ChunkSize = ChunkSize.Day) = SyncManager(
            repo = repo, transport = mailbox, inboxDir = inbox, processedDir = processed,
            chunkSize = chunkSize, sleeper = { },
            shouldStop = { stopWhenStored?.let { mailbox.emails.size >= it } ?: false },
        )

        fun sync(chunkSize: ChunkSize = ChunkSize.Day): SyncStats = manager(chunkSize).run()

        /** A run the process dies in; the run row stays pending. */
        fun syncAndDie(chunkSize: ChunkSize = ChunkSize.Day) {
            try {
                manager(chunkSize).run()
                fail("the simulated process death should escape run()")
            } catch (_: ProcessDeath) {
            }
        }

        fun hashRows(hash: String): Int =
            SyncRunSupport.rows(File(root, "state.db").absolutePath, "SELECT hash FROM message_hashes WHERE hash = '$hash'", listOf("hash")).size

        fun exportStillQueued() = File(inbox, fileName).exists()
    }

    /** First half of the scenario: the split day goes out, interrupted by [interrupt] after email 1. */
    private fun interruptedSplitDay(interrupt: Env.() -> Unit): Env {
        val e = Env(SPLIT_LIMIT)
        e.write(splitDay)
        e.interrupt()
        assertEquals("precondition: exactly one email went before the interruption", 1, e.mailbox.emails.size)
        assertEquals("precondition: the copies were not split, the first email holds neither", listOf(0), e.mailbox.copiesPerEmail(repeatedHash()))
        assertTrue("precondition: the export waits for the next run", e.exportStillQueued())
        return e
    }

    private val byStop: Env.() -> Unit = { stopWhenStored = 1; sync() }
    private val byFailure: Env.() -> Unit = { mailbox.failWhenStored = 1; sync() }
    private val byProcessDeath: Env.() -> Unit = { mailbox.dieWhenStored = 1; syncAndDie() }

    /** Second half: the next run with nothing in the way, as the user would start it. */
    private fun resume(e: Env): SyncStats {
        e.stopWhenStored = null
        e.mailbox.failWhenStored = null
        e.mailbox.dieWhenStored = null
        return e.sync()
    }

    // ---- (a) where two same-minute copies can land -----------------------------------------

    @Test
    fun dayHourAndWeekChunksNeverSeparateTwoMessagesOfTheSameMinute() {
        val msgs = listOf(
            msg(chatId, "2025-03-14T10:09:00", meera, "before"),
            msg(chatId, minuteIso, rohan, repeated),
            msg(chatId, minuteIso, rohan, repeated),
            msg(chatId, "2025-03-14T11:30:00", meera, "after"),
        )
        for (size in listOf(ChunkSize.Day, ChunkSize.Hour, ChunkSize.Week)) {
            val holding = chunkMessages(msgs, size).count { c -> c.any { it.body == repeated } }
            assertEquals("$size: one chunk holds both copies", 1, holding)
        }
    }

    @Test
    fun aMessageCountChunkNeverSeparatesThem() {
        // Count(N) is not offered by the app's picker; it is reachable through a restored
        // backup's numeric chunk_size (accepted by validateRestoredSettings).
        val msgs = listOf(
            msg(chatId, "2025-03-14T10:09:00", meera, "before"),
            msg(chatId, minuteIso, rohan, repeated),
            msg(chatId, minuteIso, rohan, repeated),
        )
        val chunks = chunkMessages(msgs, ChunkSize.Count(2))
        assertEquals("BUG-24: the boundary moves past the repeat", listOf(3), chunks.map { it.size })
        assertEquals(listOf(2), chunks.map { c -> c.count { it.body == repeated } })
    }

    @Test
    fun aDayWithNoRepeatsIsChunkedExactlyAsBefore() {
        val msgs = (1..7).map { msg(chatId, "2025-03-14T10:0$it:00", meera, "line $it") }
        assertEquals(listOf(3, 3, 1), chunkMessages(msgs, ChunkSize.Count(3)).map { it.size })
        assertEquals(listOf(2, 2, 2, 1), chunkMessages(msgs, ChunkSize.Count(2)).map { it.size })
        // The halving point is the plain middle when nothing repeats.
        assertEquals(3, splitPoint(msgs))
        assertEquals(1, splitPoint(msgs.take(2)))
    }

    @Test
    fun theSplitPointSkipsARepeatEvenWhenAnotherMessageSitsBetweenTheCopies() {
        val a = msg(chatId, minuteIso, rohan, repeated)
        val between = msg(chatId, minuteIso, meera, "hello")
        val msgs = listOf(
            msg(chatId, "2025-03-14T10:09:00", meera, "x"), a, between, a.copy(),
            msg(chatId, "2025-03-14T10:11:00", meera, "y"),
        )
        val cut = splitPoint(msgs)!!
        val left = msgs.subList(0, cut).map { it.body }
        val right = msgs.subList(cut, msgs.size).map { it.body }
        assertFalse("NEGATIVE: the copies are never on both sides", repeated in left && repeated in right)
        // Every message is one big repeat: there is no legal cut at all.
        assertEquals(null, splitPoint(listOf(a, a.copy(), a.copy())))
    }

    @Test
    fun aSizeSplitKeepsTheTwoCopiesInOneEmailAndAnUninterruptedRunDeliversBoth() {
        val e = Env(SPLIT_LIMIT)
        e.write(splitDay)
        val stats = e.sync()
        assertEquals("the fixture really is split in two", 2, e.mailbox.emails.size)
        assertEquals("both copies in the same email", listOf(0, 2), e.mailbox.copiesPerEmail(repeatedHash()))
        assertEquals(4, stats.messagesSynced)
        assertFalse(e.exportStillQueued())

        // Re-export of the same history: nothing is mailed again (no duplicate).
        e.write(splitDay)
        val again = e.sync()
        assertEquals(0, again.messagesSynced)
        assertEquals(2, e.mailbox.emails.size)
        assertEquals(2, e.mailbox.copies(repeatedHash()))
    }

    @Test
    fun aDayWithNoRepeatsIsEmailedInExactlyTheSameGroupsAsBefore() {
        // Same shape as splitDay but every text differs: the old plain halving gives [2, 2].
        val e = Env(SPLIT_LIMIT)
        e.write(
            listOf(
                line("10:09", meera, "Note A $filler"),
                line(minute, rohan, "first"),
                line(minute, rohan, "second"),
                line("10:11", meera, "Note B $filler"),
            ),
        )
        e.sync()
        val perEmail = e.mailbox.indexes().map { Regex("\"hash\":").findAll(it).count() }
        assertEquals("NEGATIVE: not moved off the middle", listOf(2, 2), perEmail)
    }

    @Test
    fun anExistingLedgerRowStillStopsAResendAndTheFingerprintIsUnchanged() {
        val e = Env()
        val export = listOf(line("10:09", meera, "Shall we go?"), line(minute, rohan, repeated), line(minute, rohan, repeated))
        e.write(export)
        e.sync()
        // The stored fingerprint is exactly the old formula's value, one row for the pair.
        assertEquals(1, e.hashRows(computeMessageHash(chatId, minuteIso, rohan, repeated)))
        assertEquals(1, e.mailbox.emails.size)
        // An already-archived chat re-exported (and re-exported once more) is never re-sent.
        repeat(2) {
            e.write(export)
            assertEquals(0, e.sync().messagesSynced)
        }
        assertEquals(1, e.mailbox.emails.size)
    }

    // ---- (b) no interruption: both go together, both are skipped next time ----------------

    @Test
    fun plainReExportDeliversBothCopiesOnceAndNeverAgain() {
        val e = Env()
        val export = listOf(line("10:09", meera, "Shall we go?"), line(minute, rohan, repeated), line(minute, rohan, repeated))
        e.write(export)
        val first = e.sync()
        assertEquals(1, e.mailbox.emails.size)
        assertEquals("both copies in the one email", 2, e.mailbox.copies(repeatedHash()))
        assertEquals(3, first.messagesSynced)

        e.write(export)
        val second = e.sync()
        assertEquals("NEGATIVE: nothing mailed twice", 1, e.mailbox.emails.size)
        assertEquals(0, second.messagesSynced)
        assertEquals(3, second.messagesSkipped)
        assertEquals(2, e.mailbox.copies(repeatedHash()))
    }

    // ---- interrupted after the first email: user Stop --------------------------------------

    @Test
    fun stopBetweenSplitEmailsThenNextRunBothCopiesAreArchivedOnce() {
        val e = interruptedSplitDay(byStop)
        val stats = resume(e)
        assertEquals(2, e.mailbox.copies(repeatedHash()))
        assertEquals("NEGATIVE: both copies in the one email, none re-sent", listOf(0, 2), e.mailbox.copiesPerEmail(repeatedHash()))
        assertEquals(3, stats.messagesSynced)
        assertFalse(e.exportStillQueued())
        // NEGATIVE: a re-export mails nothing again.
        e.write(splitDay)
        assertEquals(0, e.sync().messagesSynced)
        assertEquals(2, e.mailbox.emails.size)
    }

    // ---- interrupted after the first email: hard failure -----------------------------------

    @Test
    fun failureBetweenSplitEmailsThenNextRunBothCopiesAreArchivedOnce() {
        val e = interruptedSplitDay(byFailure)
        val stats = resume(e)
        assertEquals(2, e.mailbox.copies(repeatedHash()))
        assertEquals(listOf(0, 2), e.mailbox.copiesPerEmail(repeatedHash()))
        assertEquals(3, stats.messagesSynced)
        assertEquals(0, stats.filesFailed)
        assertFalse(e.exportStillQueued())
    }

    // ---- interrupted after the first email: process death, then recovery ------------------

    @Test
    fun processDeathBetweenSplitEmailsThenRecoveryBothCopiesAreArchivedOnce() {
        val e = interruptedSplitDay(byProcessDeath)
        assertEquals("precondition: the run was left pending", 1, e.repo.getPendingRuns().size)
        val stats = resume(e)
        assertEquals("recovery finished the run", 1, stats.chatsRecovered)
        assertEquals(0, e.repo.getPendingRuns().size)
        assertEquals(2, e.mailbox.copies(repeatedHash()))
        assertEquals(listOf(0, 2), e.mailbox.copiesPerEmail(repeatedHash()))
        assertFalse(e.exportStillQueued())
    }

    // ---- the server's own size refusal does the split ----------------------------------------

    private fun refusedThenStopped(): Env {
        // Under the default limit the day is ONE email; the server refuses it as too large, the
        // push halves it between the copies, and the user stops after the first half.
        val e = Env()
        e.write(splitDay)
        e.mailbox.refuseTooLargeAttempt = 1
        e.stopWhenStored = 1
        e.sync()
        assertEquals("precondition: one half went", 1, e.mailbox.emails.size)
        assertEquals("precondition: the halves were cut around the repeat", 0, e.mailbox.copies(repeatedHash()))
        e.mailbox.refuseTooLargeAttempt = null
        return e
    }

    @Test
    fun sizeRefusalSplitThenStopBothCopiesAreArchivedOnce() {
        val e = refusedThenStopped()
        resume(e)
        assertEquals(2, e.mailbox.copies(repeatedHash()))
        assertEquals("NEGATIVE: neither copy is split or re-sent", listOf(0, 2), e.mailbox.copiesPerEmail(repeatedHash()))
    }

    // ---- a message-count chunk (restored numeric setting) ------------------------------------

    private val countChunked = listOf(line("10:09", meera, "Shall we go?"), line(minute, rohan, repeated), line(minute, rohan, repeated))

    private fun countChunkStopped(): Env {
        val e = Env()
        e.write(countChunked)
        e.stopWhenStored = 1
        e.sync(ChunkSize.Count(2))
        assertEquals("precondition: the chunk grew to keep both copies together", listOf(2), e.mailbox.copiesPerEmail(repeatedHash()))
        return e
    }

    @Test
    fun countChunkThenNextRunBothCopiesAreArchivedOnce() {
        val e = countChunkStopped()
        e.stopWhenStored = null
        val stats = e.sync(ChunkSize.Count(2))
        assertEquals("NEGATIVE: nothing is mailed twice", 1, e.mailbox.emails.size)
        assertEquals(2, e.mailbox.copies(repeatedHash()))
        assertEquals(0, stats.messagesSynced)
        assertFalse(e.exportStillQueued())
    }

    // ---- no interruption at all: the export was taken inside the repeated minute -----------

    private fun exportedMidMinute(): Env {
        // Export 1 is made after the first "ok" and before the second, both stamped 10:10.
        val e = Env()
        e.write(listOf(line("10:09", meera, "Shall we go?"), line(minute, rohan, repeated)))
        e.sync()
        assertEquals("precondition", 1, e.mailbox.copies(repeatedHash()))
        assertFalse(e.exportStillQueued())
        e.write(listOf(line("10:09", meera, "Shall we go?"), line(minute, rohan, repeated), line(minute, rohan, repeated)))
        return e
    }

    @Ignore("BUG-24 / D167 = A: stays open. An export taken between the two copies files one fingerprint, so the next export's second copy is skipped; only an occurrence index closes it and that is a ledger format change A excludes.")
    @Test
    fun exportTakenMidMinuteThenNextExportBothCopiesAreArchived() {
        val e = exportedMidMinute()
        e.sync()
        assertEquals(2, e.mailbox.copies(repeatedHash()))
    }

    @Test
    fun currentBehaviourExportTakenMidMinuteLosesTheSecondCopy() {
        val e = exportedMidMinute()
        val stats = e.sync()
        assertEquals("CURRENT: no second email", 1, e.mailbox.emails.size)
        assertEquals("CURRENT: the 2nd copy is never mailed", 1, e.mailbox.copies(repeatedHash()))
        assertEquals(0, stats.messagesSynced)
        assertEquals(3, stats.messagesSkipped)
    }

    // ---- (d) two copies in one email: one stored row --------------------------------------

    @Test
    fun currentBehaviourTwoCopiesInOneEmailAreStoredAsOneFingerprintRow() {
        val e = Env()
        e.write(listOf(line("10:09", meera, "Shall we go?"), line(minute, rohan, repeated), line(minute, rohan, repeated)))
        val stats = e.sync()
        assertEquals(2, e.mailbox.copies(repeatedHash()))
        assertEquals("the run reports three sent", 3, stats.messagesSynced)
        assertEquals(3, e.repo.getRecentRuns(days = 36500).single().messagesSynced)
        // INSERT OR IGNORE on the primary key: the second copy adds no row.
        assertEquals(1, e.hashRows(repeatedHash()))
        assertEquals("CURRENT: the filed count is one short of what was mailed", 2, e.repo.countArchivedMessages(chatId))
        assertEquals(2, e.repo.getSyncSummary().single().totalFiled)
    }

    // ---- (e) a chat with linked names: the time rule after a stop -------------------------

    /**
     * After a confirmed rename the overlap mark also honours the newest ARCHIVED message and a
     * message exactly at the mark is skipped (exactMarkIsSkipped). A Stop after email 1 of a split
     * day makes the 2nd email's first message sit at the mark, so it is skipped even with a
     * DIFFERENT text. Two different texts here on purpose: this is the wider form of F-05.
     */
    private var linkedExport: List<String> = emptyList()

    private fun linkedChatStoppedAfterOneSplitEmail(): Env {
        val number = "+91 90000 00001"
        val e = Env(SPLIT_LIMIT)
        fun history(other: String) = (1..5).map {
            line("09:00", if (it % 2 == 0) meera else other, "message $it", day = "%02d/03/25".format(it))
        }
        e.write(history(number), "WhatsApp Chat with $number.txt")
        assertEquals(5, e.sync().messagesSynced)
        val sent = e.mailbox.emails.size

        val newDay = listOf(
            line("10:09", meera, "Note A $filler"),
            line(minute, rohan, "first reply"),
            line(minute, rohan, "second reply"),
            line("10:11", meera, "Note B $filler"),
        )
        linkedExport = history(rohan) + newDay
        e.write(linkedExport, fileName)
        e.sync() // held: looks like the renamed chat
        val p = scanLinkProposals(e.repo, e.inbox).single()
        applyLink(e.repo, p, File(e.inbox, p.fileName))

        e.stopWhenStored = sent + 1
        e.sync()
        assertEquals("precondition: one email of the new day went", sent + 1, e.mailbox.emails.size)
        assertTrue(e.exportStillQueued())
        e.stopWhenStored = null
        return e
    }

    private fun linkedHash(e: Env, body: String) = computeMessageHash(e.repo.resolveChatId(chatId), minuteIso, rohan, body)

    @Test
    fun linkedChatStopThenNextRunTheSameMinuteMessageIsArchived() {
        val e = linkedChatStoppedAfterOneSplitEmail()
        e.sync()
        assertEquals("NEGATIVE: sent once, not twice", 1, e.mailbox.copies(linkedHash(e, "first reply")))
        assertEquals(1, e.mailbox.copies(linkedHash(e, "second reply")))
        assertFalse(e.exportStillQueued())
        // NEGATIVE: re-exporting the same renamed chat mails nothing again, the mark's minute included.
        val before = e.mailbox.emails.size
        e.write(linkedExport, fileName)
        assertEquals(0, e.sync().messagesSynced)
        assertEquals(before, e.mailbox.emails.size)
        assertEquals(1, e.mailbox.copies(linkedHash(e, "first reply")))
        assertEquals(1, e.mailbox.copies(linkedHash(e, "second reply")))
    }

    private companion object {
        /** A provider limit small enough to halve [splitDay]; the default is 25 MB. */
        const val SPLIT_LIMIT = 18_000L
    }
}
