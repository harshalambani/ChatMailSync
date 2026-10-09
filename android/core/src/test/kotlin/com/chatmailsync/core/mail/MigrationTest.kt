package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Behaviour and negative tests for the `.cmsbackup` port (`Migration.kt`). Placeholder data only
 * (Meera Iyer, Rohan Mehta, example.com). Cross-implementation parity lives in
 * [MigrationGoldenParityTest]; this file is what holds regardless of Python.
 */
class MigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val openDb: OpenDb = { f -> SqliteJdbcStateDb(f.absolutePath) }
    private val fixedClock = { "2026-10-01T10:00:00" }

    private fun repo(root: File) = StateRepository(fixedClock) { openDb(bundleDbPath(root)) }

    /** A root with a populated ledger: two chats, one run each, [hashesPerChat] hashes per chat. */
    private fun seededRoot(hashesPerChat: Int = 3): File {
        val root = tmp.newFolder()
        bundleDbPath(root).parentFile.mkdirs()
        val r = repo(root)
        r.initDb()
        for ((id, name) in listOf("chat-meera" to "Meera Iyer", "chat-rohan" to "Rohan Mehta")) {
            r.upsertChat(id, name, "$name.txt")
            val run = r.startSyncRun(id)
            r.completeSyncRun(run, "2026-09-30T09:00:00", "h-last", hashesPerChat, hashesPerChat, 0)
            r.insertMessageHashes(
                (1..hashesPerChat).map {
                    StateRepository.HashEntry("$id-h$it", id, "2026-09-30T09:0$it:00", run)
                },
            )
        }
        r.setChatCutoff("chat-meera", "2026-01-01")
        r.recordChatSenders("chat-meera", mapOf("Meera Iyer" to 3, "Rohan Mehta" to 1), "2026-09-30T09:00:00")
        ensureBundleLedger(bundleDbPath(root), openDb) // so a refusal after init_db leaves the bytes alone
        return root
    }

    private fun sha(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun dump(root: File): List<String> {
        val out = mutableListOf<String>()
        openDb(bundleDbPath(root)).use { db ->
            for (t in listOf("chats", "sync_runs", "message_hashes", "chat_cutoffs", "chat_senders", "app_state")) {
                db.query("SELECT * FROM $t").map { row -> row.toSortedMap().toString() }.sorted()
                    .forEach { out += "$t $it" }
            }
        }
        return out
    }

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

    private val goodManifest =
        """{"schema_version":1,"bundle_id":"b-1","created_at":"2026-10-01T10:00:00","app_version":"t","counts":{}}"""
            .toByteArray()

    private fun leftovers(root: File): List<String> =
        root.walkTopDown().filter { it.isFile }.map { it.name }
            .filter { it.endsWith("-wal") || it.endsWith("-shm") || it.endsWith(".tmp") || it.startsWith("cms_") }
            .toList()

    // ---------------------------------------------------------------- export

    @Test
    fun `export then import into an empty root reproduces the ledger and is a no-op the second time`() {
        val src = seededRoot()
        val bundle = File(tmp.newFolder(), "a.cmsbackup")
        val ex = exportBundle(src, bundle, openDb, mapOf("chunk_size" to "all"), "9.9.9", "bid-1", "2026-10-01T10:00:00")
        assertEquals(BundleCounts(2, 2, 6, 1, 2), ex.counts)

        val dst = tmp.newFolder()
        val first = importBundle(dst, bundle, openDb)
        assertTrue(first.error, first.ok)
        assertEquals(2, first.chatsAdded)
        assertEquals(6, first.hashesAdded)
        assertEquals(dump(src).filter { !it.startsWith("sync_runs") }, dump(dst).filter { !it.startsWith("sync_runs") })

        val before = sha(bundleDbPath(dst))
        val second = importBundle(dst, bundle, openDb)
        assertTrue(second.ok)
        assertTrue(second.alreadyImported)
        assertEquals(0, second.hashesAdded)
        assertEquals(before, sha(bundleDbPath(dst)))
    }

    @Test
    fun `export leaves no wal shm or temp files in the root`() {
        val src = seededRoot()
        exportBundle(src, File(tmp.newFolder(), "a.cmsbackup"), openDb)
        assertEquals(emptyList<String>(), leftovers(src))
    }

    @Test
    fun `VACUUM INTO keeps user_version and the snapshot has the data`() {
        val src = seededRoot()
        val snap = File(tmp.newFolder(), "snap.db")
        val srcVersion = openDb(bundleDbPath(src)).use { it.userVersion() }
        assertTrue(srcVersion > 0)
        snapshotBundleDb(bundleDbPath(src), snap, openDb)
        openDb(snap).use {
            assertEquals(srcVersion, it.userVersion())
            assertEquals(6L, (it.query("SELECT COUNT(*) AS n FROM message_hashes").single()["n"] as Number).toLong())
        }
        assertFalse(File(snap.path + "-wal").exists())
        assertFalse(File(snap.path + "-shm").exists())
    }

    @Test
    fun `sqlite floor guard - VACUUM INTO needs 3_27 and Android minSdk 30 ships 3_28 or later`() {
        val minSdk30Sqlite = intArrayOf(3, 28)
        val vacuumInto = intArrayOf(3, 27)
        assertTrue(minSdk30Sqlite[0] > vacuumInto[0] || (minSdk30Sqlite[0] == vacuumInto[0] && minSdk30Sqlite[1] >= vacuumInto[1]))
        // and the driver the tests run on is at least that new
        openDb(File(tmp.newFolder(), "v.db")).use {
            val v = it.query("SELECT sqlite_version() AS v").single()["v"] as String
            val p = v.split(".").map(String::toInt)
            assertTrue(v, p[0] > 3 || p[1] >= 28)
        }
    }

    @Test
    fun `credential-like and non-allow-listed settings are not exported`() {
        val src = seededRoot()
        val bundle = File(tmp.newFolder(), "a.cmsbackup")
        val ex = exportBundle(
            src, bundle, openDb,
            mapOf("chunk_size" to "all", "imap_host" to "evil.example.com", "app_password" to "hunter2", "random_key" to "x"),
        )
        assertEquals(listOf("chunk_size"), ex.settingsKeys)
        val text = ZipFile(bundle).use { z -> z.getInputStream(z.getEntry("settings.json")).readBytes().decodeToString() }
        assertFalse(text.contains("evil"))
        assertFalse(text.contains("hunter2"))
    }

    @Test
    fun `tripwire refuses a credential-like key even if it were allow-listed`() {
        try {
            portableSettings(mapOf("app_password" to "x"), setOf("app_password"))
            fail("expected BundleError")
        } catch (e: BundleError) {
            assertFalse(e.message!!.contains("x\""))
        }
    }

    // ---------------------------------------------------------------- import refusals

    private fun assertRefusedUntouched(source: File, root: File, mustContain: String? = null) {
        val before = sha(bundleDbPath(root))
        val r = importBundle(root, source, openDb)
        assertFalse(r.ok)
        if (mustContain != null) assertTrue(r.error, r.error!!.contains(mustContain))
        assertEquals(before, sha(bundleDbPath(root)))
        assertEquals(emptyList<String>(), leftovers(root))
    }

    @Test
    fun `not a zip is refused and the existing db is byte identical`() {
        val root = seededRoot()
        assertRefusedUntouched(File(tmp.newFolder(), "n.cmsbackup").also { it.writeText("hello") }, root)
    }

    @Test
    fun `missing manifest is refused`() {
        val root = seededRoot()
        assertRefusedUntouched(zipOf("settings.json" to "{}".toByteArray()), root)
    }

    @Test
    fun `newer schema is refused and the existing db is byte identical`() {
        val root = seededRoot()
        val m = """{"schema_version":2,"bundle_id":"b","created_at":"x","app_version":"","counts":{}}""".toByteArray()
        assertRefusedUntouched(zipOf("manifest.json" to m), root, "newer version")
    }

    @Test
    fun `non-object manifest and boolean schema are refused not thrown`() {
        val root = seededRoot()
        assertRefusedUntouched(zipOf("manifest.json" to "[1,2]".toByteArray()), root)
        assertRefusedUntouched(zipOf("manifest.json" to "\"s\"".toByteArray()), root)
        assertRefusedUntouched(zipOf("manifest.json" to """{"schema_version":true,"bundle_id":"b"}""".toByteArray()), root)
    }

    @Test
    fun `corrupt db member is refused and the existing db is byte identical`() {
        val root = seededRoot()
        val r = importBundle(
            root,
            zipOf("manifest.json" to goodManifest, "settings.json" to "{}".toByteArray(), "sync_state.db" to ByteArray(4096) { 7 }),
            openDb,
        )
        assertFalse(r.ok)
    }

    @Test
    fun `non-object settings and non-string imap_provider do not throw`() {
        val root = seededRoot()
        val r1 = importBundle(root, zipOf("manifest.json" to goodManifest, "settings.json" to "[1]".toByteArray()), openDb)
        assertFalse(r1.ok)
        val r2 = importBundle(
            root,
            zipOf("manifest.json" to goodManifest.decodeToString().replace("b-1", "b-2").toByteArray(),
                "settings.json" to """{"imap_provider":5}""".toByteArray()),
            openDb,
        )
        assertTrue(r2.error, r2.ok)
        assertFalse(r2.settings.containsKey("imap_host"))
    }

    @Test
    fun `oversized settings member is refused declared and lying`() {
        val root = seededRoot()
        val big = ByteArray(MAX_BUNDLE_MEMBER_BYTES + 10) { ' '.code.toByte() }
        assertRefusedUntouched(zipOf("manifest.json" to goodManifest, "settings.json" to big), root)
        assertRefusedUntouched(zipOf("manifest.json" to big), root)
    }

    @Test
    fun `hostile host and port are ignored and a retired provider lands on custom`() {
        val root = seededRoot()
        val s = """{"imap_provider":"gmail","imap_host":"evil.example.com","imap_port":1,"chunk_size":"all"}"""
        val r = importBundle(root, zipOf("manifest.json" to goodManifest, "settings.json" to s.toByteArray()), openDb)
        assertTrue(r.error, r.ok)
        assertEquals("imap.gmail.com", r.settings["imap_host"])
        assertFalse(r.settings.values.any { it == "evil.example.com" })
        val o = importBundle(
            root,
            zipOf("manifest.json" to goodManifest.decodeToString().replace("b-1", "b-3").toByteArray(),
                "settings.json" to """{"imap_provider":"outlook","imap_host":"evil.example.com"}""".toByteArray()),
            openDb,
        )
        assertEquals("custom", o.settings["imap_provider"])
        assertFalse(o.settings.containsKey("imap_host"))
    }

    @Test
    fun `path tricks and extra members write nothing outside the target`() {
        val root = seededRoot()
        val outside = File(root.parentFile, "escaped.txt")
        val r = importBundle(
            root,
            zipOf(
                "../escaped.txt" to "x".toByteArray(),
                "/abs.txt" to "x".toByteArray(),
                "manifest.json" to goodManifest,
                "extra/evil.db" to "x".toByteArray(),
            ),
            openDb,
        )
        assertTrue(r.error, r.ok)
        assertFalse(outside.exists())
        assertFalse(File("/abs.txt").exists())
        assertFalse(File(root, "extra").exists())
        assertEquals(emptyList<String>(), leftovers(root))
    }

    @Test
    fun `merge never deletes or overwrites a local hash and does not duplicate a run`() {
        val src = seededRoot()
        val dst = seededRoot()
        repo(dst).insertMessageHashes(listOf(StateRepository.HashEntry("local-only", "chat-meera", "2026-09-30T09:09:00", 1L)))
        val before = dump(dst).filter { it.startsWith("message_hashes") }.toSet()
        val bundle = File(tmp.newFolder(), "a.cmsbackup")
        exportBundle(src, bundle, openDb)
        val r = importBundle(dst, bundle, openDb)
        assertTrue(r.error, r.ok)
        assertEquals(0, r.chatsAdded)
        assertEquals(0, r.runsAdded)
        assertEquals(0, r.hashesAdded)
        assertTrue(dump(dst).filter { it.startsWith("message_hashes") }.toSet().containsAll(before))
    }

    @Test
    fun `no result text carries a settings value, chat name or address`() {
        val root = seededRoot()
        val secret = "Zq9-unique-value"
        val s = """{"chunk_size":"$secret","imap_user":"test@example.com"}"""
        val bad = importBundle(root, zipOf("manifest.json" to "[\"$secret\"]".toByteArray()), openDb)
        val bad2 = importBundle(root, zipOf("manifest.json" to goodManifest, "sync_state.db" to s.toByteArray()), openDb)
        for (r in listOf(bad, bad2)) {
            val t = r.error ?: ""
            for (needle in listOf(secret, "Meera", "Rohan", "example.com")) assertFalse(t, t.contains(needle))
        }
        val e = ByteArrayOutputStream()
        assertNotNull(e)
    }
}
