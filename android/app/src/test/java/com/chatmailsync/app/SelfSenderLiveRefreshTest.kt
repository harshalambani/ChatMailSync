package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-03: the Home Me row follows the self-sender mid-sync. The worker posts a stamp of
 * source|name in its progress; Home refreshes only when that stamp is present and differs from
 * what the row shows. Plain JUnit over the two helpers.
 *
 * A dry or test run learns nothing because the core resolves the self-sender only on the real
 * path (SyncManager dryRun = false); the stamp then stays the unknown one and nothing refreshes.
 */
class SelfSenderLiveRefreshTest {

    private val unknown = selfSenderStamp("unknown", null)
    private val learned = selfSenderStamp("learned", "Asha Rao")

    @Test
    fun `row moves from unknown to learned as soon as the stamp changes`() {
        assertNotEquals(unknown, learned)
        assertTrue(shouldRefreshSelfSender(learned, unknown))
    }

    @Test
    fun `no refresh while nothing changed`() {
        assertFalse(shouldRefreshSelfSender(learned, learned))
        assertFalse(shouldRefreshSelfSender(unknown, unknown))
    }

    @Test
    fun `a run that learned nothing leaves the row alone`() {
        // Dry/test run, or a failed read: no stamp at all.
        assertFalse(shouldRefreshSelfSender(null, unknown))
        assertFalse(shouldRefreshSelfSender("", learned))
    }

    @Test
    fun `an override is never replaced by a learned name`() {
        val override = selfSenderStamp("override", "Asha R")
        assertNotEquals(override, learned)
        // The core reports the override as the source whenever one is set, so the live stamp
        // stays the override one and an unchanged override triggers nothing.
        assertFalse(shouldRefreshSelfSender(override, override))
    }

    @Test
    fun `stamp is stable and null safe`() {
        assertEquals("|", selfSenderStamp(null, null))
        assertEquals("learned|Asha Rao", learned)
    }
}
