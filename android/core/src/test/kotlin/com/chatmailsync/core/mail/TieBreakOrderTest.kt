package com.chatmailsync.core.mail

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BUG-10: rows that tie on the sort key (two runs that start in the same second, two senders
 * with the same count, two chats with the same display name) come back in a fixed order, not
 * whichever order SQLite happens to pick.
 */
class TieBreakOrderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = LocalDateTime.now().withNano(0).minusMinutes(5)

    private fun stamp(t: LocalDateTime) = t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

    private var dbPath = ""

    private fun raw(sql: String) = SqliteJdbcStateDb(dbPath).use { it.exec(sql, emptyList()) }

    private fun newRepo(): StateRepository {
        val path = File(tmp.newFolder(), "state.db").absolutePath
        dbPath = path
        val repo = StateRepository(clock = { stamp(now) }) { SqliteJdbcStateDb(path) }
        repo.initDb()
        repo.upsertChat("meera_iyer", "Meera Iyer", "a.txt")
        repo.upsertChat("rohan_mehta", "Rohan Mehta", "b.txt")
        return repo
    }

    /** Starts a run and finishes it, all within the current (frozen) second. */
    private fun run(repo: StateRepository, chat: String, fail: Boolean = false): Long {
        val id = repo.startSyncRun(chat)
        if (fail) repo.failSyncRun(id, "boom") else repo.completeSyncRun(id, null, null, 1, 1, 0, 0)
        return id
    }

    @Test
    fun runsStartedInTheSameSecondListTheHigherRunIdFirstAndItIsTheLastRun() {
        val repo = newRepo()
        val a = run(repo, "meera_iyer")
        val b = run(repo, "rohan_mehta", fail = true)
        val c = run(repo, "meera_iyer")
        assertEquals(listOf(c, b, a), repo.getRecentRuns().map { it.runId })
        val summary = repo.summarizeRecentRuns()
        assertEquals(c, summary.lastRunId)
        assertEquals("Meera Iyer", summary.lastDisplayName)
    }

    @Test
    fun theOrderNeverChangesAcrossManyReadsOrInsertionOrders() {
        val repo = newRepo()
        val ids = (1..6).map { run(repo, if (it % 2 == 0) "meera_iyer" else "rohan_mehta") }
        val want = ids.reversed()
        repeat(50) { assertEquals(want, repo.getRecentRuns().map { it.runId }) }
        // Rows inserted in the opposite physical order: run_ids 11..13 written first, 10 last.
        val other = newRepo()
        for (id in listOf(13L, 12L, 11L, 10L)) {
            raw(
                "INSERT INTO sync_runs (run_id, chat_id, status, trigger, started_at) VALUES ($id, 'meera_iyer', 'complete', 'manual', '${stamp(now)}')",
            )
        }
        repeat(50) { assertEquals(listOf(13L, 12L, 11L, 10L), other.getRecentRuns().map { it.runId }) }
    }

    @Test
    fun aLaterStartTimeStillBeatsAHigherRunId() {
        val repo = newRepo()
        val low = run(repo, "meera_iyer")
        val high = run(repo, "rohan_mehta")
        // Only a backwards clock (or a hand-edited row) gives a LOWER run_id a LATER start.
        now = now.plusSeconds(30)
        raw("UPDATE sync_runs SET started_at = '${stamp(now)}' WHERE run_id = $low")
        assertEquals(listOf(low, high), repo.getRecentRuns().map { it.runId })
        assertEquals(low, repo.summarizeRecentRuns().lastRunId)
    }

    @Test
    fun sendersWithEqualCountsListBySenderAscendingAndAcrossChatsByChatIdThenSender() {
        val repo = newRepo()
        repo.recordChatSenders("rohan_mehta", mapOf("Zed Rao" to 2, "Asha Rao" to 2))
        repo.recordChatSenders("meera_iyer", mapOf("Rohan Mehta" to 2, "Meera Iyer" to 2))
        repeat(50) {
            assertEquals(
                listOf("Asha Rao", "Zed Rao"),
                repo.listChatSenders("rohan_mehta").map { it.sender },
            )
            assertEquals(
                listOf("meera_iyer/Meera Iyer", "meera_iyer/Rohan Mehta", "rohan_mehta/Asha Rao", "rohan_mehta/Zed Rao"),
                repo.listChatSenders().map { "${it.chatId}/${it.sender}" },
            )
        }
    }

    @Test
    fun aHigherCountAlwaysComesFirstWhateverTheName() {
        val repo = newRepo()
        repo.recordChatSenders("meera_iyer", mapOf("Zed Rao" to 5, "Asha Rao" to 4, "Meera Iyer" to 4))
        repo.recordChatSenders("rohan_mehta", mapOf("Asha Rao" to 1))
        assertEquals(listOf("Zed Rao", "Asha Rao", "Meera Iyer"), repo.listChatSenders("meera_iyer").map { it.sender })
        assertEquals(
            listOf("meera_iyer/Zed Rao", "meera_iyer/Asha Rao", "meera_iyer/Meera Iyer", "rohan_mehta/Asha Rao"),
            repo.listChatSenders().map { "${it.chatId}/${it.sender}" },
        )
    }

    @Test
    fun chatsWithTheSameDisplayNameListByChatId() {
        val root = tmp.newFolder()
        val path = File(root, "state.db").absolutePath
        val repo = StateRepository(clock = { stamp(now) }) { SqliteJdbcStateDb(path) }
        repo.initDb()
        repo.upsertChat("b_chat", "Meera Iyer", "b.txt")
        repo.upsertChat("a_chat", "Meera Iyer", "a.txt")
        repeat(50) {
            assertEquals(listOf("a_chat", "b_chat"), repo.listChats().map { it.chatId })
            assertEquals(listOf("a_chat", "b_chat"), repo.getSyncSummary().map { it.chatId })
        }
    }
}
