package com.chatmailsync.core.mail

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kotlin port of the Chaquopy facade `src/android_api.py`: one public function per
 * Python function, same arguments, same result shapes.
 *
 * Not wired into `:app` (CUT-02 does that). Until then the app keeps calling Python.
 *
 * Result shapes. Every function that returns a dict in Python returns a
 * `Map<String, Any?>` here whose keys are exactly Python's, in Python's order; a list of
 * dicts is a `List<Map<String, Any?>>`. Values are `null`, [Boolean], [Int], [Long],
 * [Double], [String], [List] and [Map] only, so [CoreApiJson.render] gives each result a
 * JSON form with Python's key names and the swap in `:app` is a change of caller, not of
 * parsing.
 *
 * This is a straight port of today's behaviour. Nothing here fixes a Python quirk (the
 * stop request is still only honoured between files, for one). The deliberate differences
 * are few and each is marked "Difference from Python" below.
 *
 * Error text boundary (the `:core` half of SEC-06(3)): every error string in a result and
 * every exception that leaves this class has [redact] (the app password) removed with
 * [stripSecret] first -- raw and IMAP-quoted form both. Exceptions leave as
 * [CoreApiException], which has no cause, so a wrapped exception cannot bring the secret
 * back. Python let the raw exception through.
 *
 * Root setup: the constructor is what `config.set_root()` is today. [root] holds
 * `data/inbox`, `data/processed` and `data/sync_state.db`; [openDb] opens the state store
 * at a given path (the Android build passes its SQLite opener, the tests the JDBC one).
 */
class CoreApi(
    root: File,
    private val openDb: OpenDb,
    /** The app password, or any secret, to remove from every error text. Read per call. */
    private val redact: () -> String? = { null },
    clock: () -> String = { StateRepository.now() },
    private val sleeper: Sleeper = SystemSleeper,
) {
    val paths: RootPaths = RootPaths(root)

    private val repo: StateRepository = StateRepository(clock) { openDb(paths.stateDbPath) }

    private val progressLock = Any()
    private val progressTracker = ProgressTracker()
    private val stopFlag = AtomicBoolean(false)

    // ------------------------------------------------------------------
    // Error text boundary
    // ------------------------------------------------------------------

    private fun clean(text: String): String = stripSecret(text, redact())

    private fun cleanOrNull(text: String?): String? = text?.let { clean(it) }

    /** Runs [block]; any exception leaves as a [CoreApiException] with the secret removed. */
    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (e: CoreApiException) {
            throw e
        } catch (e: SyncAbortedException) {
            throw e
        } catch (e: Exception) {
            throw CoreApiException(clean("${e::class.simpleName}: ${e.message.orEmpty()}"))
        }

    /** Opens (and brings up to date) the state store; twin of `init_db(config.STATE_DB_PATH)`. */
    private fun ready(): StateRepository {
        paths.dataDir.mkdirs()
        repo.initDb()
        return repo
    }

    // ------------------------------------------------------------------
    // Progress (android_api.py:86-100)
    // ------------------------------------------------------------------

    /** `progress_state` (android_api.py:90): the current sync as a rendered snapshot. */
    fun progressState(): Map<String, Any?> = synchronized(progressLock) { progressTracker.state.asDict() }

    private fun publishProgress(event: Map<String, Any?>) {
        synchronized(progressLock) { progressTracker.feed(event) }
    }

    // ------------------------------------------------------------------
    // ping / inbox / providers
    // ------------------------------------------------------------------

    /**
     * `ping` (android_api.py:103). Difference from Python: it says Kotlin, not the Python
     * version. The app never calls it.
     */
    fun ping(): String = "android_api.ping() OK \u2014 Kotlin core"

    /** `list_inbox` (android_api.py:109): `[{"name", "size_bytes"}]`, sorted by name. */
    fun listInbox(): List<Map<String, Any?>> = guarded {
        val inbox = paths.inboxDir
        if (!inbox.exists()) return@guarded emptyList()
        (inbox.listFiles() ?: emptyArray())
            .filter { it.isFile }
            .sortedWith { a, b -> CodePointOrder.compare(a.name, b.name) }
            .map { linkedMapOf<String, Any?>("name" to it.name, "size_bytes" to it.length()) }
    }

    /** `remove_from_inbox` (android_api.py:126): `{"ok", "error"}`. A file that is not there is fine. */
    fun removeFromInbox(name: String): Map<String, Any?> = guarded {
        val base = name.split('/').lastOrNull { it.isNotEmpty() && it != "." } ?: ""
        val path = if (base.isEmpty()) paths.inboxDir else File(paths.inboxDir, base)
        try {
            if (Files.isDirectory(path.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return@guarded linkedMapOf("ok" to false, "error" to clean("Is a directory: ${path.name}"))
            }
            Files.delete(path.toPath())
        } catch (_: NoSuchFileException) {
            // Already gone: the goal is met.
        } catch (e: IOException) {
            return@guarded linkedMapOf("ok" to false, "error" to clean(e.message.orEmpty()))
        }
        linkedMapOf("ok" to true, "error" to null)
    }

    /** `imap_providers` (android_api.py:139): the preset table; a missing host comes through as "". */
    fun imapProviders(): List<Map<String, Any?>> =
        IMAP_PROVIDERS.map { (key, info) ->
            linkedMapOf<String, Any?>("key" to key, "label" to info.label, "host" to (info.host ?: ""), "port" to info.port)
        }

    // ------------------------------------------------------------------
    // preview
    // ------------------------------------------------------------------

    /** `_preview_cutoff` (android_api.py:150): the floor this file would meet, as a plain day. */
    private fun previewCutoff(chatId: String, appCutoff: String): String? {
        val own: String? = try {
            ready().getChatCutoff(chatId)
        } catch (_: Exception) {
            null
        }
        if (!own.isNullOrEmpty()) return own.take(10)
        return try {
            (normaliseCutoff(appCutoff) ?: "").take(10).ifEmpty { null }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * `preview` (android_api.py:174): parse one export file without touching the mailbox, and
     * report its size and date range. Keys: ok, display_name, message_count,
     * participant_count, media_count, first_message_ts, last_message_ts, cutoff_date, error.
     */
    fun preview(filePath: String, cutoff: String = ""): Map<String, Any?> = guarded {
        val file = File(filePath)
        fun empty(): LinkedHashMap<String, Any?> = linkedMapOf(
            "ok" to false,
            "display_name" to null,
            "message_count" to 0,
            "participant_count" to 0,
            "media_count" to 0,
            "first_message_ts" to null,
            "last_message_ts" to null,
            "cutoff_date" to null,
            "error" to null,
        )

        var chatId = ""
        var displayName = ""
        var messages: List<ParsedMessage> = emptyList()
        try {
            val info = extractChatInfo(file.name)
            chatId = info.chatId
            displayName = info.displayName
            messages = parseFile(file, chatId).toList()
        } catch (e: Exception) {
            return@guarded empty().also { it["error"] = clean(e.message.orEmpty()) }
        }

        if (messages.isEmpty()) {
            return@guarded empty().also {
                it["ok"] = true
                it["display_name"] = displayName
                it["error"] = "No messages could be parsed from this file."
            }
        }

        linkedMapOf(
            "ok" to true,
            "display_name" to displayName,
            "message_count" to messages.size,
            "participant_count" to messages.map { it.sender }.toSet().size,
            "media_count" to messages.count { !it.attachmentFilename.isNullOrEmpty() },
            "first_message_ts" to messages.minOf { it.timestampIso },
            "last_message_ts" to messages.maxOf { it.timestampIso },
            "cutoff_date" to previewCutoff(chatId, cutoff),
            "error" to null,
        )
    }

    /** `format_preview` (android_api.py:228): [preview]'s map as the few lines a person reads. */
    fun formatPreview(info: Map<String, Any?>): String {
        if (!truthy(info["ok"])) {
            return info["error"]?.takeIf { truthy(it) }?.toString() ?: "This file could not be read."
        }
        val name = info["display_name"]?.takeIf { truthy(it) }?.toString() ?: "This chat"
        val lines = mutableListOf(name)

        if (truthy(info["error"])) {
            // ok=true with an error means the file parsed but held nothing.
            lines.add(info["error"].toString())
            return lines.joinToString("\n")
        }

        val messageCount = (info["message_count"] as Number).toLong()
        val participantCount = (info["participant_count"] as Number).toLong()
        val parts = mutableListOf(
            "$messageCount message${if (messageCount == 1L) "" else "s"}",
            "$participantCount participant${if (participantCount == 1L) "" else "s"}",
        )
        if (truthy(info["media_count"])) parts.add("${info["media_count"]} media")
        lines.add(parts.joinToString(", "))

        val first = info["first_message_ts"] as String?
        val last = info["last_message_ts"] as String?
        if (!first.isNullOrEmpty() && !last.isNullOrEmpty()) {
            lines.add("${first.take(10)} to ${last.take(10)}")
        }

        val day = info["cutoff_date"] as String?
        if (!day.isNullOrEmpty() && !last.isNullOrEmpty() && last.take(10) < day) {
            lines.add("All of this is older than your cutoff date, $day, so none of it would be sent.")
        } else if (!day.isNullOrEmpty() && !first.isNullOrEmpty() && first.take(10) < day) {
            lines.add("Your cutoff date, $day, holds back the part of this that is older than it.")
        }
        return lines.joinToString("\n")
    }

    /** `preview_text` (android_api.py:283): [preview] + [formatPreview] in one call. */
    fun previewText(filePath: String, cutoff: String = ""): String = formatPreview(preview(filePath, cutoff))

    // ------------------------------------------------------------------
    // sync
    // ------------------------------------------------------------------

    /**
     * `request_stop` (android_api.py:297): ask the in-flight [sync] to stop. It is only
     * honoured between files: the file in progress finishes and no further file is started.
     */
    fun requestStop() {
        stopFlag.set(true)
    }

    /**
     * `sync` (android_api.py:302): one full pass over the inbox. [onProgress] gets the
     * files_total / total_messages / syncing / chunk / file_done events, with the secret
     * already removed from every string in them. Returns the [SyncStats.asMap] keys plus
     * `stopped`.
     */
    fun sync(
        transport: MailTransport? = null,
        chunkSize: ChunkSize? = null,
        dryRun: Boolean = false,
        chatFilter: String? = null,
        onProgress: ((Map<String, Any?>) -> Unit)? = null,
        trigger: String = "manual",
        cutoffDate: String? = null,
    ): Map<String, Any?> {
        val relay: (Map<String, Any?>) -> Unit = { event ->
            publishProgress(event)
            onProgress?.invoke(event)
        }

        synchronized(progressLock) { progressTracker.reset() }
        stopFlag.set(false)
        paths.dataDir.mkdirs()
        val mgr = guarded {
            SyncManager(
                repo = repo,
                transport = transport,
                inboxDir = paths.inboxDir,
                processedDir = paths.processedDir,
                chunkSize = chunkSize ?: ChunkSize.Day,
                dryRun = dryRun,
                trigger = trigger,
                cutoffDate = cutoffDate,
                onProgress = relay,
                shouldStop = { stopFlag.get() },
                redact = redact(),
                sleeper = sleeper,
            )
        }
        val stats = guarded { mgr.run(chatFilter) }
        val stopped = stopFlag.get()
        // `stopped` travels with the event so the final headline says "Stopped", not "Done".
        publishProgress(linkedMapOf("type" to "done", "stopped" to stopped))
        return LinkedHashMap(stats.asMap()).also { it["stopped"] = stopped }
    }

    // ------------------------------------------------------------------
    // status / sync log
    // ------------------------------------------------------------------

    /** `status` (android_api.py:357): the per-chat summary. */
    fun status(): List<Map<String, Any?>> = guarded {
        ready().getSyncSummary().map { r ->
            linkedMapOf<String, Any?>(
                "chat_id" to r.chatId,
                "display_name" to r.displayName,
                "source_filename" to r.sourceFilename,
                "gmail_thread_id" to r.gmailThreadId,
                // SQLite gives Python 1 or 0 here, so this stays an Int.
                "has_thread" to (if (r.hasThread) 1 else 0),
                "last_run_status" to r.lastRunStatus,
                "last_synced_ts" to r.lastSyncedTs,
                "messages_synced" to r.messagesSynced,
                "last_run_at" to r.lastRunAt,
            )
        }
    }

    /** `sync_log` (android_api.py:363): runs of the last [days] days, newest first, plus `uneventful`. */
    fun syncLog(days: Int = 90): List<Map<String, Any?>> = guarded {
        val r = ready()
        r.getRecentRuns(days).map { run ->
            linkedMapOf<String, Any?>(
                "run_id" to run.runId,
                "chat_id" to run.chatId,
                "status" to run.status,
                "trigger" to run.trigger,
                "last_synced_ts" to run.lastSyncedTs,
                "last_synced_hash" to run.lastSyncedHash,
                "messages_parsed" to run.messagesParsed,
                "messages_synced" to run.messagesSynced,
                "messages_skipped" to run.messagesSkipped,
                "messages_cutoff" to run.messagesCutoff,
                "error_message" to cleanOrNull(run.errorMessage),
                "started_at" to run.startedAt,
                "completed_at" to run.completedAt,
                "display_name" to run.displayName,
                "uneventful" to r.isUneventfulRun(run.status, run.messagesSynced),
            )
        }
    }

    /** `sync_status` (android_api.py:378): the last finished run and the failure count. */
    fun syncStatus(days: Int = 90): Map<String, Any?> = guarded {
        val s = ready().summarizeRecentRuns(days)
        linkedMapOf(
            "window_days" to s.windowDays,
            "total_runs" to s.totalRuns,
            "failed_runs" to s.failedRuns,
            "running_runs" to s.runningRuns,
            "last_run_id" to s.lastRunId,
            "last_status" to s.lastStatus,
            "last_display_name" to s.lastDisplayName,
            "last_started_at" to s.lastStartedAt,
            "last_completed_at" to s.lastCompletedAt,
            "last_messages_synced" to s.lastMessagesSynced,
            "last_messages_skipped" to s.lastMessagesSkipped,
        )
    }

    // ------------------------------------------------------------------
    // reset / delete
    // ------------------------------------------------------------------

    // The target is scrubbed before it is quoted: quoting doubles a backslash, which would
    // turn the secret into a third form that no later scrub recognises.
    private fun noChat(target: String): String = clean("No chat found matching ${pyRepr(clean(target))}")

    /** `reset_preview` (android_api.py:386): what a reset would cost. Changes nothing. */
    fun resetPreview(chatIdOrName: String): Map<String, Any?> = guarded {
        val r = ready()
        val chat = r.resolveChat(chatIdOrName)
            ?: return@guarded linkedMapOf(
                "ok" to false,
                "chat_id" to null,
                "display_name" to null,
                "archived_count" to 0,
                "mailbox_folder" to null,
                "requires_confirmation" to false,
                "error" to noChat(chatIdOrName),
            )
        val archived = r.countArchivedMessages(chat.chatId)
        linkedMapOf(
            "ok" to true,
            "chat_id" to chat.chatId,
            "display_name" to chat.displayName,
            "archived_count" to archived,
            "mailbox_folder" to mailboxFolderFor(chat.displayName),
            "requires_confirmation" to (archived > 0),
            "error" to null,
        )
    }

    /**
     * `reset` (android_api.py:419): reset one chat's sync state and put its export back in the
     * inbox. `needs_confirmation` true means nothing was changed.
     */
    fun reset(chatIdOrName: String, confirmedMailboxCleared: Boolean = false): Map<String, Any?> = guarded {
        val r = ready()
        val chat = r.resolveChat(chatIdOrName)
            ?: return@guarded linkedMapOf(
                "ok" to false,
                "chat_id" to null,
                "display_name" to null,
                "file_restored" to false,
                "archived_count" to 0,
                "needs_confirmation" to false,
                "error" to noChat(chatIdOrName),
            )

        try {
            r.resetChat(chat.chatId, confirmedMailboxCleared)
        } catch (e: StateRepository.MailboxNotClearedError) {
            return@guarded linkedMapOf(
                "ok" to false,
                "chat_id" to chat.chatId,
                "display_name" to chat.displayName,
                "file_restored" to false,
                "archived_count" to e.archivedCount,
                "needs_confirmation" to true,
                "error" to clean(
                    "${e.archivedCount} message(s) from this chat are already in " +
                        "${mailboxFolderFor(chat.displayName)}. Delete them there " +
                        "first, or resetting will file a second copy of every one.",
                ),
            )
        }

        var fileRestored = false
        val sourceFilename = chat.sourceFilename
        if (sourceFilename.isNotEmpty()) {
            val src = File(paths.processedDir, sourceFilename)
            val dest = File(paths.inboxDir, sourceFilename)
            if (src.exists() && !dest.exists()) {
                dest.parentFile?.mkdirs()
                Files.move(src.toPath(), dest.toPath())
                fileRestored = true
            }
        }

        linkedMapOf(
            "ok" to true,
            "chat_id" to chat.chatId,
            "display_name" to chat.displayName,
            "file_restored" to fileRestored,
            "archived_count" to 0,
            "needs_confirmation" to false,
            "error" to null,
        )
    }

    /** `delete_chat` (android_api.py:491): remove a chat and its history entirely. */
    fun deleteChat(chatIdOrName: String): Map<String, Any?> = guarded {
        val r = ready()
        val chat = r.resolveChat(chatIdOrName)
            ?: return@guarded linkedMapOf(
                "ok" to false,
                "chat_id" to null,
                "display_name" to null,
                "error" to noChat(chatIdOrName),
            )
        r.deleteChat(chat.chatId)
        linkedMapOf("ok" to true, "chat_id" to chat.chatId, "display_name" to chat.displayName, "error" to null)
    }

    // ------------------------------------------------------------------
    // Per-chat cutoff override
    // ------------------------------------------------------------------

    /**
     * `_cutoff_chat_id` (android_api.py:527): resolve to a chat_id, falling back to the argument
     * as given. An unknown chat is not an error: a cutoff can be set from the import preview,
     * before the chat has ever synced.
     */
    private fun cutoffChatId(r: StateRepository, chatIdOrName: String): String =
        r.resolveChat(chatIdOrName)?.chatId ?: chatIdOrName

    /** `get_cutoff` (android_api.py:541): `{"chat_id", "cutoff_date"}`, the date as a plain day. */
    fun getCutoff(chatIdOrName: String): Map<String, Any?> = guarded {
        val r = ready()
        val chatId = cutoffChatId(r, chatIdOrName)
        val stored = r.getChatCutoff(chatId)
        linkedMapOf("chat_id" to chatId, "cutoff_date" to (if (!stored.isNullOrEmpty()) stored.take(10) else null))
    }

    /** `set_cutoff` (android_api.py:557): set this chat's own cutoff, or clear it when empty. */
    fun setCutoff(chatIdOrName: String, cutoffDate: String? = null): Map<String, Any?> = guarded {
        val r = ready()
        val chatId = cutoffChatId(r, chatIdOrName)
        val text = pythonStrip(cutoffDate ?: "")
        if (text.isEmpty()) {
            r.clearChatCutoff(chatId)
            return@guarded linkedMapOf("ok" to true, "chat_id" to chatId, "cutoff_date" to null, "error" to null)
        }
        try {
            r.setChatCutoff(chatId, text)
        } catch (_: IllegalArgumentException) {
            return@guarded linkedMapOf(
                "ok" to false,
                "chat_id" to chatId,
                "cutoff_date" to null,
                "error" to clean("${pyRepr(clean(cutoffDate ?: ""))} is not a date. Use YYYY-MM-DD."),
            )
        }
        linkedMapOf("ok" to true, "chat_id" to chatId, "cutoff_date" to text.take(10), "error" to null)
    }

    /** `list_cutoffs` (android_api.py:589): every per-chat override as `[{"chat_id", "cutoff_date"}]`. */
    fun listCutoffs(): List<Map<String, Any?>> = guarded {
        ready().listChatCutoffs().entries
            .sortedWith { a, b -> CodePointOrder.compare(a.key, b.key) }
            .map { linkedMapOf<String, Any?>("chat_id" to it.key, "cutoff_date" to it.value.take(10)) }
    }

    // ------------------------------------------------------------------
    // Which name in an export is yours
    // ------------------------------------------------------------------

    /** `get_self_sender` (android_api.py:617): name, source, summary, detail, override, learned. */
    fun getSelfSender(): Map<String, Any?> = guarded {
        val r = ready()
        val override = r.getAppState(StateRepository.SELF_SENDER_OVERRIDE)
        val learned = r.getAppState(StateRepository.SELF_SENDER_LEARNED)
        val d = describe(override, learned)
        linkedMapOf(
            "name" to d.name,
            "source" to d.source,
            "summary" to d.summary,
            "detail" to d.detail,
            "override" to override,
            "learned" to learned,
        )
    }

    /** `get_pending_self_sender_banner` (android_api.py:636): the name to announce on Home, or null. */
    fun getPendingSelfSenderBanner(): String? = guarded {
        ready().getAppState(StateRepository.SELF_SENDER_LEARNED_PENDING)
    }

    /** `clear_self_sender_banner` (android_api.py:647). */
    fun clearSelfSenderBanner() {
        guarded { ready().setAppState(StateRepository.SELF_SENDER_LEARNED_PENDING, null) }
    }

    /** `list_chat_senders` (android_api.py:653): senders seen in exports, most active first. */
    fun listChatSenders(chatId: String? = null): List<Map<String, Any?>> = guarded {
        ready().listChatSenders(chatId).map {
            linkedMapOf<String, Any?>(
                "chat_id" to it.chatId,
                "sender" to it.sender,
                "first_seen" to it.firstSeen,
                "last_seen" to it.lastSeen,
                "msg_count" to it.msgCount,
            )
        }
    }

    /** `set_self_sender` (android_api.py:663): set your name by hand, or clear it when empty. */
    fun setSelfSender(name: String? = null): Map<String, Any?> = guarded {
        ready().setAppState(StateRepository.SELF_SENDER_OVERRIDE, name)
        getSelfSender()
    }

    // ------------------------------------------------------------------
    // Device migration
    // ------------------------------------------------------------------

    /**
     * `export_backup` (android_api.py:688): write a restore bundle to [destPath]. Only the
     * allow-listed keys of [settingsJson] survive into the bundle; a credential passed in is
     * dropped. Keys on success: ok, path, bundle_id, counts, settings_keys.
     */
    fun exportBackup(destPath: String, settingsJson: String = "{}", appVersion: String = ""): Map<String, Any?> = guarded {
        var settings: Map<String, Any?> = emptyMap()
        try {
            val parsed = parseBundleJson(settingsJson.ifEmpty { "{}" })
            @Suppress("UNCHECKED_CAST")
            if (parsed is Map<*, *>) settings = parsed as Map<String, Any?>
        } catch (_: BundleJsonException) {
            // Unreadable settings are treated as none, as Python does.
        }
        try {
            val result = exportBundle(paths.projectRoot, File(destPath), openDb, settings, appVersion)
            linkedMapOf(
                "ok" to true,
                "path" to result.path,
                "bundle_id" to result.bundleId,
                "counts" to result.counts.toJson(),
                "settings_keys" to result.settingsKeys,
            )
        } catch (e: BundleError) {
            linkedMapOf("ok" to false, "error" to clean(e.message.orEmpty()))
        } catch (e: IOException) {
            linkedMapOf("ok" to false, "error" to clean("That backup could not be written: ${e.message.orEmpty()}"))
        }
    }

    /** `describe_backup` (android_api.py:711): what is in the bundle, for showing before restoring. */
    fun describeBackup(sourcePath: String): Map<String, Any?> = guarded {
        val notABackup = "That file is not a Chat Mail Sync backup."
        val manifest = try {
            readManifest(File(sourcePath))
        } catch (e: BundleError) {
            return@guarded linkedMapOf("ok" to false, "error" to clean(e.message.orEmpty()))
        }
        try {
            val counts = manifest["counts"].let { if (truthy(it)) it else emptyMap<String, Any?>() }
            if (counts !is Map<*, *>) throw IllegalArgumentException("counts")
            linkedMapOf(
                "ok" to true,
                "error" to null,
                "created_at" to textOrEmpty(manifest["created_at"]),
                "app_version" to textOrEmpty(manifest["app_version"]),
                "chats" to pyInt(counts["chats"]),
                "runs" to pyInt(counts["runs"]),
                "hashes" to pyInt(counts["hashes"]),
                // A bundle written before per-chat cutoffs existed has no such key and carries none.
                "cutoffs" to pyInt(counts["cutoffs"]),
            )
        } catch (_: IllegalArgumentException) {
            // Difference from Python: a manifest whose counts are not numbers made Python raise.
            linkedMapOf("ok" to false, "error" to notABackup)
        }
    }

    /**
     * `import_backup` (android_api.py:733): merge the bundle into this install. On success the
     * map also carries `settings_json` (the portable settings, JSON-encoded) and
     * `created_at_epoch_ms`.
     */
    fun importBackup(sourcePath: String): Map<String, Any?> = guarded {
        val result = importBundle(paths.projectRoot, File(sourcePath), openDb)
        if (!result.ok) {
            return@guarded linkedMapOf("ok" to false, "error" to clean(result.error.orEmpty()))
        }
        linkedMapOf(
            "ok" to true,
            "already_imported" to result.alreadyImported,
            "settings" to result.settings,
            "manifest" to result.manifest,
            "created_at" to result.createdAt,
            "chats_added" to result.chatsAdded,
            "runs_added" to result.runsAdded,
            "hashes_added" to result.hashesAdded,
            "cutoffs_added" to result.cutoffsAdded,
            "senders_added" to result.sendersAdded,
            "settings_json" to CoreApiJson.render(result.settings),
            "created_at_epoch_ms" to createdAtEpoch(result.createdAt) * 1000,
        )
    }
}

/**
 * What leaves [CoreApi] when a call fails. The text has the secret removed and no cause is
 * attached, so nothing wrapped can bring it back.
 */
class CoreApiException(message: String) : RuntimeException(message)

/** The JSON form of a [CoreApi] result: Python's key names, `json.dumps` value shapes. */
object CoreApiJson {
    fun render(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long -> sb.append(v.toString())
            is Double -> sb.append(if (v == Math.rint(v) && Math.abs(v) < 1e15) v.toString() else v.toString())
            is Number -> sb.append(v.toString())
            is String -> sb.append(jsonQuote(v))
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(", ")
                    first = false
                    sb.append(jsonQuote(k as String)).append(": ")
                    write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(", ")
                    first = false
                    write(sb, x)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("not a JSON value: ${v::class.simpleName}")
        }
    }
}

// ---------------------------------------------------------------------------
// Python semantics the facade leans on
// ---------------------------------------------------------------------------

/** Python truthiness for the loosely typed values a result map holds. */
private fun truthy(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is String -> v.isNotEmpty()
    is Number -> v.toDouble() != 0.0
    is Collection<*> -> v.isNotEmpty()
    is Map<*, *> -> v.isNotEmpty()
    else -> true
}

/** `str(v)` of a manifest value used as `v or ""`. */
private fun textOrEmpty(v: Any?): String = if (!truthy(v)) "" else if (v is String) v else v.toString()

/** `int(v or 0)` for a manifest count; a value Python could not turn into an int is refused. */
private fun pyInt(v: Any?): Long = when {
    !truthy(v) -> 0L
    v is Boolean -> 1L
    v is Long -> v
    v is Int -> v.toLong()
    v is Double -> v.toLong()
    v is String -> v.trim().toLongOrNull() ?: throw IllegalArgumentException("not a number")
    else -> throw IllegalArgumentException("not a number")
}

/** Python's `repr()` of a string, which the "No chat found" and "is not a date" texts embed. */
internal fun pyRepr(s: String): String {
    val quote = if ('\'' in s && '"' !in s) '"' else '\''
    val sb = StringBuilder().append(quote)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        i += Character.charCount(cp)
        when {
            cp == quote.code -> sb.append('\\').append(quote)
            cp == '\\'.code -> sb.append("\\\\")
            cp == 9 -> sb.append("\\t")
            cp == 10 -> sb.append("\\n")
            cp == 13 -> sb.append("\\r")
            cp < 0x20 || cp == 0x7F -> sb.append(String.format("\\x%02x", cp))
            cp < 0x7F -> sb.append(cp.toChar())
            isPyPrintable(cp) -> sb.appendCodePoint(cp)
            cp <= 0xFF -> sb.append(String.format("\\x%02x", cp))
            cp <= 0xFFFF -> sb.append(String.format("\\u%04x", cp))
            else -> sb.append(String.format("\\U%08x", cp))
        }
    }
    return sb.append(quote).toString()
}

private fun isPyPrintable(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
    Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
    Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR,
    -> false
    else -> true
}
