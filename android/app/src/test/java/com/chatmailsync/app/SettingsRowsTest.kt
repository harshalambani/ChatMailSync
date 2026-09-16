package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D8 split Settings into a Basic screen (everyday decisions) and an
 * Advanced screen (everything else, moved one tap deeper — nothing
 * dropped). These lists are what each screen renders, so a regression
 * here is a row silently vanishing or drifting to the wrong screen.
 *
 * Plain JUnit: the lists are constants, no Compose or Robolectric needed.
 */
class SettingsRowsTest {

    @Test
    fun `basic settings holds exactly the six everyday rows`() {
        assertEquals(
            listOf("mail_account", "me", "theme", "backup_restore", "help_about", "advanced"),
            BASIC_SETTINGS_ROWS.map { it.id },
        )
    }

    @Test
    fun `basic settings does not hold any advanced-only row`() {
        val advancedIds = ADVANCED_SETTINGS_ROWS.map { it.id }.toSet()
        val basicIds = BASIC_SETTINGS_ROWS.map { it.id }.toSet()
        assertTrue(basicIds.intersect(advancedIds).isEmpty())
        // Specifically the items the task moved off Home/Settings' first
        // screen — the regression this guards is one of them quietly
        // staying behind on Basic instead of moving.
        assertFalse(basicIds.contains("watched_folder"))
        assertFalse(basicIds.contains("cutoff_date"))
        assertFalse(basicIds.contains("chunk_size"))
        assertFalse(basicIds.contains("sync_log"))
    }

    @Test
    fun `advanced settings holds every moved row, nothing lost`() {
        assertEquals(
            listOf(
                "watched_folder",
                "auto_import",
                "watch_interval",
                "after_import",
                "cutoff_date",
                "test_run",
                "sync_log",
                "chunk_size",
                "test_connection",
            ),
            ADVANCED_SETTINGS_ROWS.map { it.id },
        )
    }

    @Test
    fun `no row id appears on both screens`() {
        val basicIds = BASIC_SETTINGS_ROWS.map { it.id }
        val advancedIds = ADVANCED_SETTINGS_ROWS.map { it.id }
        assertTrue((basicIds + advancedIds).toSet().size == basicIds.size + advancedIds.size)
    }
}
