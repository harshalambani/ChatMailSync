package com.chatmailsync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host/Port are only ever meaningful for a custom server — every named
 * provider fills and locks them automatically — so showing two disabled,
 * pre-filled fields for Gmail/Yahoo/iCloud/AOL/Fastmail was clutter with
 * no decision behind it (D8). This guards that only "custom" reveals them.
 *
 * Plain JUnit: showCustomServerFields is a pure String -> Boolean function.
 */
class ShowCustomServerFieldsTest {

    @Test
    fun `custom provider shows the host and port fields`() {
        assertTrue(showCustomServerFields("custom"))
    }

    @Test
    fun `named providers do not show the host and port fields`() {
        assertFalse(showCustomServerFields("gmail"))
        assertFalse(showCustomServerFields("yahoo"))
        assertFalse(showCustomServerFields("icloud"))
        assertFalse(showCustomServerFields("aol"))
        assertFalse(showCustomServerFields("fastmail"))
    }

    @Test
    fun `an unrecognised provider string also stays hidden`() {
        // Fail closed: an unknown value is not "custom", so it should not
        // spuriously reveal fields that have nothing to fill them in from.
        assertFalse(showCustomServerFields(""))
        assertFalse(showCustomServerFields("Custom"))
    }
}
