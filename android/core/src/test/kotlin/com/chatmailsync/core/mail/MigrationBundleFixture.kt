package com.chatmailsync.core.mail

import java.io.File
import java.nio.file.Files

/**
 * Builds the Kotlin-written `.cmsbackup` that `tests/test_migration_kotlin_bundle.py` hands to
 * the real Python `import_bundle` (direction 2 of the bundle parity proof). It lives in the
 * test source set so both the by-hand generator (`BundleFixtureGenerator.kt` in `fixtureGen`,
 * which sees test output) and the staleness guard ([MigrationKotlinFixtureTest]) run exactly
 * this code. Placeholder data only; every value is a literal, so the decoded content is stable.
 */
object MigrationBundleFixture {
    const val BUNDLE_ID = "00000000-0000-4000-8000-0000000b0b0b"
    const val CREATED_AT = "2026-10-01T10:00:00"
    const val APP_VERSION = "placeholder"

    /** What the bundle was asked to carry, hostile and credential-like entries included. */
    val SETTINGS: Map<String, Any?> = mapOf(
        "chunk_size" to "week",
        "theme_mode" to "dark",
        "imap_provider" to "yahoo",
        "imap_port" to 993L,
        "imap_email" to "test@example.com",
        "imap_host" to "evil.example.com",
        "app_password" to "not-a-real-password",
        "unknown_key" to "x",
    )

    fun build(dest: File) {
        val root = Files.createTempDirectory("cms_kt_fixture_").toFile()
        try {
            val open: OpenDb = { f -> SqliteJdbcStateDb(f.absolutePath) }
            val db = File(File(root, "data"), "sync_state.db")
            db.parentFile.mkdirs()
            val repo = StateRepository(clock = { CREATED_AT }) { open(db) }
            repo.initDb()
            repo.upsertChat("chat-priya", "Priya Nair", "Priya Nair.txt")
            repo.upsertChat("chat-vikram", "Vikram Rao", "Vikram Rao.txt")
            val r1 = repo.startSyncRun("chat-priya")
            repo.completeSyncRun(r1, "2026-09-30T09:02:00", "p2", 3, 2, 1)
            val r2 = repo.startSyncRun("chat-vikram")
            repo.failSyncRun(r2, "placeholder failure")
            repo.insertMessageHashes(
                listOf(
                    StateRepository.HashEntry("p1", "chat-priya", "2026-09-30T09:01:00", r1),
                    StateRepository.HashEntry("p2", "chat-priya", "2026-09-30T09:02:00", r1),
                ),
            )
            repo.setChatCutoff("chat-priya", "2026-02-01")
            repo.recordChatSenders("chat-priya", mapOf("Priya Nair" to 2, "Vikram Rao" to 1), "2026-09-30T09:02:00")
            repo.setAppState("owner_override", "Priya Nair")
            exportBundle(
                root, dest, open, SETTINGS,
                appVersion = APP_VERSION, bundleId = BUNDLE_ID, createdAt = CREATED_AT,
                zipTimeMillis = 1_790_000_000_000L,
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
