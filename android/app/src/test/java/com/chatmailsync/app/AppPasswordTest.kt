package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch 3 (2.2.0): app-password entry UX.
 *
 * Plain JUnit: both functions under test are pure Kotlin with no Android
 * framework dependency and no Composable, same as HelpLinkTest.
 */
class AppPasswordTest {

    // --- normalizeAppPassword -------------------------------------------

    @Test
    fun `gmail strips spaces between the four letter groups`() {
        assertEquals("abcdefghijklmnop", normalizeAppPassword("gmail", "abcd efgh ijkl mnop"))
    }

    @Test
    fun `gmail strips tabs and other whitespace too`() {
        assertEquals("abcdefghijklmnop", normalizeAppPassword("gmail", "abcd\tefgh\nijkl mnop"))
    }

    @Test
    fun `gmail with no whitespace is unchanged`() {
        assertEquals("abcdefghijklmnop", normalizeAppPassword("gmail", "abcdefghijklmnop"))
    }

    @Test
    fun `negative - yahoo input with spaces is returned unchanged`() {
        assertEquals("abcd efgh ijkl mnop", normalizeAppPassword("yahoo", "abcd efgh ijkl mnop"))
    }

    @Test
    fun `negative - custom provider input with spaces is returned unchanged`() {
        assertEquals("abcd efgh ijkl mnop", normalizeAppPassword("custom", "abcd efgh ijkl mnop"))
    }

    @Test
    fun `negative - icloud input with spaces is returned unchanged`() {
        assertEquals("abcd efgh ijkl mnop", normalizeAppPassword("icloud", "abcd efgh ijkl mnop"))
    }

    // --- appPasswordHint ---------------------------------------------------

    @Test
    fun `a well formed 16 letter gmail password gets no hint`() {
        assertNull(appPasswordHint("gmail", "abcdefghijklmnop"))
    }

    @Test
    fun `a well formed 16 letter yahoo password gets no hint`() {
        assertNull(appPasswordHint("yahoo", "abcdefghijklmnop"))
    }

    @Test
    fun `a well formed 16 letter aol password gets no hint`() {
        assertNull(appPasswordHint("aol", "abcdefghijklmnop"))
    }

    @Test
    fun `a short aol password gets a hint`() {
        val hint = appPasswordHint("aol", "abcdefgh")
        assertTrue(hint != null && hint.isNotBlank())
    }

    @Test
    fun `negative - aol input with spaces is returned unchanged by normalizeAppPassword`() {
        assertEquals("abcd efgh ijkl mnop", normalizeAppPassword("aol", "abcd efgh ijkl mnop"))
    }

    @Test
    fun `a short gmail password gets a hint`() {
        val hint = appPasswordHint("gmail", "abcdefgh")
        assertTrue(hint != null && hint.isNotBlank())
    }

    @Test
    fun `a gmail password with digits gets a hint`() {
        val hint = appPasswordHint("gmail", "abcd1234ijklmnop")
        assertTrue(hint != null && hint.isNotBlank())
    }

    @Test
    fun `negative - the hint text never contains the password itself`() {
        val secret = "abcd1234ijklmnop"
        val hint = appPasswordHint("gmail", secret)
        assertTrue(hint != null)
        assertFalse("Hint must never echo the password back.", hint!!.contains(secret))
    }

    @Test
    fun `negative - custom imap provider never gets a hint`() {
        assertNull(appPasswordHint("custom", "short"))
        assertNull(appPasswordHint("custom", "abcdefghijklmnop"))
    }

    @Test
    fun `negative - icloud and fastmail never get a hint`() {
        assertNull(appPasswordHint("icloud", "short"))
        assertNull(appPasswordHint("fastmail", "short"))
    }

    @Test
    fun `blank password gets no hint`() {
        assertNull(appPasswordHint("gmail", ""))
        assertNull(appPasswordHint("gmail", "   "))
    }
}
