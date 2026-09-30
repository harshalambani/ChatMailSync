package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager

/**
 * ST-01 (D33 = c): the :core state SQL uses `INSERT ... ON CONFLICT`, which needs SQLite 3.24, and the
 * SQLite inside Android is older than that before Android 11 (API 30). So minSdk must be at least 30,
 * and the duplicate-run sweep must never bind more than 999 values in one statement (Android 11's
 * SQLite is older than 3.32, the release that raised that limit).
 */
class StateMinSdkAndSweepTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A [StateDb] that records how many values each statement binds. */
    private class RecordingStateDb(private val inner: StateDb, val bindCounts: MutableList<Int>) : StateDb by inner {
        override fun exec(sql: String, params: List<Any?>) {
            bindCounts.add(params.size)
            inner.exec(sql, params)
        }

        override fun query(sql: String, params: List<Any?>): List<Map<String, Any?>> {
            bindCounts.add(params.size)
            return inner.query(sql, params)
        }
    }

    // ---- the sweep

    @Test
    fun aSweepOfTwentyFiveHundredDuplicatesDeletesAllOfThemWithNoStatementOverNineHundredNinetyNineValues() {
        val path = File(tmp.newFolder(), "sync_state.db").absolutePath
        StateRepository { SqliteJdbcStateDb(path) }.apply {
            initDb()
            upsertChat("chat1", "Chat One", "chat1.txt")
            val runId = startSyncRun("chat1")
            completeSyncRun(runId, "2025-03-14T09:41:00", "a", 1, 1, 0)
        }
        val copies = 2500
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.autoCommit = false
            val cols = StateRepository.RUN_NATURAL_KEY.joinToString(", ")
            val stmt = conn.prepareStatement(
                "INSERT INTO sync_runs ($cols) SELECT $cols FROM sync_runs WHERE run_id = 1",
            )
            repeat(copies) { stmt.executeUpdate() }
            conn.createStatement().use { it.execute("PRAGMA user_version = 0") }
            conn.commit()
        }
        assertEquals(copies + 1, countRuns(path))

        val counts = mutableListOf<Int>()
        StateRepository { RecordingStateDb(SqliteJdbcStateDb(path), counts) }.initDb()

        assertEquals("every duplicate is deleted, the original stays", 1, countRuns(path))
        assertTrue("the sweep did not bind anything: $counts", counts.any { it > 900 })
        assertTrue("a statement bound more than 999 values: ${counts.max()}", counts.all { it <= 999 })
    }

    @Test
    fun aSweepWithNothingToDeleteSendsNoDeleteAtAll() {
        val path = File(tmp.newFolder(), "sync_state.db").absolutePath
        val counts = mutableListOf<Int>()
        StateRepository { RecordingStateDb(SqliteJdbcStateDb(path), counts) }.initDb()
        assertTrue(counts.all { it <= 999 })
    }

    private fun countRuns(path: String): Int =
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().executeQuery("SELECT COUNT(*) FROM sync_runs").use { it.next(); it.getInt(1) }
        }

    // ---- the minSdk guard

    private val coreSqlWithUpsert = listOf("db.exec(\"INSERT INTO t (k) VALUES (?) ON CONFLICT(k) DO UPDATE SET k = excluded.k\")")
    private val coreSqlPlain = listOf("db.exec(\"INSERT OR IGNORE INTO t (k) VALUES (?)\")")

    private fun minSdkViolation(buildFile: String, coreSources: List<String>): String? {
        if (coreSources.none { it.contains("ON CONFLICT") }) return null
        val match = Regex("""minSdk\s*=\s*(\d+)""").find(buildFile)
            ?: return "no minSdk found in the build file"
        val value = match.groupValues[1].toInt()
        return if (value < 30) "minSdk = $value but :core SQL uses ON CONFLICT (needs SQLite 3.24, Android 11+)" else null
    }

    @Test
    fun theGuardFailsWhenTheBuildFileSaysMinSdk29AndCoreUsesOnConflict() {
        val violation = minSdkViolation("defaultConfig {\n    minSdk = 29\n}", coreSqlWithUpsert)
        assertNotNull(violation)
        assertTrue(violation, violation!!.contains("29"))
        assertNotNull(minSdkViolation("minSdk = 24", coreSqlWithUpsert))
        assertNotNull("a missing minSdk must not pass", minSdkViolation("android { }", coreSqlWithUpsert))
    }

    @Test
    fun theGuardPassesAtThirtyAndWhenCoreHasNoUpsert() {
        assertNull(minSdkViolation("minSdk = 30", coreSqlWithUpsert))
        assertNull(minSdkViolation("minSdk = 36", coreSqlWithUpsert))
        assertNull(minSdkViolation("minSdk = 24", coreSqlPlain))
    }

    @Test
    fun theRealBuildFileAndTheRealCoreSourcesPassTheGuard() {
        val coreDir = resolveCoreDir()
        val sources = File(coreDir, "src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
        assertTrue("the guard only means something while :core uses ON CONFLICT", sources.any { it.contains("ON CONFLICT") })
        val buildFile = File(coreDir.parentFile, "app/build.gradle.kts").readText()
        assertNull(minSdkViolation(buildFile, sources))
        val minSdk = Regex("""minSdk\s*=\s*(\d+)""").find(buildFile)!!.groupValues[1].toInt()
        assertFalse("minSdk is $minSdk", minSdk < 30)
    }

    private fun resolveCoreDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            for (candidate in listOfNotNull(dir, dir?.let { File(it, "core") }, dir?.let { File(it, "android/core") })) {
                if (File(candidate, "src/main/kotlin/com/chatmailsync/core/mail").isDirectory &&
                    File(candidate.parentFile, "app/build.gradle.kts").isFile
                ) return candidate
            }
            dir = dir?.parentFile
        }
        error("could not find :core from ${System.getProperty("user.dir")}")
    }
}
