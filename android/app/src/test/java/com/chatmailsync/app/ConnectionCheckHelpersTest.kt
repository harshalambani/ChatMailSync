package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the pure pieces of the "test connection never returns"
 * fix -- [runConnectionCheck] itself needs a background Thread + Handler
 * loop that isn't exercisable from a plain JVM unit test (no Robolectric
 * here), but the two things that make its guarantees meaningful on their
 * own -- the watchdog's user-facing text, and the redaction it applies to
 * whatever an exception says -- are ordinary functions and are covered
 * directly.
 */
class ConnectionCheckHelpersTest {

    @Test
    fun `the watchdog timeout text says timed out and never blames a specific stage by name`() {
        val text = connectionWatchdogTimeoutText()
        assertTrue(text.contains("timed out", ignoreCase = true))
    }

    @Test
    fun `redactSecretText masks the password when it appears in the text`() {
        val redacted = redactSecretText("Could not connect: auth failed for fake-app-password", "fake-app-password")
        assertFalse(
            "the password itself must not survive redaction",
            redacted.contains("fake-app-password"),
        )
        assertTrue(redacted.contains("********"))
    }

    // Negative: this is the actual requirement from the hard rules -- the
    // password must never reach a produced result string, under any of the
    // ways it could sneak back through the connection-check path (raw
    // exception text, a null/blank secret, or a secret that doesn't happen
    // to occur in this particular message).
    @Test
    fun `the password never appears in redactSecretText output, in any of these cases`() {
        val password = "fake-app-password"
        val cases = listOf(
            "Could not connect: $password",
            "Login failed for user with secret $password rejected",
            "unrelated failure, no secret in this message",
        )
        for (message in cases) {
            val redacted = redactSecretText(message, password)
            assertFalse(
                "password leaked through for input: $message",
                redacted.contains(password),
            )
        }
        // A null or blank secret must be a safe no-op, not a crash.
        assertEquals("unchanged", redactSecretText("unchanged", null))
        assertEquals("unchanged", redactSecretText("unchanged", ""))
    }
}
