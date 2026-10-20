package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUG-12: the failure line in the finish notification and the result card. */
class ExportsKeptLineTest {

    @Test
    fun noFailedExportsMeansNoLineAtAll() {
        assertNull(exportsKeptLine(0))
        assertNull(exportsKeptLine(-1))
        assertEquals("Sync complete \u2014 12 message(s) synced", syncNotificationText("Sync complete", 12, 0))
        assertFalse(syncNotificationText("Sync complete", 12, 0).contains("kept"))
    }

    @Test
    fun oneFailedExportIsSingular() {
        assertEquals("1 export could not be read and was kept - see Sync log", exportsKeptLine(1))
        assertEquals(
            "Sync complete \u2014 12 message(s) synced; 1 export could not be read and was kept - see Sync log",
            syncNotificationText("Sync complete", 12, 1),
        )
    }

    @Test
    fun severalFailedExportsArePlural() {
        assertEquals("3 exports could not be read and were kept - see Sync log", exportsKeptLine(3))
        assertEquals(
            "Test run complete \u2014 0 message(s) synced; 3 exports could not be read and were kept - see Sync log",
            syncNotificationText("Test run complete", 0, 3),
        )
    }

    @Test
    fun theLineNeverCarriesAChatName() {
        for (n in 1..5) {
            val text = exportsKeptLine(n)!! + syncNotificationText("Sync complete", 1, n)
            assertFalse(text.contains("Meera"))
            assertFalse(text.contains("WhatsApp Chat with"))
            assertTrue(text.contains("Sync log"))
        }
    }
}
