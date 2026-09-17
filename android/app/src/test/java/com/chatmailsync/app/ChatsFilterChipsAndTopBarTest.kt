package com.chatmailsync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two source-scan guards, neither exercisable through Robolectric-free unit
 * tests (Compose UI is not unit-testable here -- see FirstRunStepTitlesTest):
 *
 * 1. The Chats and Sync log filter-chip rows used to be a horizontally
 *    scrolling Row. At 384dp the last chip ("Never synced (0)") ran off the
 *    right edge; it was technically reachable by scrolling, but nothing on
 *    screen signalled that, so it read as a cut-off, broken row. Both rows
 *    now wrap onto a second line via FlowRow instead, so every chip is
 *    always fully visible. The negative half of this guard is the point:
 *    it is not enough that FlowRow appears somewhere, horizontalScroll must
 *    be gone from the chip rows specifically.
 *
 * 2. No top-level tab (Home, Chats, Settings) may call ChatMailTopBar with a
 *    labelled back affordance (backLabel/onBack) -- that would suppress the
 *    mark+wordmark badge, which ChatMailTopBar only hides when
 *    `labelledBack` is true, exactly for pushed detail screens. Home and
 *    Settings never passed these; Chats was checked here because it was the
 *    subject of a report (from a build that predates this fix) that its
 *    masthead showed no logo where Home's and Settings' did.
 */
class ChatsFilterChipsAndTopBarTest {

    private fun source(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative from ${File("").absolutePath}")
    }

    private fun kotlinSource(name: String) =
        source("app/src/main/java/com/chatmailsync/app/$name")

    // -- filter chips: FlowRow, not horizontalScroll -------------------------

    @Test
    fun `the Chats filter chip row uses FlowRow, not horizontalScroll`() {
        val text = kotlinSource("ChatsListScreen.kt")
        assertTrue("ChatsListScreen.kt no longer imports FlowRow", text.contains("FlowRow"))
        // Negative: horizontalScroll must not remain anywhere in the file --
        // the only prior use of it was the chip row this migrates.
        assertFalse(
            "ChatsListScreen.kt still references horizontalScroll",
            text.contains("horizontalScroll"),
        )
    }

    @Test
    fun `the Sync log filter chip row uses FlowRow, not horizontalScroll`() {
        val text = kotlinSource("SyncLogScreen.kt")
        assertTrue("SyncLogScreen.kt no longer imports FlowRow", text.contains("FlowRow"))
        assertFalse(
            "SyncLogScreen.kt still references horizontalScroll",
            text.contains("horizontalScroll"),
        )
        // Sync log's detail screen legitimately still scrolls vertically
        // (verticalScroll) -- only the horizontal chip-scrolling is gone.
        assertTrue(
            "SyncLogScreen.kt's detail screen should still scroll vertically",
            text.contains("verticalScroll"),
        )
    }

    // -- top bar: no tab may hide the logo ------------------------------------

    @Test
    fun `no top-level tab passes a labelled back affordance to ChatMailTopBar`() {
        for (name in listOf("HomeScreen.kt", "ChatsListScreen.kt", "SettingsScreen.kt")) {
            val text = kotlinSource(name)
            assertFalse(
                "$name passes backLabel to ChatMailTopBar, which would hide its logo",
                text.contains("backLabel ="),
            )
            assertFalse(
                "$name passes onBack to ChatMailTopBar, which would hide its logo",
                text.contains("onBack ="),
            )
        }
    }
}
