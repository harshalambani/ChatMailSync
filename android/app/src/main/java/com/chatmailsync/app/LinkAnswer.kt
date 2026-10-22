package com.chatmailsync.app

/** BUG-17: what the user is told when saving a "same chat?" answer failed. Names no chat. */
internal const val LINK_ANSWER_FAILED_TEXT = "Couldn't save your answer. Please try again."

/**
 * BUG-17: runs [save] (confirm or decline) and says whether it worked. On failure it logs the
 * exception TYPE only through [log] (never the message: a file error carries the export's path,
 * which holds the chat name) and returns false, so the caller shows [LINK_ANSWER_FAILED_TEXT]. The
 * question is never dropped: the caller refreshes either way, and a failed answer leaves the
 * proposal open so the same question comes back.
 */
internal fun saveLinkAnswer(save: () -> Unit, log: (String) -> Unit): Boolean =
    try {
        save()
        true
    } catch (e: Exception) {
        log("link answer failed: ${e.javaClass.simpleName}")
        false
    }
