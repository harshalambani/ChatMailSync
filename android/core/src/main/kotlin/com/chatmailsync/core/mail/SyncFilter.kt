package com.chatmailsync.core.mail

import java.util.Locale

/**
 * Kotlin port of the decision half of `src/sync_manager.py`: which messages of
 * a parsed file are new, who "me" is, and what a pushed chunk records.
 *
 * Every function takes the [StateRepository] it reads and writes; nothing here
 * opens a mailbox or touches the file system. Not wired into `:app`.
 */

/** What [filterMessages] decided: the messages to push and the two counts. */
class FilterResult(
    val newMessages: List<ParsedMessage>,
    /** Deduped or overlapping: the app had already sent these. */
    val skipped: Int,
    /** Withheld because they predate the cutoff date. */
    val cutoff: Int,
)

/**
 * Mirrors `_effective_cutoff`: a chat's own override if it has one, otherwise
 * the app-wide cutoff, otherwise null. A per-chat row wins outright rather
 * than being combined with the global one (an override exists to ask for older
 * history than the app-wide floor allows).
 */
fun effectiveCutoff(repo: StateRepository, chatId: String, appCutoff: String?): String? =
    repo.getChatCutoff(chatId) ?: appCutoff

/**
 * Mirrors `_filter_messages`. Rules, applied in this order:
 *  1. hash already in message_hashes -> skipped (exact duplicate);
 *  2. timestamp <= [lastSyncedTs]    -> skipped (re-export overlap);
 *  3. timestamp <  cutoff            -> withheld (before the user's date).
 *
 * The order is load-bearing. A chat already synced to March with a January
 * cutoff must not be dragged back to January, so rule 2 runs first and the
 * later of the two always wins; a message failing both counts as skipped, not
 * withheld. Rule 2 is `<=` and rule 3 is `<` on purpose: [lastSyncedTs] names a
 * message already sent, the cutoff names an instant, and a message stamped
 * exactly at midnight of the chosen day is on the day the user asked for.
 */
fun filterMessages(
    repo: StateRepository,
    messages: List<ParsedMessage>,
    chatId: String,
    lastSyncedTs: String?,
    appCutoff: String?,
): FilterResult {
    val cutoff = effectiveCutoff(repo, chatId, appCutoff)
    val new = mutableListOf<ParsedMessage>()
    var skipped = 0
    var nCutoff = 0
    for (msg in messages) {
        val h = StateRepository.computeMessageHash(msg.chatId, msg.timestampIso, msg.sender, msg.body)
        if (repo.hashExists(h)) {
            skipped++
            continue
        }
        if (!lastSyncedTs.isNullOrEmpty() && msg.timestampIso <= lastSyncedTs) {
            skipped++
            continue
        }
        if (!cutoff.isNullOrEmpty() && msg.timestampIso < cutoff) {
            nCutoff++
            continue
        }
        new.add(msg)
    }
    return FilterResult(new, skipped, nCutoff)
}

/**
 * Mirrors the chat filter in `SyncManager.run`: matches when the lower-cased
 * filter equals the chat id or the lower-cased display name. A null or empty
 * filter matches every chat.
 */
fun matchesChatFilter(chatFilter: String?, chatId: String, displayName: String): Boolean {
    if (chatFilter.isNullOrEmpty()) return true
    val f = chatFilter.lowercase(Locale.ROOT)
    return f == chatId || f == displayName.lowercase(Locale.ROOT)
}

/**
 * Mirrors `_resolve_self_sender`: the owner's name for this chat, learned from
 * the file when it can be. Called with every parsed message of the file, not
 * just the new ones: the one-to-one rule needs to see both people speak.
 */
fun resolveSelfSenderFor(repo: StateRepository, displayName: String, messages: List<ParsedMessage>): String {
    val override = repo.getAppState(StateRepository.SELF_SENDER_OVERRIDE)
    val learned = repo.getAppState(StateRepository.SELF_SENDER_LEARNED)

    val resolved = resolve(
        override = override,
        learned = learned,
        displayName = displayName,
        senders = messages.map { it.sender },
    )

    val derived = resolved.newlyDerived
    if (derived != null) {
        repo.setAppState(StateRepository.SELF_SENDER_LEARNED, derived)
        if (!(override != null && pythonStrip(override).isNotEmpty())) {
            repo.setAppState(StateRepository.SELF_SENDER_LEARNED_PENDING, derived)
        }
    }
    return resolved.name
}

/**
 * Mirrors `_record_chat_senders`: tally per-sender counts over every parsed
 * message, with the exact pushed count for senders that contributed and 0 for
 * the rest. Best effort: a failure here never fails a sync, so any exception
 * is swallowed (Python logs it and drops it).
 */
fun recordChatSendersFor(
    repo: StateRepository,
    chatId: String,
    allMessages: List<ParsedMessage>,
    pushedMessages: List<ParsedMessage>,
) {
    if (allMessages.isEmpty()) return
    try {
        val pushedCounts = pushedMessages.groupingBy { it.sender }.eachCount()
        val counts = LinkedHashMap<String, Int>()
        for (m in allMessages) counts.getOrPut(m.sender) { pushedCounts[m.sender] ?: 0 }
        repo.recordChatSenders(chatId, counts)
    } catch (_: Exception) {
        // best effort, see above
    }
}

/** Mirrors `_build_hash_entries`. */
fun buildHashEntries(messages: List<ParsedMessage>, runId: Long): List<StateRepository.HashEntry> =
    messages.map {
        StateRepository.HashEntry(
            hash = StateRepository.computeMessageHash(it.chatId, it.timestampIso, it.sender, it.body),
            chatId = it.chatId,
            messageTs = it.timestampIso,
            runId = runId,
        )
    }
