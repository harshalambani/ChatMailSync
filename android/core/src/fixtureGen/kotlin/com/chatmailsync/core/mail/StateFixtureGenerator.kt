package com.chatmailsync.core.mail

import java.io.File
import java.sql.DriverManager

/**
 * One-off, human/agent-run generator: writes a synthetic `sync_state.db`
 * using the real Kotlin [StateRepository]/[SqliteJdbcStateDb] path, so a
 * Python pytest (`tests/test_state_kotlin_fixture.py`) can prove
 * `src/state.py` reads a Kotlin-written database correctly -- the mirror
 * image of `StateDbGoldenParityTest.kt`'s "Kotlin reads a Python-written
 * database" proof, completing the bidirectional cross-language DB
 * compatibility requirement.
 *
 * Deliberately NOT a JUnit test and NOT run by any of `test`/`build`/
 * `assemble`/CI -- see the `generateStateFixtureKotlinWritten` Gradle task
 * in `core/build.gradle.kts` for the one command that runs this. Every
 * value written below is a literal, fixed string -- never [System]
 * wall-clock time -- so re-running this generator reproduces the exact same
 * logical content every time (the schema itself, via [StateRepository.DDL],
 * is already deterministic; only the *rows* below needed the same care that
 * `generate_state_db_golden()` needed on the Python side, where the
 * equivalent non-determinism was `state._now()`).
 *
 * Writes every row through [StateRepository]'s own write methods (`upsertChat`,
 * `startSyncRun`, ...) with an injected frozen clock (ST-04), so the file is both
 * deterministic and a proof of the real Kotlin write path.
 */
fun main(args: Array<String>) {
    require(args.size == 1) { "usage: StateFixtureGenerator <output-db-path>" }
    val outFile = File(args[0]).absoluteFile
    outFile.parentFile?.mkdirs()
    if (outFile.exists()) outFile.delete()
    File(outFile.path + "-wal").let { if (it.exists()) it.delete() }
    File(outFile.path + "-shm").let { if (it.exists()) it.delete() }

    val frozenNow = "2025-03-14T09:40:00"

    // StateRepository.DDL is `internal` to :core's `main` compilation, and
    // this `fixtureGen` source set is a separate Kotlin compilation unit
    // (Gradle compiles each source set independently), so it cannot see
    // that constant directly the way test code in the same module can --
    // Kotlin's `internal` is per-compilation, not merely per-Gradle-module.
    // Going through the public StateRepository.initDb() instead gets the
    // exact same schema (and, incidentally, exercises the exact code path
    // an eventual Phase 4 Android caller would use to first open a fresh
    // database) without needing DDL to be public.
    // ST-04: every row goes through the REAL repository write methods, with a frozen
    // clock, so the file is deterministic AND proves the Kotlin write path (binding,
    // upserts, run lifecycle) produces a database Python reads -- raw INSERTs here
    // would only have proved the schema.
    val repo = StateRepository(clock = { frozenNow }) { SqliteJdbcStateDb(outFile.path) }
    repo.initDb()

    repo.upsertChat(
        "chat_priya", "Priya Nair", "Priya Nair.txt",
        gmailThreadId = "thread-kotlin-1",
        gmailLabelId = "WhatsApp/Priya Nair",
        anchorMessageId = "<kotlin-golden-anchor@local>",
    )
    repo.upsertChat("chat_rohan", "Rohan Mehta", "Rohan Mehta.txt")

    val h1 = StateRepository.computeMessageHash(
        "chat_priya", "2025-03-14T09:41:00", "Priya Nair", "hello from the kotlin-written fixture",
    )
    val h2 = StateRepository.computeMessageHash(
        "chat_priya", "2025-03-14T09:41:30", "Meera Iyer", "line one\nline two",
    )

    val run1 = repo.startSyncRun("chat_priya")
    repo.insertMessageHashes(
        listOf(
            StateRepository.HashEntry(h1, "chat_priya", "2025-03-14T09:41:00", run1),
            StateRepository.HashEntry(h2, "chat_priya", "2025-03-14T09:41:30", run1),
        ),
    )
    repo.completeSyncRun(run1, "2025-03-14T09:41:30", h2, messagesParsed = 2, messagesSynced = 2, messagesSkipped = 0)

    val run2 = repo.startSyncRun("chat_rohan", trigger = "watched_folder")
    repo.failSyncRun(run2, "kotlin-written fixture: simulated network error")

    repo.setChatCutoff("chat_priya", "2025-01-01")

    repo.recordChatSenders(
        "chat_priya",
        linkedMapOf("Priya Nair" to 1, "Meera Iyer" to 1, "Rohan Mehta" to 0),
        seenTs = "2025-03-14T09:41:30",
    )

    repo.setAppState(StateRepository.SELF_SENDER_LEARNED, "Priya Nair")

    // Checkpoint WAL into the main file and drop the -wal/-shm sidecars, the
    // same as generate_state_db_golden() does on the Python side -- so
    // exactly one file needs to be committed and read back.
    DriverManager.getConnection("jdbc:sqlite:${outFile.path}").use { conn ->
        conn.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
    }

    println("Wrote ${outFile.path} (${outFile.length()} bytes)")
}
