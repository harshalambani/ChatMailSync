package com.chatmailsync.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chatmailsync.core.mail.OpenDb
import com.chatmailsync.core.mail.StateRepository
import com.chatmailsync.core.mail.exportBundle
import com.chatmailsync.core.mail.importBundle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * QA-01: things only a real ART runtime (Android's ICU-backed java.util.regex, the platform
 * SQLite) can show. Synthetic fixtures only; no account, no network, no secret.
 */
@RunWith(AndroidJUnit4::class)
class ArtRuntimeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // (a) The CUT-02 crash: a pattern ART rejects throws from <clinit>. Force every class that
    // compiles a Pattern/Regex at class init, so a rejected flag fails here and not on a phone.
    @Test
    fun d74ThrowawayForcedFailure() {
        org.junit.Assert.fail("D74 throwaway: forced test failure")
    }

    @Test
    fun everyClassThatCompilesAPatternInitialisesOnArt() {
        val names = listOf(
            "com.chatmailsync.core.mail.RegexClassesKt",
            "com.chatmailsync.core.mail.ParserKt",
            "com.chatmailsync.core.mail.HtmlRenderer",
            "com.chatmailsync.core.mail.HtmlMimeBuilder",
            "com.chatmailsync.core.mail.MimeBuilder",
            "com.chatmailsync.core.mail.ListResponseKt",
            "com.chatmailsync.core.mail.StateRepository",
            "com.chatmailsync.core.mail.StateRepository\$Companion",
            "com.chatmailsync.core.mail.StateKt",
            "com.chatmailsync.core.mail.SyncStatsKt",
            "com.chatmailsync.core.mail.JvmImapConnection",
            "com.chatmailsync.app.SyncLogScreenKt",
        )
        val loader = javaClass.classLoader
        val missing = ArrayList<String>()
        for (n in names) {
            try {
                Class.forName(n, true, loader)
            } catch (e: ClassNotFoundException) {
                missing.add(n) // a renamed container must be noticed, not skipped
            }
        }
        assertTrue("class names in this guard are stale: $missing", missing.isEmpty())
    }

    // NEGATIVE: the flag the Nord crash used really IS rejected here. If this ever starts passing
    // the platform changed and the guard above would no longer be proving what it claims.
    @Test
    fun artRejectsTheUnicodeCharacterClassFlag() {
        val flag = java.util.regex.Pattern::class.java.getField("UNICODE_CHARACTER_CLASS").getInt(null)
        var rejected = false
        try {
            java.util.regex.Pattern.compile("\\s", flag)
        } catch (e: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("this runtime accepts UNICODE_CHARACTER_CLASS; the QA-01 premise changed", rejected)
    }

    private fun openDb(): OpenDb = { f -> AndroidStateDb(f) }

    private fun golden(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("golden/$name")!!.use { it.readBytes() }

    // (d) an old-schema (Python-written, no `stopped` column) database upgraded by the real
    // Android SQLite path, with its rows kept.
    @Test
    fun anOldSchemaDatabaseGainsTheStoppedColumnAndKeepsItsRows() {
        val f = File(tmp.newFolder(), "sync_state.db")
        f.writeBytes(golden("state_fixture_python_written.db"))
        val before = AndroidStateDb(f).use { db ->
            val cols = db.query("PRAGMA table_info(sync_runs)").map { it["name"].toString() }
            assertFalse("fixture should predate BUG-15", cols.contains("stopped"))
            (db.query("SELECT COUNT(*) AS n FROM sync_runs").first()["n"] as Number).toInt()
        }
        val repo = StateRepository { AndroidStateDb(f) }
        repo.initDb()
        repo.initDb() // idempotent: the duplicate-column ALTER must not escape
        AndroidStateDb(f).use { db ->
            val cols = db.query("PRAGMA table_info(sync_runs)").map { it["name"].toString() }
            assertTrue(cols.contains("stopped"))
            assertEquals(before, (db.query("SELECT COUNT(*) AS n FROM sync_runs").first()["n"] as Number).toInt())
        }
    }

    // (d) a fresh database and a stop flag written through the app's own adapter.
    @Test
    fun aFreshDatabaseStoresAStoppedRunWithoutTouchingTheLastSuccess() {
        val f = File(tmp.newFolder(), "sync_state.db")
        val repo = StateRepository { AndroidStateDb(f) }
        repo.initDb()
        repo.upsertChat("meera_iyer", "Meera Iyer", "WhatsApp Chat with Meera Iyer.txt")
        val run = repo.startSyncRun("meera_iyer")
        repo.stopSyncRun(run, 2, 5)
        val stopped = repo.getRecentRuns(days = 36500).single()
        assertTrue(stopped.stopped)
        assertEquals("failed", stopped.status)
        assertEquals(2, stopped.messagesSynced)
        assertEquals(0, repo.getPendingRuns().size)
        assertEquals(null, repo.getLastSuccessfulRun("meera_iyer"))
    }

    // (d) backup round trip through the Android adapter.
    @Test
    fun aBackupRoundTripsThroughTheAndroidAdapter() {
        val src = tmp.newFolder()
        val db = File(File(src, "data").also { it.mkdirs() }, "sync_state.db")
        val repo = StateRepository { AndroidStateDb(db) }
        repo.initDb()
        repo.upsertChat("rohan_mehta", "Rohan Mehta", "WhatsApp Chat with Rohan Mehta.txt")
        val run = repo.startSyncRun("rohan_mehta")
        repo.stopSyncRun(run, 1, 3)
        val bundle = File(tmp.newFolder(), "x.cmsbackup")
        val exported = exportBundle(src, bundle, openDb(), appVersion = "test")
        assertNotNull(exported)
        assertTrue(bundle.length() > 0)
        val dst = tmp.newFolder()
        File(dst, "data").mkdirs()
        val imported = importBundle(dst, bundle, openDb())
        assertTrue(imported.toString(), imported.ok)
        val back = StateRepository { AndroidStateDb(File(File(dst, "data"), "sync_state.db")) }
        back.initDb()
        val p = back.getRecentRuns(days = 36500).single()
        assertTrue(p.stopped)
        assertEquals(1, p.messagesSynced)
    }
}
