package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * Staleness guard for `tests/fixtures/bundle_fixture_kotlin_written.cmsbackup`: the committed
 * bundle (which pytest feeds to Python's `import_bundle`) must decode to exactly what the
 * current Kotlin export produces. Compared by decoded content -- member names, manifest text,
 * settings text and database rows -- not raw bytes (zip dates, SQLite header).
 */
class MigrationKotlinFixtureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val openDb: OpenDb = { f -> SqliteJdbcStateDb(f.absolutePath) }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "tests/fixtures").isDirectory && File(dir, "android").isDirectory) return dir!!
            dir = dir?.parentFile
        }
        error("could not find the repository root")
    }

    private fun decode(bundle: File): Triple<List<String>, Map<String, String>, List<String>> {
        ZipFile(bundle).use { z ->
            val names = z.entries().asSequence().map { it.name }.sorted().toList()
            val text = names.filter { it != "sync_state.db" }
                .associateWith { z.getInputStream(z.getEntry(it)).readBytes().toString(Charsets.UTF_8) }
            val db = File(tmp.newFolder(), "sync_state.db")
            z.getInputStream(z.getEntry("sync_state.db")).use { i -> db.outputStream().use { o -> i.copyTo(o) } }
            val rows = mutableListOf<String>()
            openDb(db).use { d ->
                for (t in listOf("chats", "sync_runs", "message_hashes", "chat_cutoffs", "chat_senders", "app_state")) {
                    d.query("SELECT * FROM $t").map { r -> t + " " + r.toSortedMap().toString() }.sorted().forEach(rows::add)
                }
            }
            return Triple(names, text, rows)
        }
    }

    @Test
    fun `the committed Kotlin-written bundle is what the current export produces`() {
        val committed = File(repoRoot(), "tests/fixtures/bundle_fixture_kotlin_written.cmsbackup")
        assertTrue("missing $committed - run :core:generateBundleFixtureKotlinWritten", committed.isFile)
        val fresh = File(tmp.newFolder(), "fresh.cmsbackup")
        MigrationBundleFixture.build(fresh)
        assertEquals(decode(fresh), decode(committed))
    }
}
