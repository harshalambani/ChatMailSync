package com.chatmailsync.core.mail

import java.io.File
import java.nio.file.Files

/**
 * Kotlin port of `SyncManager` and `ProgressSyncManager` from
 * `src/sync_manager.py`, merged into one class: the Python split exists only
 * because the progress subclass was added later, and the one production caller
 * (`android_api.sync`) always uses the subclass. Passing no [onProgress] and
 * the default [shouldStop] gives the plain base behaviour for events and
 * stopping; the pre-scan that sizes the progress bar runs either way.
 *
 * Nothing here is Android specific: the state store,
 * the mail transport and the two folders are handed in.
 *
 * Error text boundary: every string that leaves this class, whether stored in
 * `sync_runs.error_message`, put in [SyncStats.errors], sent to [onProgress] or
 * carried by the exception [run] can throw, has [redact] (the app password)
 * removed with [stripSecret] first. Python stored the raw exception text.
 */
class SyncManager(
    internal val repo: StateRepository,
    internal val transport: MailTransport?,
    internal val inboxDir: File,
    internal val processedDir: File,
    internal val chunkSize: ChunkSize = ChunkSize.Day,
    internal val dryRun: Boolean = false,
    internal val trigger: String = "manual",
    cutoffDate: String? = null,
    private val onProgress: ((Map<String, Any?>) -> Unit)? = null,
    internal val shouldStop: () -> Boolean = { false },
    /** The app password (or any secret) to remove from error text; never logged. */
    internal val redact: String? = null,
    internal val sleeper: Sleeper = SystemSleeper,
    /** Removes a delivered export from inbox/ (PRIV-03); injectable so tests can make it fail. */
    internal val exportDeleter: (File) -> Unit = { Files.delete(it.toPath()) },
    /** CUT-02: the running app's version, stamped into every archived email's index. */
    internal val appVersion: String = UNKNOWN_VERSION,
    /** BUG-05: hold an export that looks like a renamed chat until the user answers. Always on in the app. */
    internal val detectRenames: Boolean = true,
) {
    /** The app-wide floor; a per-chat override beats it for that chat. */
    internal val cutoffDate: String? = normaliseCutoff(cutoffDate)

    private var filesTotal = 0
    private var filesDone = 0
    private var totalNewMessages = 0
    private var priorMsgsDone = 0

    /** What parsing and filtering one file decided; the pre-scan caches these. */
    private class Planned(
        val all: List<ParsedMessage>,
        val new: List<ParsedMessage>,
        val skipped: Int,
        val cutoff: Int,
    )

    private val prescanCache = HashMap<String, Planned>()

    /** BUG-05: per export path, whether it waits on a rename question. Decided once per run. */
    private val heldCache = HashMap<String, Boolean>()

    /** The chat an export belongs to: its own id unless the user confirmed a rename. */
    private fun effectiveId(info: ChatInfo): String = repo.resolveChatId(info.chatId)

    /**
     * BUG-05: true when [file] looks like a renamed chat that is already archived under another
     * name and the user has not answered yet. Such a file is held in the inbox untouched: sending
     * it now would mail the whole history again under a second chat.
     */
    private fun isHeld(file: File, info: ChatInfo): Boolean =
        detectRenames && heldCache.getOrPut(file.path) {
            try {
                findLinkProposals(repo, file, info).isNotEmpty()
            } catch (_: Exception) {
                false
            }
        }

    /**
     * The name to show for [info]: the export's own name, except an export still carrying the
     * chat's OLD name after a confirmed rename, which must not rename the chat back.
     */
    private fun shownName(info: ChatInfo, chatId: String): String {
        if (info.chatId == chatId && repo.hasAliases(chatId)) {
            repo.getChat(chatId)?.let { return it.displayName }
        }
        return info.displayName
    }

    /**
     * Dedup baseline: the newest non-empty time across completed runs (BUG-07). A chat that has
     * names linked into it also honours the newest archived message, because the other party's
     * label changed with the rename and so those messages carry new fingerprints.
     */
    private fun baselineTs(chatId: String): String? {
        val runTs = repo.getLastSyncedTs(chatId)
        if (!repo.hasAliases(chatId)) return runTs
        val archived = repo.newestArchivedTs(chatId)
        return listOfNotNull(runTs, archived).maxOrNull()
    }

    init {
        repo.initDb()
        processedDir.mkdirs()
        // PRIV-03: clear the copies older versions kept in processed/. A real run only (a
        // dry run deletes nothing); a failure here never fails startup or a sync.
        if (!dryRun) {
            try {
                sweepKeptCopies(processedDir, inboxDir)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * PRIV-03: delete the export of a run that just completed. Never fails the run: mail is
     * already delivered and recorded, so if the file cannot be removed it stays in inbox/ and
     * the next run re-parses it (the hash check sends nothing twice). The only trace is one
     * progress event, `export_not_deleted`, with no file name, chat name or error text.
     */
    internal fun removeDeliveredExport(file: File) {
        if (dryRun) return
        try {
            if (!deleteCompletedExport(file, inboxDir, exportDeleter)) emit(mapOf("type" to "export_not_deleted"))
        } catch (_: Exception) {
            emit(mapOf("type" to "export_not_deleted"))
        }
    }

    // ------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------

    /**
     * Run one full incremental sync. [chatFilter], when given, limits the run
     * to chats whose id or display name equals it (case-insensitive).
     *
     * A failure of one file is recorded and the run goes on. Anything else
     * (a broken store, a folder that cannot be written) ends the run with a
     * [SyncAbortedException] whose text has [redact] removed and which carries
     * no cause, so no wrapped exception can bring the secret back.
     */
    fun run(chatFilter: String? = null): SyncStats {
        try {
            return runInner(chatFilter)
        } catch (e: Exception) {
            throw SyncAbortedException(clean("${e::class.simpleName}: ${e.message.orEmpty()}"))
        }
    }

    private fun runInner(chatFilter: String?): SyncStats {
        val files = listInboxFiles(inboxDir)
        filesTotal = files.size
        emit(mapOf("type" to "files_total", "n" to filesTotal))

        // Local-only pre-scan (parse and dedup, no network) so the progress
        // screen can show one real percentage for the whole sync's workload.
        totalNewMessages = estimateTotalNewMessages(files, chatFilter)
        priorMsgsDone = 0
        emit(mapOf("type" to "total_messages", "n" to totalNewMessages))

        val stats = SyncStats()

        // Step 1: recover interrupted runs before starting new ones.
        stats.chatsRecovered = recoverPending(stats)

        // Step 2: sync every file currently in the inbox.
        val inbox = listInboxFiles(inboxDir)
        stats.filesFound = inbox.size
        if (inbox.isEmpty()) return stats

        for (file in inbox) {
            val info = extractChatInfo(file.name)
            val chatId = effectiveId(info)
            if (!matchesFilter(chatFilter, info, chatId)) continue
            if (isHeld(file, info)) {
                // BUG-05: waits for the user's answer; left in the inbox, nothing sent.
                stats.filesHeld += 1
                emit(mapOf("type" to "link_pending"))
                continue
            }
            syncFile(file, chatId, shownName(info, chatId), stats)
        }
        return stats
    }

    private fun matchesFilter(chatFilter: String?, info: ChatInfo, chatId: String): Boolean =
        matchesChatFilter(chatFilter, info.chatId, info.displayName) ||
            (chatId != info.chatId && matchesChatFilter(chatFilter, chatId, info.displayName))

    /** Finish the runs a crash left pending; see SyncRecovery.kt. */
    internal fun recoverPending(stats: SyncStats): Int = recoverPendingRuns(stats)

    // ------------------------------------------------------------------
    // Pre-scan
    // ------------------------------------------------------------------

    private fun estimateTotalNewMessages(files: List<File>, chatFilter: String?): Int {
        var total = 0
        for (file in files) {
            val info = extractChatInfo(file.name)
            val chatId = effectiveId(info)
            if (!matchesFilter(chatFilter, info, chatId)) continue
            try {
                if (isHeld(file, info)) continue
                val lastTs = baselineTs(chatId)
                val planned = parseAndFilter(file, chatId, lastTs)
                prescanCache[file.path] = planned
                total += planned.new.size
            } catch (_: Exception) {
                continue
            }
        }
        return total
    }

    private fun parseAndFilter(file: File, chatId: String, lastSyncedTs: String?): Planned {
        val all = parseFile(file, chatId).toList()
        val f = filterMessages(repo, all, chatId, lastSyncedTs, cutoffDate)
        return Planned(all, f.newMessages, f.skipped, f.cutoff)
    }

    // ------------------------------------------------------------------
    // File-level sync
    // ------------------------------------------------------------------

    private fun syncFile(file: File, chatId: String, displayName: String, stats: SyncStats) {
        // A stop request is honoured here (the file is never started) and again
        // before every email of the push (BUG-01), so Cancel does not wait for a
        // whole export.
        if (shouldStop()) return
        emit(mapOf("type" to "syncing", "name" to displayName))
        // Read before the work runs: parseOrCached() removes the entry.
        val planned = prescanCache[file.path]?.new?.size ?: 0
        syncFileCore(file, chatId, displayName, stats)
        filesDone += 1
        // Messages accounted for, not messages delivered: a file that failed
        // its push still used up its share of the pre-scan total, so keying the
        // bar off messagesSynced alone would leave it short for the rest of the
        // run. max() so a file that delivered more than planned still advances.
        priorMsgsDone = maxOf(stats.messagesSynced, priorMsgsDone + planned)
        emit(mapOf("type" to "file_done", "done" to filesDone, "total" to filesTotal))
    }

    private fun syncFileCore(file: File, chatId: String, displayName: String, stats: SyncStats) {
        if (!dryRun) {
            repo.upsertChat(chatId, displayName, file.name)
            // BUG-05: upsert never touches the name, so a rename (or a capitals-only change)
            // would leave the app showing the old one. Update it here.
            val existing = repo.getChat(chatId)
            if (existing != null && existing.displayName != displayName) {
                repo.setChatDisplayName(chatId, displayName, file.name)
            }
        }

        val lastTs = baselineTs(chatId)

        var runId: Long? = null
        if (!dryRun) runId = repo.startSyncRun(chatId, trigger)

        val planned = try {
            prescanCache.remove(file.path) ?: parseAndFilter(file, chatId, lastTs)
        } catch (exc: Exception) {
            val raw = exc.message.orEmpty()
            stats.filesFailed += 1
            stats.errors.add(clean("${file.name}: parse error — ${scrubPaths(clean(raw))}"))
            if (runId != null) repo.failSyncRun(runId, clean(raw))
            return
        }
        val all = planned.all
        val new = planned.new
        val nSkipped = planned.skipped
        val nCutoff = planned.cutoff

        stats.messagesParsed += all.size
        stats.messagesSkipped += nSkipped
        stats.messagesCutoff += nCutoff

        // BUG-12: an export that reads as zero messages is NOT a finished sync. It is kept in
        // inbox/, counted as a failed file and reported, never deleted without a word.
        if (all.isEmpty()) {
            stats.filesFailed += 1
            stats.errors.add(clean("${file.name}: no messages found - kept in the inbox"))
            if (runId != null) repo.failSyncRun(runId, "no messages found in this export")
            return
        }

        // D29: a renamed chat's server folder follows its new name even when nothing is sent now.
        if (!dryRun) renameFolderIfNeeded(chatId, displayName)

        // Nothing new to push (everything already sent, or before the cutoff).
        if (new.isEmpty()) {
            stats.filesSkipped += 1
            if (runId != null) {
                repo.completeSyncRun(runId, lastTs, null, all.size, 0, nSkipped, nCutoff)
                recordChatSendersFor(repo, chatId, all, emptyList())
            }
            removeDeliveredExport(file)
            return
        }

        // Dry run: report and stop here, nothing is written or sent.
        if (dryRun) {
            stats.filesSynced += 1
            stats.messagesSynced += new.size
            return
        }
        val rid = runId ?: error("no run row in a real run")

        // Cached mail ids. The columns are still named gmail_*; on the IMAP
        // backend they hold that transport's folder and thread identifiers.
        val chatRow = repo.getChat(chatId)
        var labelId = chatRow?.gmailLabelId
        var threadId = chatRow?.gmailThreadId
        val anchorMid = chatRow?.anchorMessageId

        // Hashes are recorded per chunk as each one is confirmed delivered, not
        // in one insert after the push: if the process dies partway, the store
        // then matches exactly what reached the mailbox, and a recovery run does
        // not re-push the whole chat.
        //
        // BUG-11: the same transaction also saves the chat's mail ids (thread, label,
        // first anchor) and this email's sender counts, so a push that fails or is
        // stopped at email k resumes in the SAME thread under the SAME anchor, and the
        // first k emails' senders are already counted.
        var deliveredMsgs = 0
        var accepted: PushResult? = null
        var acceptedLabel: String? = null
        val onAccepted: OnAccepted = { r, label -> accepted = r; acceptedLabel = label }
        val onChunk: OnChunk = { i, total, done, tmsgs, chunkMsgs ->
            deliveredMsgs = done
            repo.recordChunkDelivered(
                chatId, buildHashEntries(chunkMsgs, rid), senderCounts(chunkMsgs),
                accepted?.threadId, acceptedLabel, accepted?.messageId,
            )
            onChunkProgress(displayName, i, total, done, tmsgs)
        }

        val results: List<PushResult>
        var pushedStopped = false
        try {
            val mail = transport ?: throw IllegalStateException("no mail transport configured")
            val pushed = pushChat(
                transport = mail,
                displayName = displayName,
                messages = new,
                chunkSize = chunkSize,
                labelId = labelId,
                anchorMessageId = anchorMid,
                threadId = threadId,
                dryRun = false,
                sourcePath = file,
                onChunk = onChunk,
                onAccepted = onAccepted,
                selfSender = resolveSelfSenderFor(repo, displayName, all),
                sleeper = sleeper,
                shouldStop = shouldStop,
                appVersion = appVersion,
                onFolderRenamed = { ok -> emit(mapOf("type" to "folder_renamed", "ok" to ok)) },
                allowFolderRename = chatId != LEGACY_CHAT_ID,
            )
            results = pushed.results
            labelId = pushed.labelId
            threadId = pushed.threadId
            pushedStopped = pushed.stopped
        } catch (exc: Exception) {
            val raw = exc.message.orEmpty()
            stats.filesFailed += 1
            stats.errors.add(clean("$displayName: Mail push failed — ${scrubPaths(clean(raw))}"))
            repo.failSyncRun(rid, clean(raw))
            return
        }

        collectOmissions(stats, displayName, results)

        // Persist mail ids (thread id, and the anchor Message-ID from the first chunk).
        // BUG-11: each email already saved these; this end-of-push write is kept as an
        // idempotent safety net (same values on a clean run).
        val newAnchor = if (anchorMid == null && results.isNotEmpty()) results[0].messageId else anchorMid
        if (!pushedStopped || results.isNotEmpty()) {
            repo.updateChatGmailIds(chatId, threadId, labelId, newAnchor)
        }

        if (pushedStopped) {
            // Cancelled between two emails (BUG-01). Left exactly as an interrupted
            // run is: the emails already sent have their hashes recorded, the run
            // is closed as not complete, and the file stays in the inbox, so the
            // next run sends only what is still missing. Not a failure of the
            // file, so no error is added.
            stats.messagesSynced += deliveredMsgs
            // BUG-15: kept as a stopped run with its real count, not a failed one.
            repo.stopSyncRun(rid, deliveredMsgs, new.size)
            return
        }

        val last = new.last()
        val lastHash = StateRepository.computeMessageHash(last.chatId, last.timestampIso, last.sender, last.body)
        repo.completeSyncRun(rid, last.timestampIso, lastHash, all.size, new.size, nSkipped, nCutoff)
        // BUG-11: pushed counts were added per email; only the zero-count rows for
        // senders with nothing new are ensured here (INSERT OR IGNORE).
        recordChatSendersFor(repo, chatId, all, emptyList())

        // Delete the export only after everything succeeded (PRIV-03).
        removeDeliveredExport(file)

        stats.filesSynced += 1
        stats.messagesSynced += new.size
    }

    /**
     * D29: when the shown name no longer matches the stored folder, rename the folder now. A
     * refusal keeps the old folder (nothing is created, nothing lost). Never fails the sync:
     * the push repeats the attempt, and a network problem surfaces there.
     */
    private fun renameFolderIfNeeded(chatId: String, displayName: String) {
        val mail = transport ?: return
        if (chatId == LEGACY_CHAT_ID) return
        val stored = repo.getChat(chatId)?.gmailLabelId ?: return
        if (!stored.startsWith("$LABEL_PARENT/") || stored == fullLabelName(displayName)) return
        try {
            val label = folderForPush(mail, stored, displayName, { ok -> emit(mapOf("type" to "folder_renamed", "ok" to ok)) })
            if (label != stored) repo.updateChatGmailIds(chatId, null, label, null)
        } catch (_: Exception) {
        }
    }

    private fun onChunkProgress(displayName: String, chunk: Int, totalChunks: Int, msgsDone: Int, totalMsgs: Int) {
        emit(
            mapOf(
                "type" to "chunk",
                "name" to displayName,
                "chunk" to chunk,
                "total_chunks" to totalChunks,
                "msgs_done" to msgsDone,
                "total_msgs" to totalMsgs,
                "global_done" to priorMsgsDone + msgsDone,
                "global_total" to totalNewMessages,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Boundary helpers
    // ------------------------------------------------------------------

    /** [text] with [redact] removed. Everything stored or surfaced goes through this. */
    internal fun clean(text: String): String = stripSecret(text, redact)

    private fun emit(event: Map<String, Any?>) {
        val sink = onProgress ?: return
        sink(event.mapValues { (_, v) -> if (v is String) clean(v) else v })
    }
}

/**
 * Thrown by [SyncManager.run] when a failure is not one file's own: the text
 * has the app password removed and no cause is attached.
 */
class SyncAbortedException(message: String) : RuntimeException(message)
