package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ENC guard: message body text must not rest anywhere in the app's own storage after a sync.
 * The state database stores one-way hashes and names, never the text. A failed sync keeps the
 * export file on purpose (so a retry can resume), but still writes no body text to the database.
 *
 * Every body carries a token that appears nowhere else, so a hit can only be a leak.
 */
class NoBodyTextAtRestTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileName = "WhatsApp Chat with Meera Iyer.txt"

    private fun token(i: Int) = "ZQXBODYTOKEN$i"

    private fun chatText(n: Int): String =
        (1..n).joinToString("\n") { "%02d/03/25, 09:00 - Meera Iyer: ${token(it)} hello".format(it + 19) } + "\n"

    private class Setup(val root: File, val inbox: File, val processed: File, val db: String)

    private fun setup(n: Int): Setup {
        val root = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        File(inbox, fileName).writeText(chatText(n))
        return Setup(root, inbox, File(root, "processed"), File(root, "state.db").absolutePath)
    }

    private fun manager(s: Setup, t: MailTransport) = SyncManager(
        repo = newRepo(s.root, "state.db"), transport = t, inboxDir = s.inbox,
        processedDir = s.processed, sleeper = { }, shouldStop = { false }, dryRun = false,
        onProgress = null, exportDeleter = { it.delete() },
    )

    /** Every table and column of the database as text, read through SQL (not raw bytes). */
    private fun dumpAllText(db: String): String {
        val sb = StringBuilder()
        SqliteJdbcStateDb(db).use { d ->
            val tables = d.query("SELECT name FROM sqlite_master WHERE type='table'", emptyList()).map { it["name"].toString() }
            assertTrue("the schema has tables", tables.isNotEmpty())
            for (t in tables) {
                for (row in d.query("SELECT * FROM \"$t\"", emptyList())) {
                    sb.append(t).append(':').append(row.entries.joinToString("|") { "${it.key}=${it.value}" }).append('\n')
                }
            }
        }
        return sb.toString()
    }

    /** Every byte of every file under [root] (minus [skip]) as Latin-1 text, so a raw scan can find a token. */
    private fun rawScan(root: File, skip: File? = null): String =
        root.walkTopDown().filter { it.isFile && (skip == null || !it.startsWith(skip)) }
            .joinToString("\n") { String(it.readBytes(), Charsets.ISO_8859_1) }

    private fun tokensIn(text: String, n: Int) = (1..n).filter { text.contains(token(it)) }

    @Test
    fun aCompletedSyncLeavesNoBodyTextAnywhereUnderTheAppRoot() {
        val s = setup(4)
        val stats = manager(s, CountingTransport()).run()
        assertEquals(1, stats.filesSynced)
        // Sanity: four messages really were processed, so an empty scan is not "nothing ran".
        assertEquals(4, SyncRunSupport.rows(s.db, "SELECT hash FROM message_hashes", listOf("hash")).size)

        assertEquals("every table and column", emptyList<Int>(), tokensIn(dumpAllText(s.db), 4))
        assertEquals("every byte of every file (db, -wal, inbox, processed, unreadable)", emptyList<Int>(), tokensIn(rawScan(s.root), 4))
        assertFalse(File(s.inbox, fileName).exists())
    }

    // NEGATIVE: a failed sync keeps the export by design, yet the database still holds no body text.
    @Test
    fun aFailedSyncKeepsTheExportButWritesNoBodyTextToTheDatabase() {
        val s = setup(5)
        val stats = manager(s, CountingTransport(failAt = 3)).run()
        assertEquals(1, stats.filesFailed)
        assertTrue("the export is kept for a retry", File(s.inbox, fileName).exists())
        assertEquals(2, SyncRunSupport.rows(s.db, "SELECT hash FROM message_hashes", listOf("hash")).size)

        assertEquals("database tables", emptyList<Int>(), tokensIn(dumpAllText(s.db), 5))
        assertEquals("database files incl. -wal", emptyList<Int>(), tokensIn(rawScan(s.root, skip = s.inbox), 5))
        // The scan can see the text where it does rest: the kept export.
        assertEquals((1..5).toList(), tokensIn(rawScan(s.inbox), 5))
    }
}
