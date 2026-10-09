package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * BUG-09: the restore path against files that anyone can hand-edit. Placeholder data only.
 * Four things: the database member is capped, a run's messages_cutoff is carried across, a
 * database holding the wrong type where a number belongs is refused with a sentence, and a
 * manifest or settings that is JSON but not an object is refused. Every refusal leaves the
 * existing database byte-identical and no temp files behind.
 */
class MigrationRestoreHardeningTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val openDb: OpenDb = { f -> SqliteJdbcStateDb(f.absolutePath) }
    private val unreadable = "That backup's history could not be read."

    private fun repo(root: File) = StateRepository({ "2026-10-01T10:00:00" }) { openDb(bundleDbPath(root)) }

    private fun seededRoot(cutoff: Int = 0): File {
        val root = tmp.newFolder()
        bundleDbPath(root).parentFile.mkdirs()
        val r = repo(root)
        r.initDb()
        r.upsertChat("chat-meera", "Meera Iyer", "Meera Iyer.txt")
        val run = r.startSyncRun("chat-meera")
        r.completeSyncRun(run, "2026-09-30T09:00:00", "h-last", 2, 2, 0, cutoff)
        r.insertMessageHashes(
            listOf(StateRepository.HashEntry("h1", "chat-meera", "2026-09-30T09:01:00", run)),
        )
        ensureBundleLedger(bundleDbPath(root), openDb)
        return root
    }

    private fun sha(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun manifest(id: String) =
        """{"schema_version":1,"bundle_id":"$id","created_at":"2026-10-01T10:00:00","app_version":"t","counts":{}}"""
            .toByteArray()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val f = File(tmp.newFolder(), "x.cmsbackup")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, b) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(b)
                z.closeEntry()
            }
        }
        return f
    }

    /** The state database of [root] as it would travel in a bundle. */
    private fun dbBytes(root: File): ByteArray {
        val snap = File(tmp.newFolder(), "snap.db")
        snapshotBundleDb(bundleDbPath(root), snap, openDb)
        return snap.readBytes()
    }

    /**
     * A hand-built incoming database with LOOSE tables (no NOT NULL, no column affinity), so a
     * text, NULL or real value can sit where a number belongs. [runId], [hashRunId] and
     * [cutoff] are SQL literals.
     */
    private fun looseDb(runId: String = "1", hashRunId: String = "1", cutoff: String = "0"): ByteArray {
        val f = File(tmp.newFolder(), "loose.db")
        openDb(f).use { db ->
            db.exec(
                "CREATE TABLE chats (chat_id, display_name, gmail_thread_id, gmail_label_id, " +
                    "anchor_message_id, source_filename, created_at, updated_at)",
            )
            db.exec(
                "CREATE TABLE sync_runs (run_id, chat_id, status, trigger, last_synced_ts, last_synced_hash, " +
                    "messages_parsed, messages_synced, messages_skipped, messages_cutoff, error_message, " +
                    "started_at, completed_at)",
            )
            db.exec("CREATE TABLE message_hashes (hash, chat_id, message_ts, run_id)")
            db.exec(
                "INSERT INTO chats VALUES ('chat-rohan','Rohan Mehta',NULL,NULL,NULL,'Rohan Mehta.txt'," +
                    "'2026-09-01T00:00:00','2026-09-01T00:00:00')",
            )
            db.exec(
                "INSERT INTO sync_runs VALUES ($runId,'chat-rohan','complete','manual',NULL,NULL,1,1,0,$cutoff," +
                    "NULL,'2026-09-01T00:00:00','2026-09-01T00:01:00')",
            )
            db.exec("INSERT INTO message_hashes VALUES ('hx','chat-rohan','2026-09-01T00:00:30',$hashRunId)")
        }
        return f.readBytes()
    }

    private fun noSideFiles(root: File) = assertEquals(
        emptyList<String>(),
        root.walkTopDown().filter { it.isFile }.map { it.name }
            .filter { it.endsWith("-wal") || it.endsWith("-shm") }.toList(),
    )

    private fun assertRefused(bundle: File, root: File, sentence: String? = null) {
        val tempDir = tmp.newFolder()
        val before = sha(bundleDbPath(root))
        val r = importBundle(root, bundle, openDb, tempDir = tempDir)
        assertFalse(r.ok)
        if (sentence != null) assertEquals(sentence, r.error)
        assertTrue(r.error!!.endsWith("."))
        assertEquals(before, sha(bundleDbPath(root)))
        assertEquals(emptyList<String>(), tempDir.list()!!.toList())
        noSideFiles(root)
    }

    // ------------------------------------------------------- (2) the database member cap

    @Test
    fun `a bundle exactly at the cap still imports and one byte over is refused`() {
        val db = dbBytes(seededRoot())
        val bundle = zipOf("manifest.json" to manifest("cap-1"), "sync_state.db" to db)

        val fresh = tmp.newFolder()
        val ok = importBundle(fresh, bundle, openDb, maxDbBytes = db.size.toLong())
        assertTrue(ok.error, ok.ok)
        assertEquals(1, ok.hashesAdded)

        val root = seededRoot()
        val before = sha(bundleDbPath(root))
        val r = importBundle(
            root, zipOf("manifest.json" to manifest("cap-3"), "sync_state.db" to db), openDb,
            maxDbBytes = db.size - 1L,
        )
        assertFalse(r.ok)
        assertEquals(
            "That backup's sync_state.db would expand to ${db.size} bytes, past the ${db.size - 1L}-byte safety limit.",
            r.error,
        )
        assertEquals(before, sha(bundleDbPath(root)))
    }

    @Test
    fun `the default cap is 1 GiB`() {
        assertEquals(1_073_741_824L, MAX_BUNDLE_DB_BYTES)
    }

    @Test
    fun `a database member that declares more than the cap is refused before it is read`() {
        val root = seededRoot()
        val db = dbBytes(root)
        val tempDir = tmp.newFolder()
        val before = sha(bundleDbPath(root))
        val r = importBundle(
            root, zipOf("manifest.json" to manifest("cap-4"), "sync_state.db" to db), openDb,
            tempDir = tempDir, maxDbBytes = 100,
        )
        assertFalse(r.ok)
        assertTrue(r.error!!, r.error!!.contains("safety limit"))
        assertEquals(before, sha(bundleDbPath(root)))
        assertEquals(emptyList<String>(), tempDir.list()!!.toList())
        noSideFiles(root)
    }

    @Test
    fun `a database member whose header lies about its size is refused as soon as the stream passes the cap`() {
        val root = seededRoot()
        val db = ByteArray(200_000) // compresses to almost nothing, expands to 200 000
        val z = zipOf("manifest.json" to manifest("cap-5"), "sync_state.db" to db)
        // Patch the central-directory uncompressed size of sync_state.db down to 10 bytes.
        val bytes = z.readBytes()
        var patched = false
        var i = 0
        while (i <= bytes.size - 46) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 0x01.toByte() && bytes[i + 3] == 0x02.toByte()
            ) {
                val nameLen = ByteBuffer.wrap(bytes, i + 28, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                if (String(bytes, i + 46, nameLen) == "sync_state.db") {
                    ByteBuffer.wrap(bytes, i + 24, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(10)
                    patched = true
                }
            }
            i++
        }
        assertTrue(patched)
        z.writeBytes(bytes)
        val tempDir = tmp.newFolder()
        val before = sha(bundleDbPath(root))
        val r = importBundle(root, z, openDb, tempDir = tempDir, maxDbBytes = 1_000)
        assertFalse(r.ok)
        assertEquals(before, sha(bundleDbPath(root)))
        assertEquals(emptyList<String>(), tempDir.list()!!.toList())
        assertFalse(r.error!!.contains(root.path))
        noSideFiles(root)
    }

    // ------------------------------------------------------- (3) messages_cutoff

    @Test
    fun `restored runs keep their messages_cutoff and an existing local run keeps its own`() {
        val src = seededRoot(cutoff = 7)
        val bundle = File(tmp.newFolder(), "a.cmsbackup")
        exportBundle(src, bundle, openDb, createdAt = "2026-10-01T10:00:00")

        // Empty install: the run is appended with its cutoff.
        val empty = tmp.newFolder()
        val r1 = importBundle(empty, bundle, openDb)
        assertTrue(r1.error, r1.ok)
        assertEquals(1, r1.runsAdded)
        assertEquals(listOf(7L), cutoffs(empty))

        // The same run already here, with a different local cutoff: untouched.
        val local = seededRoot(cutoff = 3)
        val localRun = openDb(bundleDbPath(local)).use { it.query("SELECT started_at, completed_at FROM sync_runs").single() }
        openDb(bundleDbPath(src)).use {
            it.exec("UPDATE sync_runs SET started_at = ?, completed_at = ?", listOf(localRun["started_at"], localRun["completed_at"]))
        }
        val bundle2 = File(tmp.newFolder(), "b.cmsbackup")
        exportBundle(src, bundle2, openDb, bundleId = "b-2", createdAt = "2026-10-01T10:00:00")
        val r2 = importBundle(local, bundle2, openDb)
        assertTrue(r2.error, r2.ok)
        assertEquals(0, r2.runsAdded)
        assertEquals(listOf(3L), cutoffs(local))
    }

    @Test
    fun `an older bundle without the column restores with cutoff 0`() {
        val f = File(tmp.newFolder(), "old.db")
        openDb(f).use { db ->
            db.exec(
                "CREATE TABLE chats (chat_id TEXT PRIMARY KEY, display_name TEXT NOT NULL, gmail_thread_id TEXT, " +
                    "gmail_label_id TEXT, anchor_message_id TEXT, source_filename TEXT NOT NULL, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
            )
            db.exec(
                "CREATE TABLE sync_runs (run_id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id TEXT NOT NULL, " +
                    "status TEXT NOT NULL, trigger TEXT NOT NULL DEFAULT 'manual', last_synced_ts TEXT, " +
                    "last_synced_hash TEXT, messages_parsed INTEGER NOT NULL DEFAULT 0, " +
                    "messages_synced INTEGER NOT NULL DEFAULT 0, messages_skipped INTEGER NOT NULL DEFAULT 0, " +
                    "error_message TEXT, started_at TEXT NOT NULL, completed_at TEXT)",
            )
            db.exec(
                "INSERT INTO chats VALUES ('chat-priya','Priya Nair',NULL,NULL,NULL,'p.txt'," +
                    "'2026-09-01T00:00:00','2026-09-01T00:00:00')",
            )
            db.exec("INSERT INTO sync_runs (chat_id,status,started_at) VALUES ('chat-priya','failed','2026-09-01T00:00:00')")
        }
        val root = tmp.newFolder()
        val r = importBundle(root, zipOf("manifest.json" to manifest("old-1"), "sync_state.db" to f.readBytes()), openDb)
        assertTrue(r.error, r.ok)
        assertEquals(1, r.runsAdded)
        assertEquals(listOf(0L), cutoffs(root))
    }

    private fun cutoffs(root: File): List<Long> =
        openDb(bundleDbPath(root)).use { db ->
            db.query("SELECT messages_cutoff AS c FROM sync_runs ORDER BY run_id").map { (it["c"] as Number).toLong() }
        }

    // ------------------------------------------------------- (4) wrong types in the database

    @Test
    fun `wrong types where a number belongs are refused with a sentence and the merge rolls back`() {
        val cases = mapOf(
            "run_id text" to looseDb(runId = "'abc'", hashRunId = "'abc'"),
            "run_id NULL" to looseDb(runId = "NULL", hashRunId = "NULL"),
            "run_id real" to looseDb(runId = "1.5", hashRunId = "1.5"),
            "hash run_id text" to looseDb(hashRunId = "'abc'"),
            "hash run_id NULL" to looseDb(hashRunId = "NULL"),
            "hash run_id real" to looseDb(hashRunId = "2.5"),
            "cutoff text" to looseDb(cutoff = "'many'"),
            "cutoff NULL" to looseDb(cutoff = "NULL"),
            "cutoff real" to looseDb(cutoff = "0.5"),
        )
        for ((name, db) in cases) {
            val root = seededRoot()
            assertRefused(zipOf("manifest.json" to manifest("t-${name.hashCode()}"), "sync_state.db" to db), root, unreadable)
            // Rolled back: the chat the bundle carried did not come across either.
            openDb(bundleDbPath(root)).use {
                assertEquals(name, 1L, (it.query("SELECT COUNT(*) AS n FROM chats").single()["n"] as Number).toLong())
            }
        }
    }

    @Test
    fun `the loose database with sane values imports, so the refusals above are about the types`() {
        val root = seededRoot()
        val r = importBundle(root, zipOf("manifest.json" to manifest("sane-1"), "sync_state.db" to looseDb()), openDb)
        assertTrue(r.error, r.ok)
        assertEquals(1, r.runsAdded)
        assertEquals(1, r.hashesAdded)
    }

    // ------------------------------------------------------- (1) not an object

    @Test
    fun `a manifest or settings that is JSON but not an object is refused`() {
        for (bad in listOf("[1]", "\"s\"", "5", "null", "true")) {
            val root = seededRoot()
            assertRefused(zipOf("manifest.json" to bad.toByteArray()), root)
            val root2 = seededRoot()
            assertRefused(zipOf("manifest.json" to manifest("o-$bad"), "settings.json" to bad.toByteArray()), root2)
        }
    }

    @Test
    fun `settings that are not JSON at all are treated as empty, as Python does`() {
        val root = seededRoot()
        val r = importBundle(root, zipOf("manifest.json" to manifest("junk-1"), "settings.json" to "not json{".toByteArray()), openDb)
        assertTrue(r.error, r.ok)
        assertNull(r.settings["imap_host"])
    }
}
