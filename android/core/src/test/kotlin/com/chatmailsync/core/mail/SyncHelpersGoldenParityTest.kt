package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.golden
import com.chatmailsync.core.mail.SyncTestSupport.ints
import com.chatmailsync.core.mail.SyncTestSupport.msgOf
import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import com.chatmailsync.core.mail.SyncTestSupport.strings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Parity of the sync helpers with the real `src/sync_manager.py` on Python 3.13
 * (`sync_helpers_golden.json`, written by `generate_sync_helpers_golden()`).
 * The expected values come from running the real Python, not from this port.
 */
class SyncHelpersGoldenParityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val g = golden("sync_helpers_golden.json")
    private val chat = "test_chat"

    @Test
    fun syncStatsTextMatchesPython() {
        for (c in g["statsStr"].asArr().items) {
            val f = c["fields"].asObj().fields
            val s = SyncStats()
            f["files_found"]?.let { s.filesFound = it.asInt() }
            f["files_synced"]?.let { s.filesSynced = it.asInt() }
            f["files_skipped"]?.let { s.filesSkipped = it.asInt() }
            f["files_failed"]?.let { s.filesFailed = it.asInt() }
            f["messages_parsed"]?.let { s.messagesParsed = it.asInt() }
            f["messages_synced"]?.let { s.messagesSynced = it.asInt() }
            f["messages_skipped"]?.let { s.messagesSkipped = it.asInt() }
            f["messages_cutoff"]?.let { s.messagesCutoff = it.asInt() }
            f["chats_recovered"]?.let { s.chatsRecovered = it.asInt() }
            f["errors"]?.let { s.errors.addAll(strings(it)) }
            f["media_omitted"]?.let { s.mediaOmitted.addAll(strings(it)) }
            assertEquals(c["text"].asString(), s.toString())
        }
    }

    @Test
    fun omissionLinesMatchPython() {
        for (c in g["omissions"].asArr().items) {
            val stats = SyncStats()
            stats.mediaOmitted.addAll(strings(c["existing"]))
            val results = c["results"].asArr().items.map { r ->
                PushResult(
                    "id", "id", "t",
                    r.asArr().items.map { o ->
                        val a = o.asArr().items
                        HtmlRenderer.MediaOmission(a[0].asString(), a[1].asLong(), a[2].asLong())
                    },
                )
            }
            collectOmissions(stats, c["display"].asString(), results)
            assertEquals(c["name"].asString(), strings(c["mediaOmitted"]), stats.mediaOmitted)
        }
    }

    @Test
    fun scrubPathsMatchesPython() {
        for (c in g["scrubPaths"].asArr().items) {
            assertEquals(c["input"].asString(), c["output"].asString(), scrubPaths(c["input"].asString()))
        }
    }

    @Test
    fun stemAndSuffixFollowPython313() {
        for (c in g["stemSuffix"].asArr().items) {
            val n = c["name"].asString()
            assertEquals("stem of '$n'", c["stem"].asString(), pyStem(n))
            assertEquals("suffix of '$n'", c["suffix"].asString(), pySuffix(n))
        }
    }

    @Test
    fun inboxListingSelectsAndOrdersLikePython() {
        val dir = tmp.newFolder("inbox")
        val listing = g["inboxListing"]
        for (n in strings(listing["names"])) File(dir, n).writeText("x")
        File(dir, "sub.txt").mkdir() // a directory is never listed
        assertEquals(strings(listing["expected"]), listInboxFiles(dir).map { it.name })
    }

    @Test
    fun filterRulesMatchPython() {
        for ((n, c) in g["filter"].asArr().items.withIndex()) {
            val repo = newRepo(tmp.newFolder("flt$n"))
            repo.upsertChat(chat, "Meera Iyer", "Meera Iyer.txt")
            val msgs = c["messages"].asArr().items.map { msgOf(chat, it) }
            c["chatCutoff"].asStringOrNull()?.let { repo.setChatCutoff(chat, it) }
            val known = ints(c["knownIdx"])
            if (known.isNotEmpty()) {
                val runId = repo.startSyncRun(chat)
                repo.insertMessageHashes(buildHashEntries(msgs.filterIndexed { i, _ -> i in known }, runId))
            }
            val appCutoff = normaliseCutoff(c["appCutoff"].asStringOrNull())
            val r = filterMessages(repo, msgs, chat, c["lastSyncedTs"].asStringOrNull(), appCutoff)
            val name = c["name"].asString()
            assertEquals(name, ints(c["newIdx"]), r.newMessages.map { m -> msgs.indexOfFirst { it === m } })
            assertEquals("$name skipped", c["skipped"].asInt(), r.skipped)
            assertEquals("$name cutoff", c["cutoff"].asInt(), r.cutoff)
        }
    }

    @Test
    fun selfSenderResolutionMatchesPython() {
        for ((n, c) in g["selfSender"].asArr().items.withIndex()) {
            val repo = newRepo(tmp.newFolder("self$n"))
            c["override"].asStringOrNull()?.let { repo.setAppState(StateRepository.SELF_SENDER_OVERRIDE, it) }
            c["learned"].asStringOrNull()?.let { repo.setAppState(StateRepository.SELF_SENDER_LEARNED, it) }
            val msgs = strings(c["senders"]).map { SyncTestSupport.msg(chat, "2025-03-01T10:00:00", it, "hi") }
            val got = resolveSelfSenderFor(repo, "Meera Iyer", msgs)
            val name = c["name"].asString()
            assertEquals(name, c["result"].asString(), got)
            assertEquals("$name learned", c["learnedAfter"].asStringOrNull(), repo.getAppState(StateRepository.SELF_SENDER_LEARNED))
            assertEquals("$name pending", c["pendingAfter"].asStringOrNull(), repo.getAppState(StateRepository.SELF_SENDER_LEARNED_PENDING))
        }
    }

    @Test
    fun senderTalliesMatchPython() {
        for ((n, c) in g["recordSenders"].asArr().items.withIndex()) {
            val repo = newRepo(tmp.newFolder("rec$n"))
            repo.upsertChat(chat, "Meera Iyer", "Meera Iyer.txt")
            for (step in c["steps"].asArr().items) {
                val all = strings(step["all"]).map { SyncTestSupport.msg(chat, "2025-03-01T10:00:00", it, "hi") }
                val pushed = strings(step["pushed"]).map { SyncTestSupport.msg(chat, "2025-03-01T10:00:00", it, "hi") }
                recordChatSendersFor(repo, chat, all, pushed)
                val want = step["countsAfter"].asObj().fields.mapValues { it.value.asInt() }
                val got = repo.listChatSenders(chat).associate { it.sender to it.msgCount }
                assertEquals(c["name"].asString(), want, got)
            }
        }
    }

    @Test
    fun moveToProcessedMatchesPython() {
        for ((n, c) in g["moveToProcessed"].asArr().items.withIndex()) {
            val base = tmp.newFolder("mv$n")
            val inbox = File(base, "inbox").also { it.mkdirs() }
            val processed = File(base, "processed").also { it.mkdirs() }
            val fname = c["filename"].asString()
            val src = File(inbox, fname).also { it.writeText("new export") }
            for (p in strings(c["processed"])) File(processed, p).writeText("old export")
            val name = c["name"].asString()

            assertEquals(
                "$name superseded",
                strings(c["supersededBeforeMove"]),
                supersededExports(processed, fname).filter { it != src }.map { it.name }.sorted(),
            )
            moveToProcessed(src, processed)
            assertEquals("$name processed", strings(c["processedAfter"]), processed.list()!!.sorted())
            assertEquals("$name inbox", strings(c["inboxAfter"]), inbox.list()!!.sorted())
            assertEquals("$name content", c["movedContent"].asString(), File(processed, fname).readText())
        }
    }

    // ---- negative: the wrong behaviour does not happen

    @Test
    fun aNeighbouringChatsExportIsNeverPruned() {
        val processed = tmp.newFolder("neighbour")
        for (p in listOf("Meera Iyer 2_dup_1.txt", "Meera Iyer2.txt", "Rohan Mehta.txt", "Meera Iyer_dup_1.zip")) {
            File(processed, p).writeText("keep")
        }
        val src = File(tmp.newFolder("neighbour-in"), "Meera Iyer.txt").also { it.writeText("new") }
        moveToProcessed(src, processed)
        for (p in listOf("Meera Iyer 2_dup_1.txt", "Meera Iyer2.txt", "Rohan Mehta.txt", "Meera Iyer_dup_1.zip")) {
            assertTrue("$p must survive", File(processed, p).exists())
        }
    }

    @Test
    fun aDirectoryOrWrongSuffixIsNeverListed() {
        val dir = tmp.newFolder("lst")
        File(dir, "a.md").writeText("x")
        File(dir, "b.TXT").writeText("x")
        File(dir, "dir.txt").mkdir()
        assertFalse(listInboxFiles(dir).any { it.name in setOf("a.md", "b.TXT", "dir.txt") })
    }
}
