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
 * lists exactly the messages it carries). The `...BothCopiesAreArchived` tests assert the CORRECT
 * outcome; the ones that fail on the current code are @Ignore'd as open, and each has a
 * `currentBehaviour...` companion pinning what the code does today, so a fix must change it on
 * purpose. Synthetic messages, placeholder names only.
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
        assertEquals("precondition: it carried one copy", listOf(1), e.mailbox.copiesPerEmail(repeatedHash()))
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
    fun aMessageCountChunkCanSeparateThem() {
        // Count(N) is not offered by the app's picker; it is reachable through a restored
        // backup's numeric chunk_size (accepted by validateRestoredSettings).
        val msgs = listOf(
            msg(chatId, "2025-03-14T10:09:00", meera, "before"),
            msg(chatId, minuteIso, rohan, repeated),
            msg(chatId, minuteIso, rohan, repeated),
        )
        val chunks = chunkMessages(msgs, ChunkSize.Count(2))
        assertEquals(listOf(1, 1), chunks.map { c -> c.count { it.body == repeated } })
    }

    @Test
    fun aSizeSplitPutsTheTwoCopiesInDifferentEmailsAndAnUninterruptedRunDeliversBoth() {
        val e = Env(SPLIT_LIMIT)
        e.write(splitDay)
        val stats = e.sync()
        assertEquals("the fixture really is split in two", 2, e.mailbox.emails.size)
        assertEquals("one copy in each email", listOf(1, 1), e.mailbox.copiesPerEmail(repeatedHash()))
        assertEquals(4, stats.messagesSynced)
        assertFalse(e.exportStillQueued())

        // Re-export of the same history: nothing is mailed again (no duplicate).
        e.write(splitDay)
        val again = e.sync()
        assertEquals(0, again.messagesSynced)
        assertEquals(2, e.mailbox.emails.size)
        assertEquals(2, e.mailbox.copies(repeatedHash()))
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

    @Ignore("F-05: size-split day, user Stop after email 1, next run skips the 2nd identical copy; open, awaiting decision")
    @Test
    fun stopBetweenSplitEmailsThenNextRunBothCopiesAreArchived() {
        val e = interruptedSplitDay(byStop)
        resume(e)
        assertEquals(2, e.mailbox.copies(repeatedHash()))
    }

    @Test
    fun currentBehaviourStopBetweenSplitEmailsLosesTheSecondCopy() {
        val e = interruptedSplitDay(byStop)
        val stats = resume(e)
        assertEquals("the rest of the day still goes", 2, e.mailbox.emails.size)
        assertEquals("CURRENT: the 2nd copy is never mailed", 1, e.mailbox.copies(repeatedHash()))
        assertEquals("CURRENT: only Note B is new", 1, stats.messagesSynced)
        assertEquals(3, stats.messagesSkipped)
        assertEquals("NEGATIVE: never more than one copy", listOf(1, 0), e.mailbox.copiesPerEmail(repeatedHash()))
        assertFalse("the export is deleted as fully synced", e.exportStillQueued())
    }

    // ---- interrupted after the first email: hard failure -----------------------------------

    @Ignore("F-05: size-split day, hard insert failure on email 2, next run skips the 2nd identical copy; open, awaiting decision")
    @Test
    fun failureBetweenSplitEmailsThenNextRunBothCopiesAreArchived() {
        val e = interruptedSplitDay(byFailure)
        resume(e)
        assertEquals(2, e.mailbox.copies(repeatedHash()))
    }

    @Test
    fun currentBehaviourFailureBetweenSplitEmailsLosesTheSecondCopy() {
        val e = interruptedSplitDay(byFailure)
        val stats = resume(e)
        assertEquals(2, e.mailbox.emails.size)
        assertEquals("CURRENT: the 2nd copy is never mailed", 1, e.mailbox.copies(repeatedHash()))
        assertEquals(1, stats.messagesSynced)
        assertEquals(0, stats.filesFailed)
        assertFalse(e.exportStillQueued())
    }

    // ---- interrupted after the first email: process death, then recovery ------------------

    @Ignore("F-05: size-split day, process killed during email 2, recoverPendingRuns skips the 2nd identical copy; open, awaiting decision")
    @Test
    fun processDeathBetweenSplitEmailsThenRecoveryBothCopiesAreArchived() {
        val e = interruptedSplitDay(byProcessDeath)
        resume(e)
        assertEquals(2, e.mailbox.copies(repeatedHash()))
    }

    @Test
    fun currentBehaviourProcessDeathBetweenSplitEmailsRecoveryLosesTheSecondCopy() {
        val e = interruptedSplitDay(byProcessDeath)
        assertEquals("precondition: the run was left pending", 1, e.repo.getPendingRuns().size)
        val stats = resume(e)
        assertEquals("recovery finished the run", 1, stats.chatsRecovered)
        assertEquals(0, e.repo.getPendingRuns().size)
        assertEquals(2, e.mailbox.emails.size)
        assertEquals("CURRENT: the 2nd copy is never mailed", 1, e.mailbox.copies(repeatedHash()))
        assertEquals("recovery sent only Note B", 1, stats.messagesSynced)
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
        assertEquals("precondition: it carried one copy", 1, e.mailbox.copies(repeatedHash()))
        e.mailbox.refuseTooLargeAttempt = null
        return e
    }

    @Ignore("F-05: server size refusal halves the day, user Stop after half 1, next run skips the 2nd identical copy; open, awaiting decision")
    @Test
    fun sizeRefusalSplitThenStopBothCopiesAreArchived() {
        val e = refusedThenStopped()
        resume(e)
        assertEquals(2, e.mailbox.copies(repeatedHash()))
    }

    @Test
    fun currentBehaviourSizeRefusalSplitThenStopLosesTheSecondCopy() {
        val e = refusedThenStopped()
        resume(e)
        assertEquals(2, e.mailbox.emails.size)
        assertEquals("CURRENT: the 2nd copy is never mailed", 1, e.mailbox.copies(repeatedHash()))
    }

    // ---- a message-count chunk (restored numeric setting) ------------------------------------

    private val countChunked = listOf(line("10:09", meera, "Shall we go?"), line(minute, rohan, repeated), line(minute, rohan, repeated))

    private fun countChunkStopped(): Env {
        val e = Env()
        e.write(countChunked)
        e.stopWhenStored = 1
        e.sync(ChunkSize.Count(2))
        assertEquals("precondition: [Shall we go?, ok] went", listOf(1), e.mailbox.copiesPerEmail(repeatedHash()))
        return e
    }

    @Ignore("F-05: Count(2) chunks split the copies, user Stop after email 1, next run skips the 2nd identical copy; open, awaiting decision")
    @Test
    fun countChunkStopThenNextRunBothCopiesAreArchived() {
        val e = countChunkStopped()
        e.stopWhenStored = null
        e.sync(ChunkSize.Count(2))
        assertEquals(2, e.mailbox.copies(repeatedHash()))
    }

    @Test
    fun currentBehaviourCountChunkStopLosesTheSecondCopy() {
        val e = countChunkStopped()
        e.stopWhenStored = null
        val stats = e.sync(ChunkSize.Count(2))
        assertEquals("CURRENT: nothing is left to send", 1, e.mailbox.emails.size)
        assertEquals("CURRENT: the 2nd copy is never mailed", 1, e.mailbox.copies(repeatedHash()))
        assertEquals(0, stats.messagesSynced)
        assertFalse("the export is deleted as fully synced", e.exportStillQueued())
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

    @Ignore("F-05: export taken inside the minute of the 1st copy, next export's 2nd identical copy is skipped (no interruption); open, awaiting decision")
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
        e.write(history(rohan) + newDay, fileName)
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

    @Ignore("F-05(e): linked chat, size-split day, Stop after email 1, a different-text message in the same minute is skipped by the time rule; open, awaiting decision")
    @Test
    fun linkedChatStopThenNextRunTheSameMinuteMessageIsArchived() {
        val e = linkedChatStoppedAfterOneSplitEmail()
        e.sync()
        assertEquals(1, e.mailbox.copies(linkedHash(e, "first reply")))
        assertEquals(1, e.mailbox.copies(linkedHash(e, "second reply")))
    }

    @Test
    fun currentBehaviourLinkedChatStopLosesTheNextSameMinuteMessage() {
        val e = linkedChatStoppedAfterOneSplitEmail()
        val target = e.repo.resolveChatId(chatId)
        // The cause: the stopped run's delivered "first reply" set the archived mark to this minute,
        // and a linked chat skips a message exactly at the mark; "second reply" has no stored hash.
        assertTrue(e.repo.hasAliases(target))
        assertEquals(minuteIso, e.repo.newestArchivedTs(target))
        assertEquals(0, e.hashRows(linkedHash(e, "second reply")))
        val stats = e.sync()
        assertEquals(1, e.mailbox.copies(linkedHash(e, "first reply")))
        assertEquals("CURRENT: never mailed", 0, e.mailbox.copies(linkedHash(e, "second reply")))
        assertEquals("only Note B goes", 1, stats.messagesSynced)
        assertFalse(e.exportStillQueued())
    }

    private companion object {
        /** A provider limit small enough to halve [splitDay]; the default is 25 MB. */
        const val SPLIT_LIMIT = 18_000L
    }
}
