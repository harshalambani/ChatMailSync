package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
 *
 * The key-list guards below read Migration.kt's own source rather than
 * comparing RESTORABLE_SETTINGS_KEYS against a second hand-copied list --
 * a hand copy of the same list can drift right alongside it and this test
 * would keep passing; it has to be checked against what applySettings and
 * readRestorableSettings actually do, or it cannot catch the bug it is
 * named for.
 */
class RestorableSettingsTest {

    private fun migrationSource(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/java/com/chatmailsync/app/Migration.kt")
            if (candidate.isFile) return candidate.readText()
            val fallback = File(dir, "app/src/main/java/com/chatmailsync/app/Migration.kt")
            if (fallback.isFile) return fallback.readText()
            dir = dir.parentFile
        }
        throw AssertionError("could not locate Migration.kt from ${File("").absolutePath}")
    }

    /** Every `obj.has("key")` guard inside applySettings' body -- the actual
     *  set of prefs a restore JSON can change, read off the source rather
     *  than assumed. */
    private fun applySettingsKeys(source: String): Set<String> {
        val start = source.indexOf("private fun applySettings")
        assertTrue("applySettings not found in Migration.kt", start >= 0)
        val end = source.indexOf("val RESTORABLE_SETTINGS_KEYS", start)
        assertTrue("could not find the end of applySettings in Migration.kt", end > start)
        val body = source.substring(start, end)
        return Regex("""obj\.has\("([a-z_]+)"\)""").findAll(body).map { it.groupValues[1] }.toSet()
    }

    /** Every key branch inside readRestorableSettings' `when` -- the actual
     *  set of prefs MainActivity's post-restore reload re-reads. */
    private fun readRestorableSettingsKeys(source: String): Set<String> {
        val start = source.indexOf("fun readRestorableSettings")
        assertTrue("readRestorableSettings not found in Migration.kt", start >= 0)
        val body = source.substring(start)
        return Regex(""""([a-z_]+)" ->""").findAll(body)
            .map { it.groupValues[1] }
            .filter { it != "else" }
            .toSet()
    }

    @Test
    fun `RESTORABLE_SETTINGS_KEYS matches applySettings' own obj-has guards, key for key`() {
        // The regression this guards: a key added to applySettings (a new
        // portable setting) but not to the published constant would restore
        // correctly into AppPrefs and then silently vanish from the
        // running app's own state -- exactly this bug, for a field nobody
        // thought to update both places for.
        val source = migrationSource()
        assertEquals(applySettingsKeys(source), Migration.RESTORABLE_SETTINGS_KEYS.toSet())
    }

    @Test
    fun `readRestorableSettings has a branch for every listed key, and no others`() {
        val source = migrationSource()
        assertEquals(Migration.RESTORABLE_SETTINGS_KEYS.toSet(), readRestorableSettingsKeys(source))
    }

    @Test
    fun `buildRestorableSettings requests exactly the listed keys from its reader, no more and no fewer`() {
        val requestedKeys = mutableListOf<String>()
        Migration.buildRestorableSettings { key ->
            requestedKeys.add(key)
            null
        }
        assertEquals(Migration.RESTORABLE_SETTINGS_KEYS.toSet(), requestedKeys.toSet())
    }

    @Test
    fun `no restorable key is, or resembles, a secret`() {
        // Same tripwire as src/migration.py: a password/secret/token/
        // credential field must never end up on the list a restore reload
        // reads back into live app state.
        val forbidden = listOf("password", "secret", "token", "credential")
        for (key in Migration.RESTORABLE_SETTINGS_KEYS) {
            for (word in forbidden) {
                assertFalse(
                    "\"$key\" looks like a secret field (\"$word\") and must not be restorable",
                    key.contains(word, ignoreCase = true),
                )
            }
        }
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
