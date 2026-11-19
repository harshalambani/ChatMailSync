package com.chatmailsync.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * F-01 remainder / D166: a restored backup that would switch "Delete after import" ON is not
 * applied silently. The phone keeps its own policy until the user confirms on screen; "leave" and
 * "move" restore as before. Policy is the only input to deleting an export, so a policy that did
 * not change means no export file was touched.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreDeleteConfirmTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun restore(phonePolicy: String, backupPolicy: String): Boolean {
        AppPrefs.setSyncedFilePolicy(context, phonePolicy)
        return Migration.applySettings(context, """{"synced_file_policy":"$backupPolicy"}""")
    }

    @Test
    fun `a backup with delete on does not enable deletion on a phone set to leave`() {
        assertTrue("the screen is told to ask", restore("leave", "delete"))
        assertEquals("leave", AppPrefs.getSyncedFilePolicy(context))
    }

    @Test
    fun `a backup with delete on does not enable deletion on a phone set to move`() {
        assertTrue(restore("move", "delete"))
        assertEquals("move", AppPrefs.getSyncedFilePolicy(context))
    }

    @Test
    fun `declining keeps the old policy because nothing was written`() {
        restore("leave", "delete")
        // Declining is "do nothing": no call, so the stored policy is exactly the phone's own.
        assertEquals("leave", AppPrefs.getSyncedFilePolicy(context))
        assertNotEquals("delete", AppPrefs.getSyncedFilePolicy(context))
    }

    @Test
    fun `leave and move restore as before`() {
        assertFalse(restore("delete", "leave"))
        assertEquals("leave", AppPrefs.getSyncedFilePolicy(context))
        assertFalse(restore("leave", "move"))
        assertEquals("move", AppPrefs.getSyncedFilePolicy(context))
    }

    @Test
    fun `a phone that already deletes is not asked again`() {
        assertFalse(restore("delete", "delete"))
        assertEquals("delete", AppPrefs.getSyncedFilePolicy(context))
    }

    @Test
    fun `an invalid policy changes nothing and asks nothing`() {
        assertFalse(restore("leave", "wipe-everything"))
        assertEquals("leave", AppPrefs.getSyncedFilePolicy(context))
    }

    @Test
    fun `the held key is not listed as restored and the held line is listed as waiting`() {
        val held = restoreSummary(
            chats = 1, hashes = 2, cutoffs = 0,
            appliedSettings = emptyMap(), passwordSaved = true, providerLabels = emptyMap(),
            deleteAfterImportHeld = true,
        )
        assertTrue(held.notRestored.any { it.startsWith("Delete after import") })
        assertFalse(held.restored.any { it.contains("Delete after import") })
        val plain = restoreSummary(
            chats = 1, hashes = 2, cutoffs = 0,
            appliedSettings = mapOf("synced_file_policy" to "move"), passwordSaved = true, providerLabels = emptyMap(),
        )
        assertFalse(plain.notRestored.any { it.startsWith("Delete after import") })
        assertTrue(plain.restored.any { it.contains("Move to") })
    }

    @Test
    fun `only a switch from not-delete to delete is held`() {
        assertTrue(restoreWouldEnableDelete("delete", "leave"))
        assertTrue(restoreWouldEnableDelete("delete", "move"))
        assertFalse(restoreWouldEnableDelete("delete", "delete"))
        assertFalse(restoreWouldEnableDelete("leave", "delete"))
        assertFalse(restoreWouldEnableDelete("move", "leave"))
        assertFalse(restoreWouldEnableDelete(null, "leave"))
    }

    @Test
    fun `the screen only writes delete from the confirm button`() {
        val main = (File("src/main/java/com/chatmailsync/app/MainActivity.kt").takeIf { it.isFile }
            ?: File("app/src/main/java/com/chatmailsync/app/MainActivity.kt")).readText()
        val dialog = main.substring(main.indexOf("if (confirmDeleteAfterImport) {"))
            .substringBefore("fun clearWatchedFolder")
        assertEquals(1, Regex("""setSyncedFilePolicy\("delete"\)""").findAll(dialog).count())
        val confirm = dialog.substringAfter("confirmButton = {").substringBefore("dismissButton = {")
        assertTrue(confirm.contains("""setSyncedFilePolicy("delete")"""))
        val dismiss = dialog.substringAfter("dismissButton = {")
        assertFalse(dismiss.contains("setSyncedFilePolicy"))
        assertFalse(dialog.substringBefore("confirmButton = {").contains("setSyncedFilePolicy"))
        // The restore path itself never writes "delete" except through the held check.
        val mig = (File("src/main/java/com/chatmailsync/app/Migration.kt").takeIf { it.isFile }
            ?: File("app/src/main/java/com/chatmailsync/app/Migration.kt")).readText()
        assertTrue(mig.contains("if (restoredPolicy != null && !deleteHeld) AppPrefs.setSyncedFilePolicy"))
        assertFalse(mig.contains("""setSyncedFilePolicy(context, "delete")"""))
    }
}
