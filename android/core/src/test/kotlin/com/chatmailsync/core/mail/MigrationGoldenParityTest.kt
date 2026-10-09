package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * Cross-implementation parity for the `.cmsbackup` bundle, against fixtures written by the real
 * `src/migration.py` on Python 3.13 (`generate_migration_golden()` in
 * `tools/generate_kotlin_core_golden_fixtures.py`, expectations in `migration_golden.json`).
 *
 * Bundles are compared by DECODED content -- manifest and settings text, and database rows --
 * never by raw zip bytes, because the zip carries entry dates and the embedded SQLite file's
 * header records the SQLite version that wrote it.
 *
 * Direction 1 (Python bundle -> Kotlin import) and the export direction of Kotlin vs Python are
 * here. Direction 2 (Kotlin bundle -> Python `import_bundle`) is `tests/test_migration_kotlin_bundle.py`.
 */
class MigrationGoldenParityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val openDb: OpenDb = { f -> SqliteJdbcStateDb(f.absolutePath) }

    private fun resource(name: String): File {
        val stream = javaClass.classLoader.getResourceAsStream("golden/$name")
            ?: error("golden resource not found on test classpath: golden/$name")
        val out = File(tmp.newFolder(), name)
        stream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private val golden: Map<String, Any?> by lazy {
        parseBundleJson(resource("migration_golden.json").readText(Charsets.UTF_8)) as Map<String, Any?>
    }

    /** One canonical string per value, so a Python-parsed map and a driver row compare equal. */
    private fun canon(v: Any?): String = dumpBundleJson(normal(v))

    private fun normal(v: Any?): Any? = when (v) {
        null, is String, is Boolean -> v
        is Number -> if (v is Double || v is Float) v else v.toLong()
        is Map<*, *> -> v.entries.sortedBy { it.key as String }.associate { it.key as String to normal(it.value) }
        is List<*> -> v.map(::normal)
        else -> error("unexpected ${v::class}")
    }

    private fun dumpRoot(db: File): Map<String, List<String>> {
        val out = linkedMapOf<String, List<String>>()
        openDb(db).use { d ->
            val present = d.query("SELECT name FROM sqlite_master WHERE type='table'").map { it["name"] as String }.toSet()
            for (t in listOf("chats", "sync_runs", "message_hashes", "chat_cutoffs", "chat_senders", "app_state", "imported_bundles")) {
                if (t in present) out[t] = d.query("SELECT * FROM $t").map(::canon).sorted()
            }
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun expectedDump(m: Any?): Map<String, List<String>> =
        (m as Map<String, List<Any?>>).mapValues { (_, rows) -> rows.map(::canon).sorted() }

    @Test
    fun `Kotlin export of the source ledger matches Python's manifest, settings and rows`() {
        val root = tmp.newFolder()
        val db = bundleDbPath(root)
        db.parentFile.mkdirs()
        resource("migration_source.db").copyTo(db)

        @Suppress("UNCHECKED_CAST")
        val input = golden["export_settings_input"] as Map<String, Any?>
        val dest = File(tmp.newFolder(), "k.cmsbackup")
        val res = exportBundle(
            root, dest, openDb, input,
            appVersion = golden["app_version"] as String,
            bundleId = golden["bundle_id"] as String,
            createdAt = golden["created_at"] as String,
        )
        ZipFile(dest).use { z ->
            assertEquals(golden["manifest_text"], z.getInputStream(z.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8))
            assertEquals(golden["settings_text"], z.getInputStream(z.getEntry("settings.json")).readBytes().toString(Charsets.UTF_8))
            val unpacked = File(tmp.newFolder(), "sync_state.db")
            z.getInputStream(z.getEntry("sync_state.db")).use { i -> unpacked.outputStream().use { o -> i.copyTo(o) } }
            assertEquals(expectedDump(golden["bundle_db_dump"]), dumpRoot(unpacked))
        }
        @Suppress("UNCHECKED_CAST")
        val c = golden["export_counts"] as Map<String, Any?>
        assertEquals(
            BundleCounts(
                (c["chats"] as Long).toInt(), (c["runs"] as Long).toInt(), (c["hashes"] as Long).toInt(),
                (c["cutoffs"] as Long).toInt(), (c["senders"] as Long).toInt(),
            ),
            res.counts,
        )
        assertEquals(golden["export_settings_keys"], res.settingsKeys)
    }

    @Test
    fun `Python-written bundles import to the same result and rows as in Python, and re-import is a no-op`() {
        @Suppress("UNCHECKED_CAST")
        val cases = golden["import_cases"] as List<Map<String, Any?>>
        assertTrue(cases.size >= 5)
        for (case in cases) {
            val name = case["name"] as String
            val root = tmp.newFolder()
            val db = bundleDbPath(root)
            db.parentFile.mkdirs()
            (case["seed"] as String?)?.let { resource(it).copyTo(db) }
            val bundle = resource(case["bundle"] as String)
            val at = golden["created_at"] as String

            @Suppress("UNCHECKED_CAST")
            val first = case["first"] as Map<String, Any?>
            val got = importBundle(root, bundle, openDb, importedAt = at)
            assertEquals("$name ok", first["ok"], got.ok)
            assertEquals("$name already", first["already_imported"], got.alreadyImported)
            assertEquals("$name chats", (first["chats_added"] as Long).toInt(), got.chatsAdded)
            assertEquals("$name runs", (first["runs_added"] as Long).toInt(), got.runsAdded)
            assertEquals("$name hashes", (first["hashes_added"] as Long).toInt(), got.hashesAdded)
            assertEquals("$name cutoffs", (first["cutoffs_added"] as Long).toInt(), got.cutoffsAdded)
            assertEquals("$name senders", (first["senders_added"] as Long).toInt(), got.sendersAdded)
            assertEquals("$name settings", canon(first["settings"]), canon(got.settings))
            assertEquals("$name manifest", canon(first["manifest"]), canon(got.manifest))
            assertEquals("$name created_at", first["created_at"], got.createdAt)
            assertEquals("$name rows", expectedDump(case["dump_after_first"]), dumpRoot(db))

            @Suppress("UNCHECKED_CAST")
            val second = case["second"] as Map<String, Any?>
            val again = importBundle(root, bundle, openDb, importedAt = at)
            assertEquals("$name second ok", second["ok"], again.ok)
            assertEquals("$name second already", second["already_imported"], again.alreadyImported)
            assertEquals("$name second hashes", (second["hashes_added"] as Long).toInt(), again.hashesAdded)
            assertEquals("$name second runs", (second["runs_added"] as Long).toInt(), again.runsAdded)
            assertEquals("$name second settings", canon(second["settings"]), canon(again.settings))
            assertEquals("$name unchanged", true, case["dump_unchanged_by_second"])
            assertEquals("$name rows after second", expectedDump(case["dump_after_first"]), dumpRoot(db))
        }
    }

    /**
     * BUG-09: the ONE deliberate difference from Python. Python drops a run's messages_cutoff on
     * restore (the new row gets 0); Kotlin carries it. Every other field of every row still has
     * to match Python exactly, so the comparison masks only that column and asserts it apart.
     */
    @Test
    fun `restored runs match Python in every field except messages_cutoff, which Kotlin carries`() {
        @Suppress("UNCHECKED_CAST")
        val case = (golden["import_cases"] as List<Map<String, Any?>>).first { it["seed"] == null }
        val source = resource(case["bundle"] as String)

        // The same Python-written bundle, with a non-zero messages_cutoff set in its database.
        val dbFile = File(tmp.newFolder(), "sync_state.db")
        ZipFile(source).use { z -> z.getInputStream(z.getEntry("sync_state.db")).use { i -> dbFile.outputStream().use { o -> i.copyTo(o) } } }
        openDb(dbFile).use { it.exec("UPDATE sync_runs SET messages_cutoff = 7") }
        val edited = File(tmp.newFolder(), "edited.cmsbackup")
        java.util.zip.ZipOutputStream(edited.outputStream()).use { out ->
            ZipFile(source).use { z ->
                for (name in listOf("manifest.json", "settings.json", "sync_state.db")) {
                    val bytes = if (name == "sync_state.db") dbFile.readBytes() else z.getInputStream(z.getEntry(name)).readBytes()
                    out.putNextEntry(java.util.zip.ZipEntry(name)); out.write(bytes); out.closeEntry()
                }
            }
        }

        val root = tmp.newFolder()
        val db = bundleDbPath(root)
        db.parentFile.mkdirs()
        val got = importBundle(root, edited, openDb, importedAt = golden["created_at"] as String)
        assertTrue(got.error, got.ok)

        fun mask(row: Any?): String {
            @Suppress("UNCHECKED_CAST")
            val m = LinkedHashMap(normal(row) as Map<String, Any?>)
            m.remove("messages_cutoff")
            return dumpBundleJson(m)
        }
        @Suppress("UNCHECKED_CAST")
        val expected = (case["dump_after_first"] as Map<String, List<Any?>>).mapValues { (_, rows) -> rows.map(::mask).sorted() }
        val actual = linkedMapOf<String, List<String>>()
        var cutoffs = emptyList<Any?>()
        openDb(db).use { d ->
            val present = d.query("SELECT name FROM sqlite_master WHERE type='table'").map { it["name"] as String }.toSet()
            for (t in listOf("chats", "sync_runs", "message_hashes", "chat_cutoffs", "chat_senders", "app_state", "imported_bundles")) {
                if (t in present) actual[t] = d.query("SELECT * FROM $t").map(::mask).sorted()
            }
            cutoffs = d.query("SELECT messages_cutoff AS c FROM sync_runs").map { (it["c"] as Number).toLong() }
        }
        assertEquals(expected, actual)
        assertTrue(cutoffs.isNotEmpty())
        assertTrue("every restored run carries the bundle's cutoff, Python would give 0: $cutoffs", cutoffs.all { it == 7L })
    }
}
