package com.chatmailsync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as a WhatsApp export, asserted once.
 *
 * This exists because the rule used to live in two places that disagreed:
 * ImportPickerScreen hid the JPEGs and the PDF sitting beside an export and
 * said so on screen ("3 other files hidden"), while WatchFolderWorker imported
 * those same three files and queued them as chats. The user saw both screens
 * and they contradicted each other. Plain JUnit, no Robolectric -- looksLikeExport
 * takes a String and touches no Context.
 */
class ExportFilesTest {

    @Test
    fun `whatsapp export extensions are accepted`() {
        assertTrue(ExportFiles.looksLikeExport("WhatsApp Chat with Dad.txt"))
        assertTrue(ExportFiles.looksLikeExport("WhatsApp Chat with Dad.zip"))
    }

    @Test
    fun `the attachments whatsapp writes beside an export are not exports`() {
        // The exact three that were wrongly queued on a real device.
        assertFalse(ExportFiles.looksLikeExport("IMG-20260414-WA0002.jpg"))
        assertFalse(ExportFiles.looksLikeExport("IMG-20260406-WA0007.jpg"))
        assertFalse(ExportFiles.looksLikeExport("NOTICE-society-agm.pdf"))
    }

    @Test
    fun `extension matching ignores case`() {
        // SAF hands back whatever the other app stored, casing included.
        assertTrue(ExportFiles.looksLikeExport("EXPORT.TXT"))
        assertTrue(ExportFiles.looksLikeExport("Export.Zip"))
    }

    @Test
    fun `a name with no extension is not an export`() {
        // substringAfterLast with a "" default would otherwise have to be
        // checked against the set, and an empty extension must never match.
        assertFalse(ExportFiles.looksLikeExport("README"))
        assertFalse(ExportFiles.looksLikeExport(""))
        assertFalse(ExportFiles.looksLikeExport("chat."))
    }

    @Test
    fun `only the last extension counts`() {
        assertTrue(ExportFiles.looksLikeExport("chat.zip.txt"))
        assertFalse(ExportFiles.looksLikeExport("chat.txt.jpg"))
    }
}
