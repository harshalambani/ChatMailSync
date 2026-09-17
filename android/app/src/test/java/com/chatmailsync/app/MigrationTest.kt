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
