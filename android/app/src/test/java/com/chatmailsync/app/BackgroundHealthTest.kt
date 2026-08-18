package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the background-health card (Batch E).
 *
 * Plain JUnit, no Robolectric: backgroundHealthIssues takes three booleans and
 * touches no Context, which is exactly why it was split out from the two
 * readers (isBatteryExempt, notificationsAllowed) that do. Those two are thin
 * wrappers over PowerManager and NotificationManagerCompat and stay covered by
 * the device checklist.
 */
class BackgroundHealthTest {

    @Test
    fun `nothing is reported while automatic syncing is off`() {
        // The rule that keeps this from becoming a permanent banner people
        // learn to scroll past. Manual syncs run in the foreground with the
        // user watching, where neither setting can hurt them.
        assertEquals(
            emptyList<BackgroundIssue>(),
            backgroundHealthIssues(autoWatchOn = false, batteryExempt = false, notificationsAllowed = false),
        )
    }

    @Test
    fun `a healthy phone with auto-watch on is reported as healthy`() {
        assertEquals(
            emptyList<BackgroundIssue>(),
            backgroundHealthIssues(autoWatchOn = true, batteryExempt = true, notificationsAllowed = true),
        )
    }

    @Test
    fun `each setting raises its own issue`() {
        assertEquals(
            listOf(BackgroundIssue.BATTERY_OPTIMISED),
            backgroundHealthIssues(autoWatchOn = true, batteryExempt = false, notificationsAllowed = true),
        )
        assertEquals(
            listOf(BackgroundIssue.NOTIFICATIONS_BLOCKED),
            backgroundHealthIssues(autoWatchOn = true, batteryExempt = true, notificationsAllowed = false),
        )
    }

    @Test
    fun `battery comes first when both are wrong`() {
        // Order is the card order, and it is a judgement about consequence:
        // battery optimisation stops the sync, a blocked notification only
        // hides it. The worse one is read first.
        assertEquals(
            listOf(BackgroundIssue.BATTERY_OPTIMISED, BackgroundIssue.NOTIFICATIONS_BLOCKED),
            backgroundHealthIssues(autoWatchOn = true, batteryExempt = false, notificationsAllowed = false),
        )
    }

    @Test
    fun `every issue has its own words`() {
        val issues = BackgroundIssue.values()
        assertEquals(issues.size, issues.map { it.title }.toSet().size)
        assertEquals(issues.size, issues.map { it.detail }.toSet().size)
        assertEquals(issues.size, issues.map { it.actionLabel }.toSet().size)
    }

    @Test
    fun `no button promises a fix this app cannot perform`() {
        // Both buttons leave the app for a system screen. A label like "Fix"
        // is why a user comes back believing they already dealt with it.
        BackgroundIssue.values().forEach {
            assertTrue(it.actionLabel, it.actionLabel.startsWith("Open "))
        }
    }
}
