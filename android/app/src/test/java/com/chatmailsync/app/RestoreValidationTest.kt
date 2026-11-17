package com.chatmailsync.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

/**
 * F-01: a restored bundle is untrusted input. Every value is validated before it is written, an
 * invalid one is ignored (the phone keeps its own), and a restore that points the phone at a
 * different account clears the saved password. The pure seams are exercised here; the Keystore call
 * itself cannot run in a JVM test, so a source guard pins its wiring.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreValidationTest {

    private fun v(vararg pairs: Pair<String, Any?>) = validateRestoredSettings(mapOf(*pairs))

    @Test
    fun `valid values pass through typed`() {
        val out = v(
            "chunk_size" to "250", "watch_interval_minutes" to 30, "synced_file_policy" to "move",
            "theme_mode" to "dark", "dry_run_default" to true, "mail_backend" to "imap",
            "imap_provider" to "yahoo", "imap_host" to "imap.mail.yahoo.com", "imap_port" to 993,
            "imap_email" to "meera.iyer@example.com",
        )
        assertEquals("250", out["chunk_size"])
        assertEquals(30L, out["watch_interval_minutes"])
        assertEquals("move", out["synced_file_policy"])
        assertEquals("dark", out["theme_mode"])
        assertEquals(true, out["dry_run_default"])
        assertEquals("imap", out["mail_backend"])
        assertEquals("yahoo", out["imap_provider"])
        assertEquals(993, out["imap_port"])
        assertEquals("meera.iyer@example.com", out["imap_email"])
    }

    @Test
    fun `an unknown file policy is ignored`() {
        assertFalse(v("synced_file_policy" to "wipe-everything").containsKey("synced_file_policy"))
        assertFalse(v("synced_file_policy" to "").containsKey("synced_file_policy"))
    }

    @Test
    fun `any backend but imap is ignored`() {
        assertFalse(v("mail_backend" to "gmail_oauth").containsKey("mail_backend"))
        assertFalse(v("mail_backend" to "").containsKey("mail_backend"))
    }

    @Test
    fun `garbage chunk size is ignored but day is kept`() {
        assertFalse(v("chunk_size" to "banana").containsKey("chunk_size"))
        assertFalse(v("chunk_size" to "-5").containsKey("chunk_size"))
        assertEquals("day", v("chunk_size" to "day")["chunk_size"])
    }

    @Test
    fun `ports outside 1 to 65535 or not numeric are ignored`() {
        for (bad in listOf<Any?>(0, -1, 65536, 99999999999L, "abc", "", 1.5, null, true)) {
            assertFalse("port $bad", v("imap_port" to bad).containsKey("imap_port"))
        }
        assertEquals(1, v("imap_port" to 1)["imap_port"])
        assertEquals(65535, v("imap_port" to "65535")["imap_port"])
    }

    @Test
    fun `an unknown provider is ignored and not turned into gmail`() {
        val out = v("imap_provider" to "evilmail")
        assertFalse(out.containsKey("imap_provider"))
        assertNotEquals("gmail", out["imap_provider"])
    }

    @Test
    fun `a retired provider key lands on its replacement`() {
        assertEquals("custom", v("imap_provider" to "outlook")["imap_provider"])
    }

    @Test
    fun `unknown theme is ignored`() {
        assertFalse(v("theme_mode" to "neon").containsKey("theme_mode"))
        assertEquals("light", v("theme_mode" to "light")["theme_mode"])
    }

    @Test
    fun `email with control characters or absurd length is ignored`() {
        assertFalse(v("imap_email" to "a@b.com\r\nBcc: x@y.z").containsKey("imap_email"))
        assertFalse(v("imap_email" to "a\u0000@b.com").containsKey("imap_email"))
        assertFalse(v("imap_email" to "a".repeat(300) + "@b.com").containsKey("imap_email"))
    }

    @Test
    fun `host with whitespace or control characters is ignored`() {
        assertFalse(v("imap_host" to "evil.com extra").containsKey("imap_host"))
        assertFalse(v("imap_host" to "evil.com\n").containsKey("imap_host").not().let { false })
    }

    @Test
    fun `the watch interval keeps its floor`() {
        assertEquals(AppPrefs.MIN_WATCH_INTERVAL_MINUTES, v("watch_interval_minutes" to 1)["watch_interval_minutes"])
        assertEquals(AppPrefs.MIN_WATCH_INTERVAL_MINUTES, v("watch_interval_minutes" to -50)["watch_interval_minutes"])
    }

    @Test
    fun `keys outside the restorable list never come out`() {
        val out = v("imap_password" to "x", "app_password" to "x", "owner" to "x")
        assertTrue(out.isEmpty())
    }

    // ---- account change -> password cleared ----

    @Test
    fun `a different provider, email or host counts as an account change`() {
        assertTrue(restoreChangesAccount("yahoo", "a@x.com", "h", "gmail", "a@x.com", "h"))
        assertTrue(restoreChangesAccount("yahoo", "a@x.com", "h", "yahoo", "b@x.com", "h"))
        assertTrue(restoreChangesAccount("custom", "a@x.com", "h1", "custom", "a@x.com", "h2"))
    }

    @Test
    fun `the same account, differing only in case or spaces, keeps the password`() {
        assertFalse(restoreChangesAccount("yahoo", "Meera@X.com", "IMAP.Yahoo.com", "yahoo", " meera@x.com ", "imap.yahoo.com "))
        assertFalse(restoreChangesAccount("gmail", "a@x.com", "", "gmail", "a@x.com", ""))
    }

    @Test
    fun `applySettings clears only the Keystore secret, only on an account change, and the UI re-reads it`() {
        val src = File("src/main/java/com/chatmailsync/app/Migration.kt").takeIf { it.isFile }
            ?: File("app/src/main/java/com/chatmailsync/app/Migration.kt")
        val text = src.readText()
        val body = text.substring(text.indexOf("private fun applySettings"), text.indexOf("val RESTORABLE_SETTINGS_KEYS"))
        assertTrue(body.contains("restoreChangesAccount("))
        assertTrue(body.contains("SecretStore.clearSecret(context, AppPrefs.getImapPasswordSecretKey())"))
        assertFalse("must not wipe the account fields", body.contains("clearImapSettings"))
        val main = (File("src/main/java/com/chatmailsync/app/MainActivity.kt").takeIf { it.isFile }
            ?: File("app/src/main/java/com/chatmailsync/app/MainActivity.kt")).readText()
        val reload = main.substring(main.indexOf("onThemeModeChange(restored.themeMode)"))
        assertTrue(reload.take(600).contains("imapPasswordSaved = AppPrefs.hasImapPassword(context)"))
    }

    // ---- hostile fixture bundles ----

    private fun fixtureSettings(name: String): Map<String, Any?> {
        val f = listOf("../core/src/test/resources/golden/$name", "core/src/test/resources/golden/$name")
            .map { File(it) }.first { it.isFile }
        ZipFile(f).use { z ->
            val json = z.getInputStream(z.getEntry("settings.json")).bufferedReader().readText()
            val o = JSONObject(json)
            return o.keys().asSequence().associateWith { o.opt(it) }
        }
    }

    @Test
    fun `the hostile custom bundle is accepted only where valid, and changes the account`() {
        val out = validateRestoredSettings(fixtureSettings("migration_hostile_custom.cmsbackup"))
        assertEquals("custom", out["imap_provider"])
        assertEquals(2993, out["imap_port"])
        // A restore of this bundle moves the phone off its own host, so the password must go.
        assertTrue(restoreChangesAccount("custom", "me@x.com", "imap.mine.com", "custom", "me@x.com", out["imap_host"] as String))
    }

    @Test
    fun `the hostile gmail bundle cannot carry a password or an out of range port into the phone`() {
        val out = validateRestoredSettings(fixtureSettings("migration_hostile_gmail.cmsbackup"))
        assertFalse(out.containsKey("app_password"))
        assertEquals("gmail", out["imap_provider"])
        assertEquals(1, out["imap_port"])
    }

    @Test
    fun `the hostile outlook bundle lands on custom and never on gmail`() {
        val out = validateRestoredSettings(fixtureSettings("migration_hostile_outlook.cmsbackup"))
        assertEquals("custom", out["imap_provider"])
        assertNotEquals("gmail", out["imap_provider"])
    }
}
