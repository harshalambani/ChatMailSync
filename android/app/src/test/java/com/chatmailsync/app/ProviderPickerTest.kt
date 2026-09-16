package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch 3b (2.2.0): provider picker order and the tier-2 "should work, not
 * tested yet" qualifier.
 *
 * The picker's actual *order* is not a Kotlin constant -- it comes straight
 * from src/config.py's IMAP_PROVIDERS dict, read across the Chaquopy bridge
 * (see MainActivity.kt's LaunchedEffect) as a `List<ImapProviderInfo>` handed
 * in from outside. That order is covered on the Python side, in
 * tests/test_config.py (test_provider_order_is_proven_first_then_expected_then_fastmail_then_custom).
 * What *is* a pure Kotlin constant, and belongs here, is PROVIDER_TIER2_NOTE
 * -- which providers get the qualifier and which don't -- plus the AOL
 * additions to AppPassword.kt's appPasswordHint.
 */
class ProviderPickerTest {

    @Test
    fun `icloud and aol carry the tier-2 qualifier`() {
        assertEquals("Should work, not tested yet", PROVIDER_TIER2_NOTE["icloud"])
        assertEquals("Should work, not tested yet", PROVIDER_TIER2_NOTE["aol"])
    }

    @Test
    fun `negative - gmail and yahoo carry no qualifier`() {
        assertNull(PROVIDER_TIER2_NOTE["gmail"])
        assertNull(PROVIDER_TIER2_NOTE["yahoo"])
    }

    @Test
    fun `negative - fastmail and custom carry no qualifier`() {
        assertNull(PROVIDER_TIER2_NOTE["fastmail"])
        assertNull(PROVIDER_TIER2_NOTE["custom"])
    }

    @Test
    fun `aol gets generic app-password help text and no invented url`() {
        val text = APP_PASSWORD_HELP_TEXT["aol"]
        assertTrue(text != null && text.isNotBlank())
        assertNull(APP_PASSWORD_HELP_URLS["aol"])
    }

    @Test
    fun `appPasswordHint treats aol the same shape as yahoo`() {
        assertNull(appPasswordHint("aol", "abcdefghijklmnop"))
        val hint = appPasswordHint("aol", "short")
        assertTrue(hint != null && hint.isNotBlank())
    }

    @Test
    fun `negative - normalizeAppPassword leaves aol untouched, gmail-only stripping`() {
        assertEquals("abcd efgh ijkl mnop", normalizeAppPassword("aol", "abcd efgh ijkl mnop"))
    }
}
