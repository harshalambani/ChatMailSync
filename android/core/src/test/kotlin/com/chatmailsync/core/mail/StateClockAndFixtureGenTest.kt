package com.chatmailsync.core.mail

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** ST-04: the Kotlin-written fixture goes through the real write methods, under a frozen clock. */
class StateClockAndFixtureGenTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val frozen = "2025-03-14T09:40:00"

    private fun repo(clock: (() -> String)?): StateRepository {
        val path = File(tmp.newFolder(), "sync_state.db").absolutePath
        val r = if (clock == null) StateRepository { SqliteJdbcStateDb(path) }
        else StateRepository(clock = clock) { SqliteJdbcStateDb(path) }
        r.initDb()
        return r
    }

    @Test
    fun aFrozenClockStampsEveryWriteTheRepositoryMakes() {
        val r = repo { frozen }
        r.upsertChat("c", "C", "c.txt")
        val run = r.startSyncRun("c")
        r.completeSyncRun(run, "2025-03-14T09:41:00", "h", 1, 1, 0)
        r.setChatCutoff("c", "2025-01-01")
        val chat = r.getChat("c")!!
        assertEquals(frozen, chat.createdAt)
        assertEquals(frozen, chat.updatedAt)
        val got = r.getRun(run)!!
        assertEquals(frozen, got.startedAt)
        assertEquals(frozen, got.completedAt)
    }

    // NEGATIVE: with no clock passed the app still stamps real time, never a frozen value.
    @Test
    fun theDefaultClockIsRealTime() {
        val r = repo(null)
        r.upsertChat("c", "C", "c.txt")
        val stamp = r.getChat("c")!!.createdAt
        assertTrue(stamp, stamp.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}""")))
        val year = LocalDate.now().year
        assertTrue(stamp, stamp.startsWith("$year-") || stamp.startsWith("${year - 1}-"))
    }

    // NEGATIVE: the generator must not go back to raw INSERTs that bypass the repository.
    @Test
    fun theFixtureGeneratorWritesNoRawSql() {
        val src = File(resolveCoreDir(), "src/fixtureGen/kotlin/com/chatmailsync/core/mail/StateFixtureGenerator.kt")
        val code = src.readLines().filter { !it.trimStart().startsWith("*") && !it.trimStart().startsWith("//") }.joinToString("\n")
        assertFalse("raw INSERT in the generator", code.contains("INSERT INTO"))
        assertFalse("raw db.exec in the generator", code.contains("db.exec("))
        assertTrue("generator no longer uses the repository", code.contains("repo.startSyncRun"))
    }

    private fun resolveCoreDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            for (c in listOfNotNull(dir, dir?.let { File(it, "core") }, dir?.let { File(it, "android/core") })) {
                if (File(c, "src/fixtureGen/kotlin").isDirectory) return c
            }
            dir = dir?.parentFile
        }
        error("could not find :core")
    }
}
