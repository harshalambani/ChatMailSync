package com.chatmailsync.core.mail

import java.io.File

/**
 * BUG-05 / BUG-06: a chat's identity is the name in its export's file name, and that name is
 * inside every message fingerprint. When the name changes (number -> saved name, a renamed
 * contact or group) the export looks like a brand new chat. This file finds the probable
 * rename from what the phone already stores and applies it once the user confirms.
 *
 * Privacy (D30): matching reads stored FINGERPRINTS only. The new export is hashed under each
 * candidate old chat id and the hashes are compared with the stored ones; no stored message
 * text exists or is read, and nothing here logs a name, a number or a message.
 */

/** The id the pre-2.10 app gave every export whose name had no ASCII letter or digit. */
const val LEGACY_CHAT_ID = "unknown_chat"

/**
 * Fewest identical archived messages that make a pair a probable rename. One shared message
 * could be a coincidence (a forwarded line); two with the same time, sender and text are not.
 */
const val MIN_LINK_MATCHES = 2

enum class LinkKind {
    /** The export is the same chat as [LinkProposal.oldChatId], under a new name. */
    RENAME,

    /** The export is one of the chats that used to share the legacy record. */
    LEGACY,
}

/** One question for the user: is [fileName] the chat already archived as [oldDisplayName]? */
data class LinkProposal(
    val newChatId: String,
    val newDisplayName: String,
    val oldChatId: String,
    val oldDisplayName: String,
    /** How many of the export's messages are already archived under the old chat. */
    val matched: Int,
    val kind: LinkKind,
    val fileName: String,
)

/**
 * Proposals for one export, best match first. Empty when the export is not a candidate: it already
 * belongs to a chat, it is already linked, or nothing archived matches it well enough. A pair the
 * user declined is never offered again.
 */
fun findLinkProposals(repo: StateRepository, file: File, info: ChatInfo): List<LinkProposal> {
    if (repo.isAlias(info.chatId) || repo.getChat(info.chatId) != null) return emptyList()
    val candidates = repo.listChats().filter {
        it.chatId != info.chatId && !repo.isAlias(it.chatId) && !repo.isLinkDeclined(info.chatId, it.chatId)
    }
    if (candidates.isEmpty()) return emptyList()

    val messages = parseFile(file, info.chatId).toList()
    if (messages.isEmpty()) return emptyList()

    val out = ArrayList<LinkProposal>()
    for (c in candidates) {
        val stored = repo.hashesForChat(c.chatId)
        if (stored.isEmpty()) continue
        val seen = HashSet<String>()
        for (m in messages) {
            val h = StateRepository.computeMessageHash(c.chatId, m.timestampIso, m.sender, m.body)
            if (h in stored) seen.add(h)
        }
        if (seen.size >= MIN_LINK_MATCHES) {
            out.add(
                LinkProposal(
                    newChatId = info.chatId,
                    newDisplayName = info.displayName,
                    oldChatId = c.chatId,
                    oldDisplayName = c.displayName,
                    matched = seen.size,
                    kind = if (c.chatId == LEGACY_CHAT_ID) LinkKind.LEGACY else LinkKind.RENAME,
                    fileName = file.name,
                ),
            )
        }
    }
    return out.sortedWith(compareByDescending<LinkProposal> { it.matched }.thenBy { it.oldChatId })
}

/** Every open question for the exports waiting in [inboxDir]. Read-only: nothing is written. */
fun scanLinkProposals(repo: StateRepository, inboxDir: File): List<LinkProposal> {
    val out = ArrayList<LinkProposal>()
    for (file in listInboxFiles(inboxDir)) {
        try {
            out.addAll(findLinkProposals(repo, file, extractChatInfo(file.name)))
        } catch (_: Exception) {
            // An unreadable export is reported by the sync itself, not here.
        }
    }
    return out
}

/**
 * Applies a confirmed link. A RENAME adds the alias, so the export is read under the old chat's
 * id: its fingerprints equal the stored ones, and cutoff, thread and history carry on. A LEGACY
 * link splits the chat out of the shared record (see [StateRepository.adoptFromLegacy]).
 * Returns how many fingerprints were carried over (RENAME: the matched count).
 */
fun applyLink(repo: StateRepository, proposal: LinkProposal, file: File): Int {
    return when (proposal.kind) {
        LinkKind.RENAME -> {
            repo.addChatLink(proposal.newChatId, proposal.oldChatId)
            proposal.matched
        }
        LinkKind.LEGACY -> {
            val stored = repo.hashesForChat(proposal.oldChatId)
            val entries = ArrayList<StateRepository.AdoptEntry>()
            val seen = HashSet<String>()
            for (m in parseFile(file, proposal.newChatId)) {
                val old = StateRepository.computeMessageHash(proposal.oldChatId, m.timestampIso, m.sender, m.body)
                if (old !in stored || !seen.add(old)) continue
                val new = StateRepository.computeMessageHash(proposal.newChatId, m.timestampIso, m.sender, m.body)
                entries.add(StateRepository.AdoptEntry(old, new, m.timestampIso))
            }
            repo.adoptFromLegacy(
                proposal.oldChatId, proposal.newChatId, proposal.newDisplayName, file.name, entries,
            )
        }
    }
}

/** Records "not the same chat": the pair is kept separate and never asked about again. */
fun declineLink(repo: StateRepository, proposal: LinkProposal) {
    repo.declineChatLink(proposal.newChatId, proposal.oldChatId)
}
