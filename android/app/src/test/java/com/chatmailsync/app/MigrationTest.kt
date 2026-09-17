package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Migration.backupPillState] is the pure function behind Settings' new
 * "Backup & restore" status pill (batch 7). It reuses
 * [Migration.backupIsStale]'s existing >30-day rule rather than a second
 * copy of it, so these tests focus on the three-way label/tone split and
 * the boundary that rule already owns.
 *
 * Plain JUnit: no Compose, no Context, no Robolectric needed.
 */
class BackupPillStateTest {

    private val now = 1_700_000_000_000L // an arbitrary fixed instant
    private val oneDayMs = 24L * 60L * 60L * 1000L

    @Test
    fun `no backup ever taken shows No backup in the bad tone`() {
        val info = Migration.backupPillState(0L, now)
        assertEquals("No backup", info.label)
        assertEquals(BackupPillTone.BAD, info.tone)
    }

    @Test
    fun `negative timestamp is treated the same as never backed up`() {
        val info = Migration.backupPillState(-1L, now)
        assertEquals("No backup", info.label)
        assertEquals(BackupPillTone.BAD, info.tone)
    }

    @Test
    fun `a fresh backup from today shows a dated label in the good tone`() {
        val info = Migration.backupPillState(now, now)
        assertTrue(
            "expected a dated \"Backed up ...\" label, got \"${info.label}\"",
            info.label.startsWith("Backed up "),
        )
        assertEquals(BackupPillTone.GOOD, info.tone)
        // Negative: a fresh backup must not be reported as due or missing.
        assertFalse(info.label == "Backup due")
        assertFalse(info.label == "No backup")
    }

    @Test
    fun `a backup 29 days old is still fresh, not due`() {
        val info = Migration.backupPillState(now - 29L * oneDayMs, now)
        assertEquals(BackupPillTone.GOOD, info.tone)
        assertTrue(info.label.startsWith("Backed up "))
    }

    @Test
    fun `a backup exactly 30 days old is still fresh -- the boundary is strictly greater-than`() {
        // Mirrors Migration.backupIsStale's own strict ">" -- exactly 30
        // days is the last day it counts as fresh, not the first stale one.
        val info = Migration.backupPillState(now - 30L * oneDayMs, now)
        assertEquals(BackupPillTone.GOOD, info.tone)
        assertTrue(info.label.startsWith("Backed up "))
        assertFalse(info.label == "Backup due")
    }

    @Test
    fun `a backup 31 days old is due, in the warn tone`() {
        val info = Migration.backupPillState(now - 31L * oneDayMs, now)
        assertEquals("Backup due", info.label)
        assertEquals(BackupPillTone.WARN, info.tone)
        // Negative: stale must not still claim to be dated/fresh.
        assertFalse(info.label.startsWith("Backed up "))
    }

    @Test
    fun `a backup dated in the future -- clock skew -- is not stale and does not crash`() {
        val info = Migration.backupPillState(now + oneDayMs, now)
        assertEquals(BackupPillTone.GOOD, info.tone)
        assertTrue(info.label.startsWith("Backed up "))
        assertFalse(info.label == "Backup due")
        assertFalse(info.label == "No backup")
    }
}

/**
 * [restoreSummary] is the pure function behind batch 7b's restore
 * confirmation: "Chats: N" / "Messages already sent: M" plus whatever
 * settings the bundle actually carried, translated through the same label
 * maps Settings/Advanced already use, and a fixed "Not restored:" list.
 *
 * Plain JUnit: no Context, no Python, no Compose. [providerLabels] and
 * [appliedSettings] stand in for what a real restore would supply from the
 * Python side and from Migration.parseAppliedSettings respectively.
 */
class RestoreSummaryTest {

    private val providerLabels = mapOf(
        "gmail" to "Gmail",
        "yahoo" to "Yahoo Mail",
        "custom" to "Custom",
    )

    @Test
    fun `a full bundle lists every carried setting with its real label`() {
        val summary = restoreSummary(
            chats = 12,
            hashes = 340,
            cutoffs = 3,
            appliedSettings = mapOf(
                "imap_provider" to "yahoo",
                "imap_email" to "meera.iyer@example.com",
                "theme_mode" to "dark",
                "chunk_size" to "day",
                "watch_interval_minutes" to 60L,
                "synced_file_policy" to "delete",
                "dry_run_default" to true,
            ),
            passwordSaved = false,
            providerLabels = providerLabels,
        )
        assertTrue(summary.restored.contains("Chats: 12"))
        assertTrue(summary.restored.contains("Messages already sent: 340 (won't be sent again)"))
        assertTrue(summary.restored.contains("Per-chat cutoff dates: 3"))
        assertTrue(summary.restored.contains("Mail account: Yahoo Mail – meera.iyer@example.com"))
        assertTrue(summary.restored.contains("Theme: Dark"))
        assertTrue(summary.restored.contains("Email grouping: Daily emails"))
        assertTrue(summary.restored.contains("Check interval: Every hour"))
        assertTrue(summary.restored.contains("After import: Delete after import"))
        assertTrue(summary.restored.contains("Rehearse without sending: On"))
        assertTrue(summary.notRestored.contains("Your app password (enter it once)"))
        assertTrue(summary.notRestored.contains("Watched folder (choose it again in Settings > Advanced)"))
    }

    @Test
    fun `a partial bundle lists only the settings it actually carried`() {
        // Only theme_mode came with this one -- no chunk_size, no watch
        // interval, no synced-file policy, no mail account, no dry-run flag.
        val summary = restoreSummary(
            chats = 1,
            hashes = 0,
            cutoffs = 0,
            appliedSettings = mapOf("theme_mode" to "light"),
            passwordSaved = true,
            providerLabels = providerLabels,
        )
        assertTrue(summary.restored.contains("Theme: Light"))
        assertFalse(summary.restored.any { it.startsWith("Email grouping") })
        assertFalse(summary.restored.any { it.startsWith("Check interval") })
        assertFalse(summary.restored.any { it.startsWith("After import") })
        assertFalse(summary.restored.any { it.startsWith("Mail account") })
        assertFalse(summary.restored.any { it.startsWith("Rehearse") })
    }

    @Test
    fun `zero cutoffs is not named -- a phone that never set one is not told about it`() {
        val summary = restoreSummary(
            chats = 5,
            hashes = 10,
            cutoffs = 0,
            appliedSettings = emptyMap(),
            passwordSaved = true,
            providerLabels = providerLabels,
        )
        assertFalse(summary.restored.any { it.startsWith("Per-chat cutoff") })
    }

    @Test
    fun `an already-saved password is not asked for again`() {
        val summary = restoreSummary(
            chats = 1,
            hashes = 1,
            cutoffs = 0,
            appliedSettings = emptyMap(),
            passwordSaved = true,
            providerLabels = providerLabels,
        )
        assertFalse(summary.notRestored.any { it.contains("app password") })
    }

    @Test
    fun `the watched folder is always in Not restored, saved password or not`() {
        val savedPassword = restoreSummary(1, 1, 0, emptyMap(), passwordSaved = true, providerLabels = providerLabels)
        val noPassword = restoreSummary(1, 1, 0, emptyMap(), passwordSaved = false, providerLabels = providerLabels)
        assertTrue(savedPassword.notRestored.any { it.startsWith("Watched folder") })
        assertTrue(noPassword.notRestored.any { it.startsWith("Watched folder") })
    }

    @Test
    fun `no raw pref value appears where a label map exists`() {
        val summary = restoreSummary(
            chats = 1,
            hashes = 1,
            cutoffs = 0,
            appliedSettings = mapOf(
                "theme_mode" to "system",
                "chunk_size" to "week",
                "synced_file_policy" to "move",
            ),
            passwordSaved = true,
            providerLabels = providerLabels,
        )
        // Negative: the raw pref spelling must never stand in for the label.
        assertFalse(summary.restored.any { it == "Theme: system" })
        assertFalse(summary.restored.any { it == "Email grouping: week" })
        assertFalse(summary.restored.any { it == "After import: move" })
        assertTrue(summary.restored.contains("Theme: Match system"))
        assertTrue(summary.restored.contains("Email grouping: Weekly emails"))
        assertTrue(summary.restored.contains("After import: Move to a \"synced\" subfolder"))
    }

    @Test
    fun `imap_host is never listed -- it is applied but not surfaced`() {
        val summary = restoreSummary(
            chats = 1,
            hashes = 1,
            cutoffs = 0,
            appliedSettings = mapOf(
                "imap_provider" to "gmail",
                "imap_email" to "meera.iyer@example.com",
                "imap_host" to "imap.gmail.com",
            ),
            passwordSaved = true,
            providerLabels = providerLabels,
        )
        assertFalse(summary.restored.any { it.contains("imap.gmail.com") })
        assertFalse(summary.restored.any { it.contains("imap_host") })
    }

    @Test
    fun `no line names the password with a value -- only the fixed reminder phrase`() {
        val summary = restoreSummary(
            chats = 1,
            hashes = 1,
            cutoffs = 0,
            appliedSettings = emptyMap(),
            passwordSaved = false,
            providerLabels = providerLabels,
        )
        val passwordLines = (summary.restored + summary.notRestored).filter {
            it.contains("password", ignoreCase = true)
        }
        assertEquals(listOf("Your app password (enter it once)"), passwordLines)
    }

    @Test
    fun `an unknown provider key falls back to the raw key rather than crashing`() {
        val summary = restoreSummary(
            chats = 1,
            hashes = 1,
            cutoffs = 0,
            appliedSettings = mapOf(
                "imap_provider" to "outlook",
                "imap_email" to "meera.iyer@example.com",
            ),
            passwordSaved = true,
            providerLabels = providerLabels,
        )
        assertTrue(summary.restored.contains("Mail account: outlook – meera.iyer@example.com"))
    }
}
