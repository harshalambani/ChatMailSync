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
 * Writes rows directly through [StateDb.exec] rather than through
 * [StateRepository]'s own write methods (`upsertChat`, `startSyncRun`, ...)
 * because those call [StateRepository.now], which stamps real wall-clock
 * time -- exactly the non-determinism this generator exists to avoid.
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
    StateRepository { SqliteJdbcStateDb(outFile.path) }.initDb()

    val db: StateDb = SqliteJdbcStateDb(outFile.path)
    try {
        db.beginTransaction()

        db.exec(
            """
            INSERT INTO chats (chat_id, display_name, gmail_thread_id, gmail_label_id,
                               anchor_message_id, source_filename, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                "chat_priya", "Priya Nair", "thread-kotlin-1", "WhatsApp/Priya Nair",
                "<kotlin-golden-anchor@local>", "Priya Nair.txt", frozenNow, frozenNow,
            ),
        )
        db.exec(
            """
            INSERT INTO chats (chat_id, display_name, gmail_thread_id, gmail_label_id,
                               anchor_message_id, source_filename, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf("chat_rohan", "Rohan Mehta", null, null, null, "Rohan Mehta.txt", frozenNow, frozenNow),
        )

        val h1 = StateRepository.computeMessageHash(
            "chat_priya", "2025-03-14T09:41:00", "Priya Nair", "hello from the kotlin-written fixture",
        )
        val h2 = StateRepository.computeMessageHash(
            "chat_priya", "2025-03-14T09:41:30", "Meera Iyer", "line one\nline two",
        )

        db.exec(
            """
            INSERT INTO sync_runs (chat_id, status, trigger, last_synced_ts, last_synced_hash,
                                   messages_parsed, messages_synced, messages_skipped,
                                   messages_cutoff, started_at, completed_at)
            VALUES (?, 'complete', 'manual', ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf("chat_priya", "2025-03-14T09:41:30", h2, 2, 2, 0, 0, frozenNow, frozenNow),
        )
        val run1 = db.lastInsertRowId()
        db.exec(
            "INSERT INTO message_hashes (hash, chat_id, message_ts, run_id) VALUES (?, ?, ?, ?)",
            listOf(h1, "chat_priya", "2025-03-14T09:41:00", run1),
        )
        db.exec(
            "INSERT INTO message_hashes (hash, chat_id, message_ts, run_id) VALUES (?, ?, ?, ?)",
            listOf(h2, "chat_priya", "2025-03-14T09:41:30", run1),
        )

        db.exec(
            """
            INSERT INTO sync_runs (chat_id, status, trigger, error_message, started_at, completed_at)
            VALUES (?, 'failed', 'watched_folder', ?, ?, ?)
            """.trimIndent(),
            listOf("chat_rohan", "kotlin-written fixture: simulated network error", frozenNow, frozenNow),
        )

        db.exec(
            "INSERT INTO chat_cutoffs (chat_id, cutoff_ts, set_at) VALUES (?, ?, ?)",
            listOf("chat_priya", "2025-01-01T00:00:00", frozenNow),
        )

        db.exec(
            "INSERT INTO chat_senders (chat_id, sender, first_seen, last_seen, msg_count) VALUES (?, ?, ?, ?, ?)",
            listOf("chat_priya", "Priya Nair", "2025-03-14T09:41:30", "2025-03-14T09:41:30", 1),
        )
        db.exec(
            "INSERT INTO chat_senders (chat_id, sender, first_seen, last_seen, msg_count) VALUES (?, ?, ?, ?, ?)",
            listOf("chat_priya", "Meera Iyer", "2025-03-14T09:41:30", "2025-03-14T09:41:30", 1),
        )
        db.exec(
            "INSERT INTO chat_senders (chat_id, sender, first_seen, last_seen, msg_count) VALUES (?, ?, ?, ?, ?)",
            listOf("chat_priya", "Rohan Mehta", "2025-03-14T09:41:30", "2025-03-14T09:41:30", 0),
        )

        db.exec(
            "INSERT INTO app_state (key, value) VALUES (?, ?)",
            listOf(StateRepository.SELF_SENDER_LEARNED, "Priya Nair"),
        )

        db.setUserVersion(StateRepository.SCHEMA_VERSION)
        db.commit()
    } finally {
        db.close()
    }

    // Checkpoint WAL into the main file and drop the -wal/-shm sidecars, the
    // same as generate_state_db_golden() does on the Python side -- so
    // exactly one file needs to be committed and read back.
    DriverManager.getConnection("jdbc:sqlite:${outFile.path}").use { conn ->
        conn.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
    }

    println("Wrote ${outFile.path} (${outFile.length()} bytes)")
}
