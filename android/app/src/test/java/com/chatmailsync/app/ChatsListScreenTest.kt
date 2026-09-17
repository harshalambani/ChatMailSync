package com.chatmailsync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch 5b item 10: a chat row on a OnePlus Nord (1080px, ~411dp at density
 * 420) still clipped its status/time text against the "N messages" count
 * once a longer chat name was in play. The main fix is weighting and
 * ellipsizing the flexible text so the row fits down to 360dp on its own;
 * chatRowShowsMessageCount() is a second, pure width decision pulled out
 * of the row Composable as a belt-and-braces drop of the numeric column
 * below that width, testable here without Compose or Robolectric.
 */
class ChatsListScreenTest {

    @Test
    fun `below the 360dp target width the message count is dropped`() {
        assertFalse(chatRowShowsMessageCount(359))
        assertFalse(chatRowShowsMessageCount(340))
    }

    @Test
    fun `at and above the 360dp target width, including a OnePlus Nord, it is kept`() {
        assertTrue(chatRowShowsMessageCount(411))
        assertTrue(chatRowShowsMessageCount(392))
    }

    @Test
    fun `the boundary itself keeps the message count`() {
        assertTrue(chatRowShowsMessageCount(ChatRowNarrowWidthDp))
        assertFalse(chatRowShowsMessageCount(ChatRowNarrowWidthDp - 1))
    }
}
