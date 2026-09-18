package com.chatmailsync.core.mail

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Kotlin port of `src/state.py` (Phase 2 "state" of the Kotlin core port --
 * see `2026-09-17-kotlin-core-fdroid-plan-and-windows-audit.md`, sections
 * D/E). A faithful behavioural twin of the Python module's schema,
 * `user_version` migration logic, and every query helper, except where a
 * section below explicitly says otherwise.
 *
 * NOT wired into `:app` yet -- `:app` still talks to `src/state.py` through
 * Chaquopy (`android_api.py`'s call sites). This file exists so it can be
 * swapped in later (Phase 4) without behavioural surprises.
 *
 * `compute_message_hash` is deliberately **not** redeclared here -- it
 * already has a Kotlin twin, [computeMessageHash] in `MailIndex.kt`, ported
 * there in the Phase 2 "parser" PR (#92) because `mail_index.py` imports it
 * from `state.py`. This file reuses that one function so there is exactly
 * one hash implementation in `:core`, matching the plan document's
 * insistence on byte-exact parity for it.
 *
 * Every public function takes a `db: () -> StateDb` factory, called once per
 * operation and closed before returning -- the same shape as `state.py`'s
 * `_connect()` context manager (fresh `sqlite3.connect()` per call, commit
 * on success, rollback and re-raise on exception, always close). This is
 * *not* a connection pool or a persistent handle; callers (the eventual
 * `CoreApi` in Phase 4) are expected to pass a factory that knows how to
 * open the one on-device database, the same way every `state.py` function
 * defaults to `config.STATE_DB_PATH`.
 */
class StateRepository(private val openDb: () -> StateDb) {

    // -----------------------------------------------------------------
    // Schema DDL -- verbatim copy of state.py's `_DDL` (SQL text and table/
    // column/index names unchanged; only the host-language comment markers
    // around each block were translated, never the SQL itself).
    // -----------------------------------------------------------------

    companion object {
        internal val DDL: String = """
            PRAGMA journal_mode = WAL;
            PRAGMA foreign_keys = ON;

            CREATE TABLE IF NOT EXISTS chats (
                chat_id           TEXT PRIMARY KEY,
                display_name      TEXT NOT NULL,
                gmail_thread_id   TEXT,
                gmail_label_id    TEXT,
                anchor_message_id TEXT,
                source_filename   TEXT NOT NULL,
                created_at        TEXT NOT NULL,
                updated_at        TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS sync_runs (
                run_id           INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id          TEXT    NOT NULL REFERENCES chats(chat_id),
                status           TEXT    NOT NULL CHECK(status IN ('pending', 'complete', 'failed')),
                trigger          TEXT    NOT NULL DEFAULT 'manual',
                last_synced_ts   TEXT,
                last_synced_hash TEXT,
                messages_parsed  INTEGER NOT NULL DEFAULT 0,
                messages_synced  INTEGER NOT NULL DEFAULT 0,
                messages_skipped INTEGER NOT NULL DEFAULT 0,
                messages_cutoff  INTEGER NOT NULL DEFAULT 0,
                error_message    TEXT,
                started_at       TEXT    NOT NULL,
                completed_at     TEXT
            );

            CREATE TABLE IF NOT EXISTS message_hashes (
                hash       TEXT    PRIMARY KEY,
                chat_id    TEXT    NOT NULL REFERENCES chats(chat_id),
                message_ts TEXT    NOT NULL,
                run_id     INTEGER NOT NULL REFERENCES sync_runs(run_id)
            );

            CREATE TABLE IF NOT EXISTS chat_cutoffs (
                chat_id   TEXT PRIMARY KEY,
                cutoff_ts TEXT NOT NULL,
                set_at    TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS chat_senders (
                chat_id    TEXT NOT NULL,
                sender     TEXT NOT NULL,
                first_seen TEXT,
                last_seen  TEXT,
                msg_count  INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (chat_id, sender)
            );

            CREATE TABLE IF NOT EXISTS app_state (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_message_hashes_chat  ON message_hashes(chat_id);
            CREATE INDEX IF NOT EXISTS idx_sync_runs_chat       ON sync_runs(chat_id);
            CREATE INDEX IF NOT EXISTS idx_sync_runs_status     ON sync_runs(status);
        """.trimIndent()

        /**
         * Twin of `state.py:RUN_NATURAL_KEY`. `messages_cutoff` is
         * deliberately absent -- see that module's comment: this tuple is
         * also the exact column list `migration.py` SELECTs out of an
         * incoming bundle, and a bundle written before the column existed
         * has no such column to select.
         */
        val RUN_NATURAL_KEY: List<String> = listOf(
            "chat_id", "status", "trigger", "last_synced_ts", "last_synced_hash",
            "messages_parsed", "messages_synced", "messages_skipped", "error_message",
            "started_at", "completed_at",
        )

        /** Twin of `state.py:_SCHEMA_VERSION`. */
        const val SCHEMA_VERSION: Int = 2

        const val SELF_SENDER_OVERRIDE: String = "self_sender_override"
        const val SELF_SENDER_LEARNED: String = "self_sender_learned"
        const val SELF_SENDER_LEARNED_PENDING: String = "self_sender_learned_pending"

        private val TIMESTAMP_ISO_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        /** Twin of `state.py:_now`. Naive local time, seconds precision --
         * `LocalDateTime.now().toString()` would drop a `:00` seconds
         * component and silently change every hash/comparison downstream,
         * so this always uses the explicit formatter (see the plan
         * document's warning under section D, "parser"). */
        internal fun now(): String = LocalDateTime.now().format(TIMESTAMP_ISO_FORMAT)

        /** Twin of `state.py:compute_message_hash`, reusing the single
         * implementation in `MailIndex.kt` -- see this class's doc comment. */
        fun computeMessageHash(chatId: String, timestampIso: String, sender: String, body: String): String =
            com.chatmailsync.core.mail.computeMessageHash(chatId, timestampIso, sender, body)
    }

    // -----------------------------------------------------------------
    // Connection helper -- twin of state.py's `_connect` context manager.
    // -----------------------------------------------------------------

    private fun <T> withDb(block: (StateDb) -> T): T {
        val db = openDb()
        return try {
            db.beginTransaction()
            val result = block(db)
            db.commit()
            result
        } catch (t: Throwable) {
            db.rollback()
            throw t
        } finally {
            db.close()
        }
    }

    // -----------------------------------------------------------------
    // Initialisation -- twin of state.py:init_db
    // -----------------------------------------------------------------

    fun initDb() {
        withDb { db ->
            db.execScript(DDL)
            // Migration for DBs created before the `trigger` column existed.
            try {
                db.exec("ALTER TABLE sync_runs ADD COLUMN trigger TEXT NOT NULL DEFAULT 'manual'")
            } catch (e: StateDbException) {
                // column already exists
            }
            try {
                db.exec("ALTER TABLE sync_runs ADD COLUMN messages_cutoff INTEGER NOT NULL DEFAULT 0")
            } catch (e: StateDbException) {
                // column already exists
            }

            val version = db.userVersion()
            if (version < 1) {
                dedupeSyncRuns(db)
            }
            if (version < SCHEMA_VERSION) {
                db.setUserVersion(SCHEMA_VERSION)
            }
        }
    }

    // -----------------------------------------------------------------
    // Key/value app state
    // -----------------------------------------------------------------

    fun getAppState(key: String): String? =
        withDb { db ->
            db.query("SELECT value FROM app_state WHERE key = ?", listOf(key))
                .firstOrNull()?.get("value") as String?
        }

    fun setAppState(key: String, value: String?) {
        withDb { db ->
            if (value == null || value.trim().isEmpty()) {
                db.exec("DELETE FROM app_state WHERE key = ?", listOf(key))
            } else {
                db.exec(
                    "INSERT INTO app_state (key, value) VALUES (?, ?) " +
                        "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                    listOf(key, value.trim()),
                )
            }
        }
    }

    /**
     * Twin of `state.py:_dedupe_sync_runs`. Collapses runs identical on
     * every [RUN_NATURAL_KEY] column but `run_id`, keeping the lowest
     * `run_id` of each set and repointing any `message_hashes` off the
     * copies before dropping them. Returns how many rows were removed.
     */
    private fun dedupeSyncRuns(db: StateDb): Int {
        val cols = RUN_NATURAL_KEY.joinToString(", ")
        val rows = db.query("SELECT run_id, $cols FROM sync_runs ORDER BY run_id")
        val seen = LinkedHashMap<List<Any?>, Long>()
        val doomed = mutableListOf<Pair<Long, Long>>() // (copy, original)
        for (row in rows) {
            val key = RUN_NATURAL_KEY.map { row[it] }
            val runId = (row["run_id"] as Number).toLong()
            val original = seen[key]
            if (original == null) {
                seen[key] = runId
            } else {
                doomed.add(runId to original)
            }
        }
        for ((copy, original) in doomed) {
            db.exec("UPDATE message_hashes SET run_id = ? WHERE run_id = ?", listOf(original, copy))
        }
        if (doomed.isNotEmpty()) {
            val ids = doomed.map { it.first }
            val placeholders = ids.joinToString(", ") { "?" }
            db.exec("DELETE FROM sync_runs WHERE run_id IN ($placeholders)", ids)
        }
        return doomed.size
    }

    // -----------------------------------------------------------------
    // Chat helpers
    // -----------------------------------------------------------------

    fun getChat(chatId: String): Chat? =
        withDb { db -> db.query("SELECT * FROM chats WHERE chat_id = ?", listOf(chatId)).firstOrNull()?.let(::rowToChat) }

    fun upsertChat(
        chatId: String,
        displayName: String,
        sourceFilename: String,
        gmailThreadId: String? = null,
        gmailLabelId: String? = null,
        anchorMessageId: String? = null,
    ) {
        val now = now()
        withDb { db ->
            db.exec(
                """
                INSERT INTO chats (chat_id, display_name, gmail_thread_id, gmail_label_id,
                                   anchor_message_id, source_filename, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(chat_id) DO UPDATE SET
                    gmail_thread_id   = COALESCE(excluded.gmail_thread_id,   gmail_thread_id),
                    gmail_label_id    = COALESCE(excluded.gmail_label_id,    gmail_label_id),
                    anchor_message_id = COALESCE(excluded.anchor_message_id, anchor_message_id),
                    updated_at        = excluded.updated_at
                """.trimIndent(),
                listOf(chatId, displayName, gmailThreadId, gmailLabelId, anchorMessageId, sourceFilename, now, now),
            )
        }
    }

    fun updateChatGmailIds(
        chatId: String,
        gmailThreadId: String? = null,
        gmailLabelId: String? = null,
        anchorMessageId: String? = null,
    ) {
        withDb { db ->
            db.exec(
                """
                UPDATE chats
                SET gmail_thread_id   = COALESCE(?, gmail_thread_id),
                    gmail_label_id    = COALESCE(?, gmail_label_id),
                    anchor_message_id = COALESCE(?, anchor_message_id),
                    updated_at        = ?
                WHERE chat_id = ?
                """.trimIndent(),
                listOf(gmailThreadId, gmailLabelId, anchorMessageId, now(), chatId),
            )
        }
    }

    fun listChats(): List<Chat> =
        withDb { db -> db.query("SELECT * FROM chats ORDER BY display_name").map(::rowToChat) }

    /** Twin of `state.py:resolve_chat`. */
    fun resolveChat(target: String): Chat? {
        getChat(target)?.let { return it }
        return listChats().firstOrNull { it.displayName.lowercase() == target.lowercase() }
    }

    // -----------------------------------------------------------------
    // Sync run helpers
    // -----------------------------------------------------------------

    fun startSyncRun(chatId: String, trigger: String = "manual"): Long =
        withDb { db ->
            db.exec(
                "INSERT INTO sync_runs (chat_id, status, trigger, started_at) VALUES (?, 'pending', ?, ?)",
                listOf(chatId, trigger, now()),
            )
            db.lastInsertRowId()
        }

    fun completeSyncRun(
        runId: Long,
        lastSyncedTs: String?,
        lastSyncedHash: String?,
        messagesParsed: Int,
        messagesSynced: Int,
        messagesSkipped: Int,
        messagesCutoff: Int = 0,
    ) {
        withDb { db ->
            db.exec(
                """
                UPDATE sync_runs
                SET status           = 'complete',
                    last_synced_ts   = ?,
                    last_synced_hash = ?,
                    messages_parsed  = ?,
                    messages_synced  = ?,
                    messages_skipped = ?,
                    messages_cutoff  = ?,
                    completed_at     = ?
                WHERE run_id = ?
                """.trimIndent(),
                listOf(lastSyncedTs, lastSyncedHash, messagesParsed, messagesSynced, messagesSkipped, messagesCutoff, now(), runId),
            )
        }
    }

    fun failSyncRun(runId: Long, errorMessage: String) {
        withDb { db ->
            db.exec(
                "UPDATE sync_runs SET status = 'failed', error_message = ?, completed_at = ? WHERE run_id = ?",
                listOf(errorMessage, now(), runId),
            )
        }
    }

    fun getLastSuccessfulRun(chatId: String): SyncRun? =
        withDb { db ->
            db.query(
                "SELECT * FROM sync_runs WHERE chat_id = ? AND status = 'complete' ORDER BY run_id DESC LIMIT 1",
                listOf(chatId),
            ).firstOrNull()?.let(::rowToSyncRun)
        }

    fun getPendingRuns(): List<SyncRun> =
        withDb { db -> db.query("SELECT * FROM sync_runs WHERE status = 'pending' ORDER BY run_id").map(::rowToSyncRun) }

    fun getRun(runId: Long): SyncRun? =
        withDb { db -> db.query("SELECT * FROM sync_runs WHERE run_id = ?", listOf(runId)).firstOrNull()?.let(::rowToSyncRun) }

    // -----------------------------------------------------------------
    // Message hash helpers
    // -----------------------------------------------------------------

    fun hashExists(msgHash: String): Boolean =
        withDb { db -> db.query("SELECT 1 FROM message_hashes WHERE hash = ?", listOf(msgHash)).isNotEmpty() }

    data class HashEntry(val hash: String, val chatId: String, val messageTs: String, val runId: Long)

    fun insertMessageHashes(entries: List<HashEntry>) {
        withDb { db ->
            for (e in entries) {
                db.exec(
                    "INSERT OR IGNORE INTO message_hashes (hash, chat_id, message_ts, run_id) VALUES (?, ?, ?, ?)",
                    listOf(e.hash, e.chatId, e.messageTs, e.runId),
                )
            }
        }
    }

    fun getHashesForRun(runId: Long): Set<String> =
        withDb { db ->
            db.query("SELECT hash FROM message_hashes WHERE run_id = ?", listOf(runId))
                .map { it["hash"] as String }
                .toSet()
        }

    // -----------------------------------------------------------------
    // Status / reporting helpers
    // -----------------------------------------------------------------

    fun getSyncSummary(): List<SyncSummaryRow> =
        withDb { db ->
            db.query(
                """
                SELECT
                    c.chat_id,
                    c.display_name,
                    c.source_filename,
                    c.gmail_thread_id,
                    c.gmail_thread_id IS NOT NULL AS has_thread,
                    r.status            AS last_run_status,
                    r.last_synced_ts,
                    r.messages_synced,
                    r.started_at        AS last_run_at
                FROM chats c
                LEFT JOIN sync_runs r ON r.run_id = (
                    SELECT run_id FROM sync_runs
                    WHERE chat_id = c.chat_id
                    ORDER BY run_id DESC LIMIT 1
                )
                ORDER BY c.display_name
                """.trimIndent(),
            ).map { row ->
                SyncSummaryRow(
                    chatId = row["chat_id"] as String,
                    displayName = row["display_name"] as String,
                    sourceFilename = row["source_filename"] as String,
                    gmailThreadId = row["gmail_thread_id"] as String?,
                    hasThread = asBoolean(row["has_thread"]),
                    lastRunStatus = row["last_run_status"] as String?,
                    lastSyncedTs = row["last_synced_ts"] as String?,
                    messagesSynced = (row["messages_synced"] as Number?)?.toInt(),
                    lastRunAt = row["last_run_at"] as String?,
                )
            }
        }

    fun getRecentRuns(days: Int = 90): List<SyncRun> {
        val cutoff = LocalDateTime.now().minusDays(days.toLong()).format(TIMESTAMP_ISO_FORMAT)
        return withDb { db ->
            db.query(
                """
                SELECT sync_runs.*, chats.display_name
                FROM sync_runs
                JOIN chats ON chats.chat_id = sync_runs.chat_id
                WHERE sync_runs.started_at >= ?
                ORDER BY sync_runs.started_at DESC
                """.trimIndent(),
                listOf(cutoff),
            ).map { row -> rowToSyncRun(row).copy(displayName = row["display_name"] as String?) }
        }
    }

    /** Twin of `state.py:is_uneventful_run`. Pure -- no database access. */
    fun isUneventfulRun(status: String, messagesSynced: Int?): Boolean =
        status == "complete" && (messagesSynced ?: 0) == 0

    fun summarizeRecentRuns(days: Int = 90): RunsSummary {
        val runs = getRecentRuns(days)
        val finished = runs.filter { it.status == "complete" || it.status == "failed" }
        val last = finished.firstOrNull()
        return RunsSummary(
            windowDays = days,
            totalRuns = runs.size,
            failedRuns = runs.count { it.status == "failed" },
            runningRuns = runs.count { it.status == "pending" },
            lastRunId = last?.runId,
            lastStatus = last?.status,
            lastDisplayName = last?.displayName,
            lastStartedAt = last?.startedAt,
            lastCompletedAt = last?.completedAt,
            lastMessagesSynced = last?.messagesSynced ?: 0,
            lastMessagesSkipped = last?.messagesSkipped ?: 0,
        )
    }

    // -----------------------------------------------------------------
    // Reset helper
    // -----------------------------------------------------------------

    class MailboxNotClearedError(val chatId: String, val archivedCount: Int) : RuntimeException(
        "'$chatId' has $archivedCount message(s) already archived in the mailbox. " +
            "Delete them there first, then reset with confirmedMailboxCleared=true.",
    )

    fun countArchivedMessages(chatId: String): Int =
        withDb { db ->
            val row = db.query("SELECT COUNT(*) AS n FROM message_hashes WHERE chat_id = ?", listOf(chatId)).first()
            (row["n"] as Number).toInt()
        }

    fun resetChat(chatId: String, confirmedMailboxCleared: Boolean = false) {
        if (!confirmedMailboxCleared) {
            val archived = countArchivedMessages(chatId)
            if (archived > 0) {
                throw MailboxNotClearedError(chatId, archived)
            }
        }
        withDb { db ->
            db.exec("DELETE FROM message_hashes WHERE chat_id = ?", listOf(chatId))
            db.exec("DELETE FROM sync_runs WHERE chat_id = ?", listOf(chatId))
            db.exec(
                """
                UPDATE chats
                SET gmail_thread_id   = NULL,
                    gmail_label_id    = NULL,
                    anchor_message_id = NULL,
                    updated_at        = ?
                WHERE chat_id = ?
                """.trimIndent(),
                listOf(now(), chatId),
            )
        }
    }

    // -----------------------------------------------------------------
    // Cutoff helpers
    // -----------------------------------------------------------------

    fun getChatCutoff(chatId: String): String? =
        withDb { db -> db.query("SELECT cutoff_ts FROM chat_cutoffs WHERE chat_id = ?", listOf(chatId)).firstOrNull()?.get("cutoff_ts") as String? }

    fun setChatCutoff(chatId: String, cutoff: String?) {
        val normalised = normaliseCutoff(cutoff)
        if (normalised == null) {
            clearChatCutoff(chatId)
            return
        }
        withDb { db ->
            db.exec(
                "INSERT INTO chat_cutoffs (chat_id, cutoff_ts, set_at) VALUES (?, ?, ?) " +
                    "ON CONFLICT(chat_id) DO UPDATE SET cutoff_ts = excluded.cutoff_ts, set_at = excluded.set_at",
                listOf(chatId, normalised, now()),
            )
        }
    }

    fun clearChatCutoff(chatId: String) {
        withDb { db -> db.exec("DELETE FROM chat_cutoffs WHERE chat_id = ?", listOf(chatId)) }
    }

    fun listChatCutoffs(): Map<String, String> =
        withDb { db ->
            db.query("SELECT chat_id, cutoff_ts FROM chat_cutoffs")
                .associate { (it["chat_id"] as String) to (it["cutoff_ts"] as String) }
        }

    // -----------------------------------------------------------------
    // Chat senders
    // -----------------------------------------------------------------

    fun recordChatSenders(chatId: String, counts: Map<String, Int>, seenTs: String? = null) {
        if (counts.isEmpty()) return
        val ts = seenTs ?: now()
        withDb { db ->
            for ((sender, countRaw) in counts) {
                val count = countRaw
                if (count < 0) continue
                if (count == 0) {
                    db.exec(
                        "INSERT OR IGNORE INTO chat_senders (chat_id, sender, first_seen, last_seen, msg_count) VALUES (?, ?, ?, ?, 0)",
                        listOf(chatId, sender, ts, ts),
                    )
                    continue
                }
                db.exec(
                    """
                    INSERT INTO chat_senders (chat_id, sender, first_seen, last_seen, msg_count)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(chat_id, sender) DO UPDATE SET
                        first_seen = MIN(first_seen, excluded.first_seen),
                        last_seen = MAX(last_seen, excluded.last_seen),
                        msg_count = msg_count + excluded.msg_count
                    """.trimIndent(),
                    listOf(chatId, sender, ts, ts, count),
                )
            }
        }
    }

    fun listChatSenders(chatId: String? = null): List<ChatSender> =
        withDb { db ->
            val rows = if (chatId == null) {
                db.query("SELECT chat_id, sender, first_seen, last_seen, msg_count FROM chat_senders ORDER BY msg_count DESC")
            } else {
                db.query(
                    "SELECT chat_id, sender, first_seen, last_seen, msg_count FROM chat_senders WHERE chat_id = ? ORDER BY msg_count DESC",
                    listOf(chatId),
                )
            }
            rows.map { row ->
                ChatSender(
                    chatId = row["chat_id"] as String,
                    sender = row["sender"] as String,
                    firstSeen = row["first_seen"] as String?,
                    lastSeen = row["last_seen"] as String?,
                    msgCount = (row["msg_count"] as Number).toInt(),
                )
            }
        }

    fun deleteChat(chatId: String) {
        withDb { db ->
            db.exec("DELETE FROM message_hashes WHERE chat_id = ?", listOf(chatId))
            db.exec("DELETE FROM sync_runs WHERE chat_id = ?", listOf(chatId))
            db.exec("DELETE FROM chat_cutoffs WHERE chat_id = ?", listOf(chatId))
            db.exec("DELETE FROM chat_senders WHERE chat_id = ?", listOf(chatId))
            db.exec("DELETE FROM chats WHERE chat_id = ?", listOf(chatId))
        }
    }

    // -----------------------------------------------------------------
    // Row mapping
    // -----------------------------------------------------------------

    private fun rowToChat(row: Map<String, Any?>) = Chat(
        chatId = row["chat_id"] as String,
        displayName = row["display_name"] as String,
        gmailThreadId = row["gmail_thread_id"] as String?,
        gmailLabelId = row["gmail_label_id"] as String?,
        anchorMessageId = row["anchor_message_id"] as String?,
        sourceFilename = row["source_filename"] as String,
        createdAt = row["created_at"] as String,
        updatedAt = row["updated_at"] as String,
    )

    private fun rowToSyncRun(row: Map<String, Any?>) = SyncRun(
        runId = (row["run_id"] as Number).toLong(),
        chatId = row["chat_id"] as String,
        status = row["status"] as String,
        trigger = row["trigger"] as String,
        lastSyncedTs = row["last_synced_ts"] as String?,
        lastSyncedHash = row["last_synced_hash"] as String?,
        messagesParsed = (row["messages_parsed"] as Number).toInt(),
        messagesSynced = (row["messages_synced"] as Number).toInt(),
        messagesSkipped = (row["messages_skipped"] as Number).toInt(),
        messagesCutoff = (row["messages_cutoff"] as Number).toInt(),
        errorMessage = row["error_message"] as String?,
        startedAt = row["started_at"] as String,
        completedAt = row["completed_at"] as String?,
        displayName = null,
    )

    private fun asBoolean(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> false
    }
}

// ---------------------------------------------------------------------------
// Pure functions -- twins of state.py's module-level, non-DB functions.
// ---------------------------------------------------------------------------

/**
 * Twin of `state.py:normalise_cutoff`. Throws [IllegalArgumentException] on
 * a date the app cannot compare -- the Kotlin twin of `datetime.strptime`
 * raising `ValueError` -- rather than storing something that would silently
 * sort wrong against every message timestamp.
 */
fun normaliseCutoff(value: String?): String? {
    if (value == null) return null
    val text = value.trim()
    if (text.isEmpty()) return null
    val day = if (text.length > 10) text.substring(0, 10) else text
    try {
        DateTimeFormatter.ofPattern("yyyy-MM-dd").parse(day)
    } catch (e: java.time.format.DateTimeParseException) {
        throw IllegalArgumentException("time data '$day' does not match format '%Y-%m-%d'", e)
    }
    return "${day}T00:00:00"
}

// ---------------------------------------------------------------------------
// Row data classes
// ---------------------------------------------------------------------------

data class Chat(
    val chatId: String,
    val displayName: String,
    val gmailThreadId: String?,
    val gmailLabelId: String?,
    val anchorMessageId: String?,
    val sourceFilename: String,
    val createdAt: String,
    val updatedAt: String,
)

data class SyncRun(
    val runId: Long,
    val chatId: String,
    val status: String,
    val trigger: String,
    val lastSyncedTs: String?,
    val lastSyncedHash: String?,
    val messagesParsed: Int,
    val messagesSynced: Int,
    val messagesSkipped: Int,
    val messagesCutoff: Int,
    val errorMessage: String?,
    val startedAt: String,
    val completedAt: String?,
    /** Only populated by [StateRepository.getRecentRuns] (a join with
     * `chats`) -- null everywhere else, matching how `state.py`'s plain
     * `sqlite3.Row` only carries `display_name` when the query selected it. */
    val displayName: String?,
)

data class ChatSender(
    val chatId: String,
    val sender: String,
    val firstSeen: String?,
    val lastSeen: String?,
    val msgCount: Int,
)

data class SyncSummaryRow(
    val chatId: String,
    val displayName: String,
    val sourceFilename: String,
    val gmailThreadId: String?,
    val hasThread: Boolean,
    val lastRunStatus: String?,
    val lastSyncedTs: String?,
    val messagesSynced: Int?,
    val lastRunAt: String?,
)

data class RunsSummary(
    val windowDays: Int,
    val totalRuns: Int,
    val failedRuns: Int,
    val runningRuns: Int,
    val lastRunId: Long?,
    val lastStatus: String?,
    val lastDisplayName: String?,
    val lastStartedAt: String?,
    val lastCompletedAt: String?,
    val lastMessagesSynced: Int,
    val lastMessagesSkipped: Int,
)
