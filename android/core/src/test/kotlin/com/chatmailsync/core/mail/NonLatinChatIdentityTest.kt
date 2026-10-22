package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BUG-06: a name with no ASCII letter or digit used to collapse to one shared `unknown_chat`
 * record. Such a name now has an id derived from the name; every other id is unchanged.
 * All names here are made up (escapes keep the source ASCII).
 */
class NonLatinChatIdentityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Two made-up Devanagari group names and a made-up Cyrillic one.
    private val groupA = "मित्र मंडल"
    private val groupB = "परिवार समूह"
    private val cyr = "Друзья"

    /** A verbatim copy of the 2.9.1 id logic, kept here as the yardstick for "byte-identical". */
    private fun oldId(display: String): String {
        var d = display
        val stripped = d.replace(Regex("\\s*\\([0-9]+\\)$"), "").trim()
        if (stripped.isNotEmpty()) d = stripped
        var id = d.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9\\s]"), "")
        id = id.trim().replace(Regex("\\s+"), "_").trim('_')
        return if (id.isEmpty()) "unknown_chat" else id
    }

    @Test
    fun everyNameThatGaveAnIdBeforeKeepsItByteForByte() {
        val names = listOf(
            "Rohan Mehta", "ROHAN MEHTA", "+91 90000 00001", "Family Group (1)", "Asha-Rao",
            "Café Crew", "Meera Iyer ❤", "Röhan 2025", "a", "  Spaced   Out  ",
            "Group मित्र Two",
        )
        for (n in names) {
            val got = extractChatInfo("WhatsApp Chat with $n.txt").chatId
            assertEquals(oldId(n), got)
            assertFalse("a Latin-bearing name must not use the new scheme", got.startsWith("nl_"))
        }
    }

    @Test
    fun distinctNonLatinNamesGetDistinctStableIds() {
        val a = extractChatInfo("WhatsApp Chat with $groupA.txt").chatId
        val b = extractChatInfo("WhatsApp Chat with $groupB.txt").chatId
        assertTrue(a.startsWith("nl_") && a.length == 19)
        assertNotEquals(a, b)
        // Stable across exports, including the " (1)" copy and a letter-case change.
        assertEquals(a, extractChatInfo("WhatsApp Chat with $groupA (1).txt").chatId)
        assertEquals(
            extractChatInfo("WhatsApp Chat with $cyr.txt").chatId,
            extractChatInfo("WhatsApp Chat with ${cyr.lowercase(Locale.ROOT)}.txt").chatId,
        )
        // Composed and decomposed spellings of the same letters are one name.
        val composed = "й"
        val decomposed = "й"
        assertEquals(
            extractChatInfo("WhatsApp Chat with $composed.txt").chatId,
            extractChatInfo("WhatsApp Chat with $decomposed.txt").chatId,
        )
        assertNotEquals(a, "unknown_chat")
    }

    private inner class Env {
        val root: File = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        val repo = newRepo(root)
        val transport = FakeMailTransport()

        fun export(name: String, word: String, vararg days: Int): File {
            val text = days.joinToString("\n") {
                "%02d/03/25, 09:00 - %s: %s %d".format(it, if (it % 2 == 0) "Meera Iyer" else "Rohan Mehta", word, it)
            } + "\n"
            return File(inbox, "WhatsApp Chat with $name.txt").also { it.writeText(text) }
        }

        /** What a 2.9.1 install stored: every message of [f] fingerprinted under `unknown_chat`. */
        fun legacy(f: File, runId: Long) {
            val hashes = parseFile(f, LEGACY_CHAT_ID).map {
                StateRepository.HashEntry(
                    StateRepository.computeMessageHash(LEGACY_CHAT_ID, it.timestampIso, it.sender, it.body),
                    LEGACY_CHAT_ID, it.timestampIso, runId,
                )
            }.toList()
            repo.insertMessageHashes(hashes)
        }

        fun legacyRecord(): Long {
            repo.upsertChat(
                LEGACY_CHAT_ID, LEGACY_CHAT_ID, "legacy.txt",
                gmailThreadId = "T-legacy", gmailLabelId = "WhatsApp/Some Other Chat", anchorMessageId = "<a@x>",
            )
            return repo.startSyncRun(LEGACY_CHAT_ID)
        }

        fun sync(): SyncStats =
            SyncManager(repo, transport, inbox, File(root, "processed"), sleeper = { }).run()

        fun proposals() = scanLinkProposals(repo, inbox)
    }

    private fun legacyEnv(): Triple<Env, File, File> {
        val e = Env()
        val run = e.legacyRecord()
        val fa = e.export(groupA, "alpha", 1, 2, 3, 4, 5, 6)
        val fb = e.export(groupB, "beta", 1, 2, 3, 4, 5, 6)
        e.legacy(fa, run)
        e.legacy(fb, run)
        e.repo.completeSyncRun(run, "2025-03-06T09:00:00", null, 12, 12, 0)
        e.repo.setChatCutoff(LEGACY_CHAT_ID, "2025-01-01")
        return Triple(e, fa, fb)
    }

    @Test
    fun anOldUnknownChatRecordLinksToTheRightChatAfterConfirm() {
        val (e, _, _) = legacyEnv()
        val ids = setOf(extractChatInfo("$groupA.txt").chatId, extractChatInfo("$groupB.txt").chatId)
        val ps = e.proposals()
        assertEquals(2, ps.size)
        assertTrue(ps.all { it.kind == LinkKind.LEGACY && it.oldChatId == LEGACY_CHAT_ID && it.matched == 6 })
        assertEquals(ids, ps.map { it.newChatId }.toSet())

        val held = e.sync()
        assertEquals(2, held.filesHeld)
        assertEquals("nothing is sent while the question is open", 0, e.transport.inserted.size)

        val pa = ps.single { it.newChatId == extractChatInfo("$groupA.txt").chatId }
        val carried = applyLink(e.repo, pa, File(e.inbox, pa.fileName))
        assertEquals(6, carried)
        assertEquals(6, e.repo.countArchivedMessages(pa.newChatId))
        assertEquals("only A's fingerprints left the shared record", 6, e.repo.countArchivedMessages(LEGACY_CHAT_ID))
        assertNotNull("the legacy record's other rows stay", e.repo.getChat(LEGACY_CHAT_ID))
        assertEquals("T-legacy", e.repo.getChat(LEGACY_CHAT_ID)?.gmailThreadId)
        assertEquals("2025-01-01T00:00:00", e.repo.getChatCutoff(pa.newChatId))
        assertNull("thread cannot be attributed to one chat, so it is not guessed", e.repo.getChat(pa.newChatId)?.gmailThreadId)

        val pb = e.proposals().single()
        applyLink(e.repo, pb, File(e.inbox, pb.fileName))
        val after = e.sync()
        assertEquals(0, after.filesHeld)
        assertEquals("after the links NOTHING already archived is mailed again", 0, e.transport.inserted.size)
        assertEquals(6, e.repo.countArchivedMessages(pa.newChatId))
        assertEquals(6, e.repo.countArchivedMessages(pb.newChatId))
        assertEquals(0, e.repo.countArchivedMessages(LEGACY_CHAT_ID))
    }

    @Test
    fun resetOnOneNonLatinChatLeavesTheOtherAlone() {
        val (e, _, _) = legacyEnv()
        for (p in e.proposals()) applyLink(e.repo, p, File(e.inbox, p.fileName))
        val a = extractChatInfo("$groupA.txt").chatId
        val b = extractChatInfo("$groupB.txt").chatId
        e.repo.setChatCutoff(a, "2025-02-01")
        e.repo.resetChat(a, confirmedMailboxCleared = true)
        assertEquals(0, e.repo.countArchivedMessages(a))
        assertEquals("the other chat keeps its history", 6, e.repo.countArchivedMessages(b))
        assertNull(e.repo.getChat(b)?.gmailThreadId)
        assertEquals("2025-01-01T00:00:00", e.repo.getChatCutoff(b))
    }

    @Test
    fun aDeclinedLegacyLinkStaysSeparateAndIsNotAskedAgain() {
        val (e, _, _) = legacyEnv()
        for (p in e.proposals()) declineLink(e.repo, p)
        assertEquals(0, e.proposals().size)
        val s = e.sync()
        assertEquals(0, s.filesHeld)
        assertEquals("kept separate: each is a chat of its own, fully mailed", 12, e.transport.inserted.size)
        assertEquals(3, e.repo.listChats().size)
        assertEquals(2, e.repo.listChats().map { it.chatId }.count { it.startsWith("nl_") })
    }

    @Test
    fun anUnlinkedUnknownChatRecordKeepsSyncingWithoutAFolderRename() {
        val e = Env()
        val run = e.legacyRecord()
        // An export whose name is empty still maps to the shared id, and its folder is not ours to rename.
        e.transport.folders.add("WhatsApp/Some Other Chat")
        val f = e.export("", "gamma", 1, 2, 3)
        e.legacy(f, run)
        e.repo.completeSyncRun(run, "2025-03-03T09:00:00", null, 3, 3, 0)
        assertEquals(LEGACY_CHAT_ID, extractChatInfo(f.name).chatId)
        File(e.inbox, f.name).writeText(f.readText() + "04/03/25, 09:00 - Meera Iyer: gamma 4\n")
        val s = e.sync()
        assertEquals(0, s.filesHeld)
        assertEquals(1, e.transport.inserted.size)
        assertEquals(0, e.transport.renameCalls.size)
        assertTrue("another chat's folder is never renamed away", "WhatsApp/Some Other Chat" in e.transport.folders)
    }
}
