package com.chatmailsync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX-08: the Chats footer adds up each chat's total filed, not the last runs. */
class ChatTotalFooterTest {

    private fun chat(id: String, name: String, last: Long, total: Long) = ChatSummary(
        chatId = id, displayName = name, hasThread = true, gmailThreadId = null,
        lastRunStatus = "complete", messagesSynced = last, totalFiled = total,
        lastRunAt = null, sourceFilename = null,
    )

    @Test
    fun footerSumsTheTotalsNotTheLastRuns() {
        val chats = listOf(
            chat("meera_iyer", "Meera Iyer", last = 3, total = 5),
            chat("rohan_mehta", "Rohan Mehta", last = 0, total = 2),
        )
        val text = formatFooterStats(chats)
        assertTrue(text, "7 messages synced" in text)
        // NEGATIVE: the sum of the last runs (3) is not what the footer shows.
        assertFalse(text, "3 messages synced" in text)
    }
}
