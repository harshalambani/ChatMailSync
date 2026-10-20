package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PRIV-03: a completed sync deletes the chat export instead of keeping a copy in processed/.
 * Deliberate difference from Python (frozen, D38), which moves it to processed/.
 *
 * Every test works in JUnit temp folders only. The chats and senders are placeholders.
 */
class DeleteAfterSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fileName = "WhatsApp Chat with Meera Iyer.txt"

    private fun chatText(vararg days: Int): String =
        days.joinToString("\n") { "%02d/03/25, 09:00 - Meera Iyer: message $it".format(it) } + "\n"

    private class Setup(val root: File, val inbox: File, val processed: File, val db: String)

    private fun setup(text: String? = null): Setup {
        val root = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        if (text != null) File(inbox, fileName).writeText(text)
        return Setup(root, inbox, File(root, "processed"), File(root, "state.db").absolutePath)
    }

    private fun manager(
        s: Setup,
        transport: MailTransport?,
        stop: () -> Boolean = { false },
        dryRun: Boolean = false,
        onProgress: ((Map<String, Any?>) -> Unit)? = null,
        deleter: (File) -> Unit = { Files.delete(it.toPath()) },
    ) = SyncManager(
        repo = newRepo(s.root, "state.db"), transport = transport, inboxDir = s.inbox,
        processedDir = s.processed, sleeper = { }, shouldStop = stop, dryRun = dryRun,
        onProgress = onProgress, exportDeleter = deleter,
    )

    /** Every regular file under [root] except the state store's own files. */
    private fun strayFiles(root: File): List<String> =
        root.walkTopDown().filter { it.isFile && !it.name.startsWith("state.db") }
            .map { it.relativeTo(root).path }.toList()

    private fun hashCount(db: String) = SyncRunSupport.rows(db, "SELECT hash FROM message_hashes", listOf("hash")).size

    private fun distinctHashCount(db: String) =
        SyncRunSupport.rows(db, "SELECT DISTINCT hash FROM message_hashes", listOf("hash")).size

    private fun statuses(db: String) =
        SyncRunSupport.rows(db, "SELECT status FROM sync_runs ORDER BY run_id", listOf("status")).map { it[0] }

    // -- Completed syncs delete the export -------------------------------------------------------

    @Test
    fun aCompletedSyncDeletesTheExportAndLeavesNoCopyAnywhere() {
        val s = setup(chatText(20, 21, 22))
        val t = CountingTransport()
        val stats = manager(s, t).run()
        assertEquals(1, stats.filesSynced)
        assertEquals(3, t.insertCalls)
        assertEquals(listOf("complete"), statuses(s.db))
        assertFalse(File(s.inbox, fileName).exists())
        assertTrue("processed/ holds nothing", s.processed.listFiles().orEmpty().isEmpty())
        assertEquals("no copy under the data dir", emptyList<String>(), strayFiles(s.root))
    }

    @Test
    fun aCompletedZipSyncDeletesTheZipWithItsMediaAndExtractsNothingElsewhere() {
        val s = setup()
        val zip = File(s.inbox, "WhatsApp Chat with Meera Iyer.zip")
        SyncRunSupport.writeZip(
            zip,
            mapOf(
                "_chat.txt" to "01/03/25, 09:00 - Meera Iyer: <attached: IMG-1.jpg>\n02/03/25, 09:00 - Rohan Mehta: hi\n",
                "IMG-1.jpg" to "not really an image",
            ),
        )
        val stats = manager(s, CountingTransport()).run()
        assertEquals(1, stats.filesSynced)
        assertFalse(zip.exists())
        assertEquals("no media or text copy remains", emptyList<String>(), strayFiles(s.root))
        assertEquals("nothing is extracted outside the data dir", emptyList<String>(), strayFiles(tmp.root).filter { !it.startsWith(s.root.name) })
    }

    @Test
    fun anExportWithNothingNewIsDeletedToo() {
        val s = setup(chatText(20, 21))
        manager(s, CountingTransport()).run()
        File(s.inbox, fileName).writeText(chatText(20, 21))
        val t = CountingTransport()
        val stats = manager(s, t).run()
        assertEquals(0, t.insertCalls)
        assertEquals(1, stats.filesSkipped)
        assertFalse(File(s.inbox, fileName).exists())
        assertTrue(s.processed.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aCompletedRecoveryRunDeletesTheExportToo() {
        val s = setup(chatText(20, 21, 22, 23))
        try {
            manager(s, CountingTransport(crashAt = 2)).run()
        } catch (_: ProcessDeath) {
            // expected: leaves a pending run and the file in inbox/
        }
        assertTrue("the crashed run keeps its file", File(s.inbox, fileName).exists())
        assertEquals(listOf("pending"), statuses(s.db))

        val t = CountingTransport()
        val stats = manager(s, t).run()
        assertEquals(1, stats.chatsRecovered)
        assertEquals(3, t.insertCalls)
        assertEquals(listOf("complete"), statuses(s.db))
        assertFalse(File(s.inbox, fileName).exists())
        assertEquals(emptyList<String>(), strayFiles(s.root))
        assertEquals("every message exactly once", 4, distinctHashCount(s.db))
        assertEquals(4, hashCount(s.db))
    }

    // -- Negative: nothing a pending, failed or stopped run needs is ever deleted ---------------

    @Test
    fun aFailedPushKeepsTheFileAndALaterRunDeliversTheRestOnce() {
        val s = setup(chatText(20, 21, 22, 23, 24))
        val stats = manager(s, CountingTransport(failAt = 3)).run()
        assertEquals(1, stats.filesFailed)
        assertTrue("the failed run keeps its file", File(s.inbox, fileName).exists())
        assertEquals(listOf("failed"), statuses(s.db))
        assertEquals(2, hashCount(s.db))

        val t = CountingTransport()
        manager(s, t).run()
        assertEquals("only the missing three", 3, t.insertCalls)
        assertEquals(5, hashCount(s.db))
        assertEquals("no duplicate", 5, distinctHashCount(s.db))
        assertFalse(File(s.inbox, fileName).exists())
    }

    @Test
    fun aStoppedRunKeepsTheFileAndALaterRunDeliversTheRestOnce() {
        val s = setup(chatText(20, 21, 22, 23, 24))
        var stop = false
        manager(
            s, CountingTransport(), stop = { stop },
            onProgress = { if (it["type"] == "chunk" && it["chunk"] == 2) stop = true },
        ).run()
        assertTrue("a stopped run keeps its file", File(s.inbox, fileName).exists())
        assertEquals(listOf("failed"), statuses(s.db))
        assertEquals(2, hashCount(s.db))

        val t = CountingTransport()
        manager(s, t).run()
        assertEquals(3, t.insertCalls)
        assertEquals("no duplicate", 5, distinctHashCount(s.db))
        assertFalse(File(s.inbox, fileName).exists())
    }

    @Test
    fun aCrashBeforeCompletionKeepsTheFile() {
        val s = setup(chatText(20, 21, 22, 23))
        try {
            manager(s, CountingTransport(crashAt = 3)).run()
        } catch (_: ProcessDeath) {
            // expected
        }
        assertTrue(File(s.inbox, fileName).exists())
        assertEquals(listOf("pending"), statuses(s.db))
        assertTrue(s.processed.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aStoppedRecoveryRunKeepsTheFile() {
        val s = setup(chatText(20, 21, 22, 23))
        try {
            manager(s, CountingTransport(crashAt = 2)).run()
        } catch (_: ProcessDeath) {
            // expected
        }
        val before = statuses(s.db)
        manager(s, CountingTransport(), stop = { true }).run()
        assertEquals("still pending", before, statuses(s.db))
        assertTrue("a stopped recovery keeps the file", File(s.inbox, fileName).exists())
    }

    @Test
    fun aDryRunDeletesAndSweepsNothing() {
        val s = setup(chatText(20, 21, 22))
        s.processed.mkdirs()
        val kept = File(s.processed, "old copy.txt").also { it.writeText("old") }
        val t = CountingTransport()
        manager(s, t, dryRun = true).run()
        assertEquals(0, t.insertCalls)
        assertTrue("the export stays", File(s.inbox, fileName).exists())
        assertTrue("a dry run does not sweep processed/", kept.exists())
        assertEquals(emptyList<String>(), statuses(s.db))
    }

    // -- A delete failure never un-completes a delivered run -------------------------------------

    @Test
    fun aDeleteFailureLeavesTheRunCompleteAndTheFileInTheInboxAndSendsNothingTwice() {
        val s = setup(chatText(20, 21, 22))
        val events = mutableListOf<Map<String, Any?>>()
        val stats = manager(
            s, CountingTransport(), onProgress = { events.add(it) },
            deleter = { throw IOException("cannot delete ${it.name}") },
        ).run()
        assertEquals(1, stats.filesSynced)
        assertEquals(0, stats.filesFailed)
        assertTrue("no error is added to the run", stats.errors.isEmpty())
        assertEquals(listOf("complete"), statuses(s.db))
        assertTrue("the file stays in inbox/", File(s.inbox, fileName).exists())
        val notes = events.filter { it["type"] == "export_not_deleted" }
        assertEquals("one note, no names and no error text", listOf(mapOf<String, Any?>("type" to "export_not_deleted")), notes)

        // The next run re-parses the file, the hash check sends nothing, and the delete works.
        val t = CountingTransport()
        val again = manager(s, t).run()
        assertEquals("nothing sent twice", 0, t.insertCalls)
        assertEquals(3, distinctHashCount(s.db))
        assertEquals(1, again.filesSkipped)
        assertFalse(File(s.inbox, fileName).exists())
    }

    @Test
    fun aDeleteFailureDuringRecoveryStillCompletesTheRun() {
        val s = setup(chatText(20, 21, 22, 23))
        try {
            manager(s, CountingTransport(crashAt = 2)).run()
        } catch (_: ProcessDeath) {
            // expected
        }
        val t = CountingTransport()
        val stats = manager(s, t, deleter = { throw IOException("locked") }).run()
        assertEquals(1, stats.chatsRecovered)
        assertTrue("no run left pending", statuses(s.db).all { it == "complete" })
        assertTrue(File(s.inbox, fileName).exists())
        val t2 = CountingTransport()
        manager(s, t2).run()
        assertEquals(0, t2.insertCalls)
        assertEquals(4, distinctHashCount(s.db))
    }

    // -- deleteCompletedExport only ever deletes a plain file directly inside inbox/ -------------

    @Test
    fun aPathOutsideTheInboxIsNeverDeleted() {
        val s = setup()
        val outside = File(s.root, "outside.txt").also { it.writeText("mine") }
        assertFalse(deleteCompletedExport(File(s.inbox, "../outside.txt"), s.inbox))
        assertFalse(deleteCompletedExport(outside, s.inbox))
        File(s.inbox, "sub").mkdirs()
        val nested = File(s.inbox, "sub/n.txt").also { it.writeText("nested") }
        assertFalse(deleteCompletedExport(nested, s.inbox))
        assertFalse(deleteCompletedExport(File(s.inbox, "sub"), s.inbox))
        assertTrue(outside.exists() && nested.exists() && File(s.inbox, "sub").exists())
    }

    @Test
    fun aSymlinkInTheInboxIsNotFollowedOrDeleted() {
        val s = setup()
        val target = File(s.root, "target.txt").also { it.writeText("precious") }
        val link = File(s.inbox, "link.txt")
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (e: Exception) {
            assumeTrue("symbolic links not available here", false)
        }
        assertFalse(deleteCompletedExport(link, s.inbox))
        assertTrue(target.exists())
        assertEquals("precious", target.readText())
    }

    // -- The sweep of kept copies ---------------------------------------------------------------

    @Test
    fun theSweepRemovesKeptCopiesAndNeverTouchesTheInbox() {
        val s = setup()
        File(s.inbox, "WhatsApp Chat with Rohan Mehta.txt").writeText("waiting to sync")
        s.processed.mkdirs()
        File(s.processed, "WhatsApp Chat with Meera Iyer.txt").writeText("kept")
        File(s.processed, "WhatsApp Chat with Meera Iyer_dup_20250101.txt").writeText("kept")
        File(s.processed, "WhatsApp Chat with Asha Rao.zip").writeText("kept")
        val sub = File(s.processed, "unexpected").also { it.mkdirs() }
        val inner = File(sub, "inner.txt").also { it.writeText("not ours to touch") }

        manager(s, null)

        assertEquals("only the unexpected folder is left", listOf("unexpected"), s.processed.list().orEmpty().toList())
        assertTrue("a folder is not entered", inner.exists())
        assertEquals("waiting to sync", File(s.inbox, "WhatsApp Chat with Rohan Mehta.txt").readText())
        assertEquals(1, s.inbox.list().orEmpty().size)
    }

    @Test
    fun theSweepRemovesASymlinkAsALinkOnlyAndLeavesItsTarget() {
        val s = setup()
        s.processed.mkdirs()
        val target = File(s.root, "precious.txt").also { it.writeText("precious") }
        val link = File(s.processed, "link.txt")
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (e: Exception) {
            assumeTrue("symbolic links not available here", false)
        }
        assertEquals(1, sweepKeptCopies(s.processed, s.inbox))
        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("precious", target.readText())
    }

    @Test
    fun theSweepDoesNothingWhenProcessedIsALinkOrIsTheInbox() {
        val s = setup(chatText(20))
        // processed/ and inbox/ are the same folder: nothing may be removed.
        assertEquals(0, sweepKeptCopies(s.inbox, s.inbox))
        assertTrue(File(s.inbox, fileName).exists())

        // processed/ is a link to a folder: its target's files are not ours.
        val elsewhere = tmp.newFolder().also { File(it, "keep.txt").writeText("keep") }
        val linkDir = File(s.root, "processed")
        try {
            Files.createSymbolicLink(linkDir.toPath(), elsewhere.toPath())
        } catch (e: Exception) {
            assumeTrue("symbolic links not available here", false)
        }
        assertEquals(0, sweepKeptCopies(linkDir, s.inbox))
        assertTrue(File(elsewhere, "keep.txt").exists())
    }

    @Test
    fun aSweepThatCannotRunNeverFailsStartup() {
        val s = setup(chatText(20))
        // processed is a plain file, not a folder: mkdirs and the sweep both have nothing to do.
        s.processed.writeText("a file where the folder should be")
        val t = CountingTransport()
        val stats = manager(s, t).run()
        assertEquals(1, stats.filesSynced)
        assertEquals(0, sweepKeptCopies(File(s.root, "missing"), s.inbox))
    }

    // -- Reset chat no longer moves anything ----------------------------------------------------

    private fun api(root: File) = CoreApi(root, { SqliteJdbcStateDb(it.absolutePath) })

    private fun syncedApi(root: File): CoreApi {
        val a = api(root)
        a.paths.inboxDir.mkdirs()
        File(a.paths.inboxDir, fileName).writeText(chatText(20, 21))
        a.sync(transport = CountingTransport())
        return a
    }

    private fun setSourceFilename(a: CoreApi, value: String) {
        SqliteJdbcStateDb(a.paths.stateDbPath.absolutePath).use {
            it.exec("UPDATE chats SET source_filename = '${value.replace("'", "''")}' WHERE chat_id = 'meera_iyer'", emptyList())
        }
    }

    @Test
    fun resetMovesNothingWhateverTheSourceFilenameIs() {
        val outsideFolder = tmp.newFolder("outside")
        val outside = File(outsideFolder, "x").also { it.writeText("outside") }
        val names = listOf("../x", outside.absolutePath, fileName, "sub/..//x")
        for ((i, name) in names.withIndex()) {
            val a = syncedApi(tmp.newFolder("reset$i"))
            // A kept copy that older versions left behind, under the normal name and under the odd one.
            a.paths.processedDir.mkdirs()
            val kept = File(a.paths.processedDir, fileName).also { it.writeText("kept copy") }
            File(a.paths.inboxDir.parentFile, "x").writeText("neighbour of inbox")
            setSourceFilename(a, name)

            val unconfirmed = a.reset("Meera Iyer")
            assertEquals(true, unconfirmed["needs_confirmation"])
            assertEquals(false, unconfirmed["file_restored"])

            val r = a.reset("Meera Iyer", true)
            assertEquals(name, true, r["ok"])
            assertEquals(name, false, r["file_restored"])
            assertNull(r["error"])
            assertTrue("no file came into the inbox for $name", a.paths.inboxDir.listFiles().orEmpty().isEmpty())
            assertEquals("kept copy", kept.readText())
            assertEquals("outside", outside.readText())
            assertEquals("neighbour of inbox", File(a.paths.inboxDir.parentFile, "x").readText())
        }
    }

    @Test
    fun resetLeavesAFileAlreadyWaitingInTheInboxAlone() {
        val a = syncedApi(tmp.newFolder("waiting"))
        File(a.paths.inboxDir, fileName).writeText("a fresh export already waiting")
        a.paths.processedDir.mkdirs()
        File(a.paths.processedDir, fileName).writeText("old kept copy")
        val r = a.reset("Meera Iyer", true)
        assertEquals(true, r["ok"])
        assertEquals(false, r["file_restored"])
        assertEquals("a fresh export already waiting", File(a.paths.inboxDir, fileName).readText())
        assertEquals("old kept copy", File(a.paths.processedDir, fileName).readText())
    }
}
