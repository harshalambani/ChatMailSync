package com.chatmailsync.core.mail

import java.io.File

/**
 * Kotlin port of `SyncManager` and `ProgressSyncManager` from
 * `src/sync_manager.py`, merged into one class: the Python split exists only
 * because the progress subclass was added later, and the one production caller
 * (`android_api.sync`) always uses the subclass. Passing no [onProgress] and
 * the default [shouldStop] gives the plain base behaviour for events and
 * stopping; the pre-scan that sizes the progress bar runs either way.
 *
 * Not wired into `:app`. Nothing here is Android specific: the state store,
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
    private val shouldStop: () -> Boolean = { false },
    /** The app password (or any secret) to remove from error text; never logged. */
    internal val redact: String? = null,
    internal val sleeper: Sleeper = SystemSleeper,
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

    init {
        repo.initDb()
        processedDir.mkdirs()
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
            if (!matchesChatFilter(chatFilter, info.chatId, info.displayName)) continue
            syncFile(file, info.chatId, info.displayName, stats)
        }
        return stats
    }

    /** Finish the runs a crash left pending; see SyncRecovery.kt. */
    internal fun recoverPending(stats: SyncStats): Int = recoverPendingRuns(stats)

    // ------------------------------------------------------------------
    // Pre-scan
    // ------------------------------------------------------------------

    private fun estimateTotalNewMessages(files: List<File>, chatFilter: String?): Int {
        var total = 0
        for (file in files) {
            val info = extractChatInfo(file.name)
            if (!matchesChatFilter(chatFilter, info.chatId, info.displayName)) continue
            try {
                val lastTs = repo.getLastSyncedTs(info.chatId)
                val planned = parseAndFilter(file, info.chatId, lastTs)
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
        // Honour a stop request between files only: the current file is never
        // started, so no partial state is written.
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
        if (!dryRun) repo.upsertChat(chatId, displayName, file.name)

        // Dedup baseline: the latest non-empty time across completed runs.
        val lastTs = repo.getLastSyncedTs(chatId)

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

        // Nothing new to push.
        if (new.isEmpty()) {
            stats.filesSkipped += 1
            if (runId != null) {
                repo.completeSyncRun(runId, lastTs, null, all.size, 0, nSkipped, nCutoff)
                recordChatSendersFor(repo, chatId, all, emptyList())
            }
            if (!dryRun) moveToProcessed(file, processedDir)
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
        val onChunk: OnChunk = { i, total, done, tmsgs, chunkMsgs ->
            repo.insertMessageHashes(buildHashEntries(chunkMsgs, rid))
            onChunkProgress(displayName, i, total, done, tmsgs)
        }

        val results: List<PushResult>
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
                selfSender = resolveSelfSenderFor(repo, displayName, all),
                sleeper = sleeper,
            )
            results = pushed.results
            labelId = pushed.labelId
            threadId = pushed.threadId
        } catch (exc: Exception) {
            val raw = exc.message.orEmpty()
            stats.filesFailed += 1
            stats.errors.add(clean("$displayName: Mail push failed — ${scrubPaths(clean(raw))}"))
            repo.failSyncRun(rid, clean(raw))
            return
        }

        collectOmissions(stats, displayName, results)

        // Persist mail ids (thread id, and the anchor Message-ID from the first chunk).
        val newAnchor = if (anchorMid == null && results.isNotEmpty()) results[0].messageId else anchorMid
        repo.updateChatGmailIds(chatId, threadId, labelId, newAnchor)

        val last = new.last()
        val lastHash = StateRepository.computeMessageHash(last.chatId, last.timestampIso, last.sender, last.body)
        repo.completeSyncRun(rid, last.timestampIso, lastHash, all.size, new.size, nSkipped, nCutoff)
        recordChatSendersFor(repo, chatId, all, new)

        // Move the file to processed/ only after everything succeeded.
        moveToProcessed(file, processedDir)

        stats.filesSynced += 1
        stats.messagesSynced += new.size
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
