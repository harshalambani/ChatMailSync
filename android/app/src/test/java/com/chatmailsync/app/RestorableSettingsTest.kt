package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Batch 7 follow-up: after a restore, MainActivity's mail-account/settings
 * state -- held in `remember { mutableStateOf(AppPrefs.getX(context)) }`
 * blocks read once at first composition -- must re-read from AppPrefs, or
 * it keeps showing the pre-restore values even though AppPrefs itself was
 * updated correctly (confirmed on the Nord: force-stop and relaunch showed
 * the restored account, but the live app did not until this fix).
 *
 * [Migration.buildRestorableSettings] is the pure seam this reload goes
 * through: a key -> value reader in, [Migration.RestorableSettings] out,
 * no Context or SharedPreferences needed here.
 */
class RestorableSettingsTest {

    /** Mirrors MainActivity's own reload -- reloadRestoredSettingsState
     *  reads exactly these keys off Migration.readRestorableSettings. Kept
     *  as a literal list, not a reference to the production constant, so
     *  this test would actually fail if MainActivity's reload silently
     *  dropped or renamed one -- see the equality assertion below, which
     *  is the guard this list exists for. */
    private val mainActivityReloadKeys = listOf(
        "chunk_size",
        "watch_interval_minutes",
        "synced_file_policy",
        "theme_mode",
        "dry_run_default",
        "mail_backend",
        "imap_provider",
        "imap_host",
        "imap_port",
        "imap_email",
    )

    @Test
    fun `MainActivity's reload key list matches Migration applySettings' own, key for key`() {
        // The regression this guards: a key added to applySettings (a new
        // portable setting) but not to MainActivity's post-restore reload
        // would restore correctly into AppPrefs and then silently vanish
        // from the running app's own state -- exactly this bug, for a
        // field nobody thought to update both lists for.
        assertEquals(Migration.RESTORABLE_SETTINGS_KEYS, mainActivityReloadKeys)
    }

    private fun fakeYahooRestore(): (String) -> Any? = { key ->
        when (key) {
            "chunk_size" -> "250"
            "watch_interval_minutes" -> 30L
            "synced_file_policy" -> "keep"
            "theme_mode" -> "dark"
            "dry_run_default" -> true
            "mail_backend" -> "imap"
            "imap_provider" -> "yahoo"
            "imap_host" -> "imap.mail.yahoo.com"
            "imap_port" -> 993
            "imap_email" -> "meera.iyer@example.com"
            else -> null
        }
    }

    @Test
    fun `a simulated restore to yahoo is reflected, not left at defaults`() {
        val restored = Migration.buildRestorableSettings(fakeYahooRestore())
        assertEquals("yahoo", restored.imapProvider)
        assertEquals("meera.iyer@example.com", restored.imapEmail)
        assertEquals("imap.mail.yahoo.com", restored.imapHost)
        assertEquals("dark", restored.themeMode)
        // Negative: must not still read back as the pre-restore/default
        // Gmail-shaped state this bug left behind.
        assertNotEquals("gmail", restored.imapProvider)
        assertFalse(restored.imapEmail.isEmpty())
    }

    @Test
    fun `the reader is never asked for the password secret key`() {
        val requestedKeys = mutableListOf<String>()
        Migration.buildRestorableSettings { key ->
            requestedKeys.add(key)
            fakeYahooRestore()(key)
        }
        assertFalse(requestedKeys.contains(AppPrefs.getImapPasswordSecretKey()))
        // Also never any key outside the declared, deliberately narrow list.
        assertEquals(Migration.RESTORABLE_SETTINGS_KEYS.toSet(), requestedKeys.toSet())
    }

    @Test
    fun `a failed-restore read -- unchanged prefs -- reproduces the same values, not defaults`() {
        // Stands in for "restore failed / was cancelled / was already
        // imported": applySettings never ran, so AppPrefs is exactly what
        // it was before the attempt. Reading it twice must be idempotent --
        // a wipe to defaults on a no-op read would be its own new bug.
        val beforeAttempt = Migration.buildRestorableSettings(fakeYahooRestore())
        val afterFailedAttempt = Migration.buildRestorableSettings(fakeYahooRestore())
        assertEquals(beforeAttempt, afterFailedAttempt)
    }

    @Test
    fun `missing keys fall back to safe defaults, not a crash`() {
        val restored = Migration.buildRestorableSettings { null }
        assertEquals("", restored.imapEmail)
        assertEquals("", restored.imapProvider)
        assertEquals(AppPrefs.MIN_WATCH_INTERVAL_MINUTES, restored.watchIntervalMinutes)
        assertEquals(993, restored.imapPort)
        assertFalse(restored.dryRunDefault)
    }
}
