package com.chatmailsync.core.mail

import java.io.File

/**
 * Port of `_recover_pending` / `_recover_run` from `src/sync_manager.py`:
 * finish the runs a crash left in the `pending` state before any new file is
 * started.
 *
 * Python behaviour kept on purpose, not as a fix (each is named in a test):
 *  - recovery does not apply the overlap rule (`last_synced_ts`); only the
 *    hash check and the cutoff decide what is still to send;
 *  - recovery does not add to `filesSynced` or `messagesParsed`, only to
 *    `messagesSynced` / `messagesCutoff` / `filesFailed` / the omission lists.
 */
internal fun SyncManager.recoverPendingRuns(stats: SyncStats): Int {
    val pending = repo.getPendingRuns()
    if (pending.isEmpty()) return 0
    var recovered = 0
    for (run in pending) {
        if (recoverRun(run, stats)) recovered += 1
    }
    return recovered
}

private fun SyncManager.recoverRun(run: SyncRun, stats: SyncStats): Boolean {
    val runId = run.runId
    val chatId = run.chatId

    val chat = repo.getChat(chatId)
    if (chat == null) {
        repo.failSyncRun(runId, "chat record missing")
        return false
    }
    val sourceFile = File(inboxDir, chat.sourceFilename)
    if (!sourceFile.exists()) {
        repo.failSyncRun(runId, clean("source file not found: ${chat.sourceFilename}"))
        return false
    }

    // Hashes already pushed in this interrupted run.
    val alreadyPushed = repo.getHashesForRun(runId)

    val allMessages: List<ParsedMessage>
    try {
        allMessages = parseFile(sourceFile, chatId).toList()
    } catch (exc: Exception) {
        repo.failSyncRun(runId, clean("parse error: ${exc.message.orEmpty()}"))
        stats.filesFailed += 1
        return false
    }

    // Remaining = not yet pushed in this run AND not in any earlier run AND not
    // below the cutoff (a cutoff set after the run was interrupted still holds).
    val cutoff = effectiveCutoff(repo, chatId, cutoffDate)
    val remaining = ArrayList<ParsedMessage>()
    var nCutoff = 0
    for (msg in allMessages) {
        val h = StateRepository.computeMessageHash(msg.chatId, msg.timestampIso, msg.sender, msg.body)
        if (h in alreadyPushed || repo.hashExists(h)) continue
        if (cutoff != null && cutoff.isNotEmpty() && msg.timestampIso < cutoff) {
            nCutoff += 1
            continue
        }
        remaining.add(msg)
    }

    val priorSynced = run.messagesSynced
    stats.messagesCutoff += nCutoff

    // Nothing left: close the run and move the file.
    if (remaining.isEmpty()) {
        repo.completeSyncRun(
            runId,
            // This run may have pushed nothing of its own, so carry the chat's
            // previous time forward instead of closing the run with a blank one.
            run.lastSyncedTs?.takeIf { it.isNotEmpty() } ?: repo.getLastSyncedTs(chatId),
            run.lastSyncedHash,
            allMessages.size,
            priorSynced,
            allMessages.size - priorSynced - nCutoff,
            nCutoff,
        )
        recordChatSendersFor(repo, chatId, allMessages, emptyList())
        moveToProcessed(sourceFile, processedDir)
        return true
    }

    if (dryRun) return true

    // Hashes are recorded per chunk, so a second interruption loses nothing.
    val onChunk: OnChunk = { _, _, _, _, chunkMsgs ->
        repo.insertMessageHashes(buildHashEntries(chunkMsgs, runId))
    }

    val results: List<PushResult>
    val labelId: String?
    val threadId: String?
    try {
        val mail = transport ?: throw IllegalStateException("no mail transport configured")
        val pushed = pushChat(
            transport = mail,
            displayName = chat.displayName,
            messages = remaining,
            chunkSize = chunkSize,
            labelId = chat.gmailLabelId,
            anchorMessageId = chat.anchorMessageId,
            threadId = chat.gmailThreadId,
            dryRun = false,
            sourcePath = sourceFile,
            onChunk = onChunk,
            selfSender = resolveSelfSenderFor(repo, chat.displayName, allMessages),
            sleeper = sleeper,
        )
        results = pushed.results
        labelId = pushed.labelId
        threadId = pushed.threadId
    } catch (exc: Exception) {
        repo.failSyncRun(runId, clean("recovery push failed: ${exc.message.orEmpty()}"))
        stats.filesFailed += 1
        return false
    }

    collectOmissions(stats, chat.displayName, results)

    val newAnchor =
        if (chat.anchorMessageId.isNullOrEmpty() && results.isNotEmpty()) results[0].messageId else chat.anchorMessageId
    repo.updateChatGmailIds(chatId, threadId, labelId, newAnchor)

    val totalSynced = priorSynced + remaining.size
    val last = remaining.last()
    val lastHash = StateRepository.computeMessageHash(last.chatId, last.timestampIso, last.sender, last.body)
    repo.completeSyncRun(
        runId,
        last.timestampIso,
        lastHash,
        allMessages.size,
        totalSynced,
        allMessages.size - totalSynced - nCutoff,
        nCutoff,
    )
    recordChatSendersFor(repo, chatId, allMessages, remaining)
    moveToProcessed(sourceFile, processedDir)
    stats.messagesSynced += remaining.size
    return true
}
