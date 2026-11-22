package com.chatmailsync.core.mail

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SEC-18: runs the REAL [exportBundle] over a large synthetic database and watches every write that
 * reaches the zip through the onEntryWrite observer. [Sec13HardeningTest] checks the streamFileTo helper
 * and [ExportStreamsGuardTest] reads the source; this is the one that exercises the export call site.
 * A whole-file read in the export shows up here as one huge write. Synthetic, placeholder data only.
 */
class ExportMemoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val openDb: OpenDb = { f -> SqliteJdbcStateDb(f.absolutePath) }

    /** A root whose sync_state.db is several MB: [rows] fake message hashes for one demo chat. */
    private fun bigRoot(rows: Int): File {
        val root = tmp.newFolder()
        val db = bundleDbPath(root)
        db.parentFile.mkdirs()
        val repo = StateRepository(clock = { "2026-10-01T10:00:00" }) { openDb(db) }
        repo.initDb()
        repo.upsertChat("chat-meera", "Meera Iyer", "Meera Iyer.txt")
        val run = repo.startSyncRun("chat-meera")
        for (batch in 0 until rows / 10_000) {
            repo.insertMessageHashes(
                (0 until 10_000).map { i ->
                    val n = batch * 10_000 + i
                    StateRepository.HashEntry(
                        "%064x".format(n.toLong() * 7919L), "chat-meera", "2026-09-30T09:%02d:00".format(n % 60), run,
                    )
                },
            )
        }
        return root
    }

    private fun export(root: File, dest: File, observer: ((String, Int) -> Unit)?) = exportBundle(
        root, dest, openDb, mapOf("chunk_size" to "week"),
        appVersion = "placeholder", bundleId = "00000000-0000-4000-8000-0000000a0a0a",
        createdAt = "2026-10-01T10:00:00", zipTimeMillis = 1_790_000_000_000L,
        onEntryWrite = observer,
    )

    @Test
    fun theRealExportStreamsALargeDatabaseInBoundedWrites() {
        val root = bigRoot(rows = 60_000)
        val dbSize = bundleDbPath(root).length()
        assertTrue("fixture database is only $dbSize bytes", dbSize >= 4L * 1024 * 1024)

        val sizes = ArrayList<Int>()
        val seen = LinkedHashSet<String>()
        val dest = File(tmp.newFolder(), "big.cmsbackup")
        export(root, dest) { entry, len ->
            seen.add(entry)
            if (entry == BUNDLE_DB_NAME) sizes.add(len)
        }

        // The database entry arrived in many small writes, none larger than one chunk.
        assertTrue("largest database write ${sizes.max()} bytes", sizes.max() <= 64 * 1024)
        assertTrue("only ${sizes.size} database writes", sizes.size >= 50)
        // And nothing held the file: the bytes that did arrive add up to the whole snapshot.
        assertTrue("wrote ${sizes.sum()} of a $dbSize-byte database", sizes.sum().toLong() >= dbSize * 9 / 10)
        assertEquals(listOf(MANIFEST_NAME, SETTINGS_NAME, BUNDLE_DB_NAME), seen.toList())

        // The export is still a valid backup with the expected entries.
        ZipFile(dest).use { zf ->
            assertEquals(
                setOf(MANIFEST_NAME, SETTINGS_NAME, BUNDLE_DB_NAME),
                zf.entries().asSequence().map { it.name }.toSet(),
            )
        }
        assertEquals("00000000-0000-4000-8000-0000000a0a0a", readManifest(dest)["bundle_id"])
    }

    // NEGATIVE: watching the export does not change what it writes.
    @Test
    fun theObserverDoesNotChangeTheBytesWritten() {
        val root = bigRoot(rows = 10_000)
        val plain = File(tmp.newFolder(), "plain.cmsbackup")
        val watched = File(tmp.newFolder(), "watched.cmsbackup")
        export(root, plain, null)
        export(root, watched) { _, _ -> }
        assertTrue("fixture is empty", plain.length() > 0)
        // A VACUUM INTO snapshot of the same DB is deterministic, so the two zips match byte for byte.
        assertArrayEquals(plain.readBytes(), watched.readBytes())
    }
}
