package com.chatmailsync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole decision behind the one-time "the app worked out who you are"
 * Home card: show it only when there is an actual name to announce. The
 * Python twin is android_api.get_pending_self_sender_banner(), which already
 * returns None for "nothing learned" -- this covers the blank case Kotlin
 * alone has to guard against, since Python's `set_app_state(..., None, ...)`
 * and an empty string are not the same value once it crosses the bridge.
 */
class SelfSenderLearnedBannerTest {

    @Test
    fun `shows the card once a name is learned`() {
        assertTrue(shouldShowSelfSenderBanner("Rohan Desai"))
    }

    @Test
    fun `null means nothing was ever learned, or it was already dismissed`() {
        assertFalse(shouldShowSelfSenderBanner(null))
    }

    @Test
    fun `blank never shows a card with nothing to say`() {
        assertFalse(shouldShowSelfSenderBanner(""))
        assertFalse(shouldShowSelfSenderBanner("   "))
    }
}
