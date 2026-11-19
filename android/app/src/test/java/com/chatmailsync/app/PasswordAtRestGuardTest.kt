package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ENC guard: the IMAP password rests in SharedPreferences only as SecretStore ciphertext and never
 * travels in WorkManager Data. The Android Keystore cannot run in a JVM test, so this pins the seam
 * in the source; the real encrypt/decrypt round trip is SecretStoreRoundTripTest in androidTest
 * (not run here: no device or emulator was used for this batch).
 */
class PasswordAtRestGuardTest {

    private fun appSources(): List<File> {
        val dir = listOf("src/main/java/com/chatmailsync/app", "app/src/main/java/com/chatmailsync/app")
            .map { File(it) }.first { it.isDirectory }
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** The text of each call of [name](...) up to its balanced closing parenthesis. */
    private fun calls(source: String, name: String): List<String> {
        val out = mutableListOf<String>()
        var i = source.indexOf("$name(")
        while (i >= 0) {
            var depth = 0
            var j = i + name.length
            while (j < source.length) {
                if (source[j] == '(') depth++
                if (source[j] == ')') { depth--; if (depth == 0) break }
                j++
            }
            out.add(source.substring(i, minOf(j + 1, source.length)))
            i = source.indexOf("$name(", j)
        }
        return out
    }

    // Words inside string literals are user-facing text, not values; only identifiers count.
    // scrubSecret(text, secret) REMOVES the secret from text, so its whole call is clean.
    private fun isPasswordRef(call: String): Boolean {
        var c = call
        for (scrub in calls(c, "scrubSecret")) c = c.replace(scrub, "SCRUBBED")
        return Regex("(?i)password|secret").containsMatchIn(c.replace(Regex("\"(\\\\.|[^\"\\\\])*\""), "\"\""))
    }

    @Test
    fun `no WorkManager Data or work result carries a password`() {
        var seen = 0
        for (f in appSources()) {
            val text = f.readText()
            for (name in listOf("workDataOf", "Data.Builder")) {
                for (c in calls(text, name)) {
                    seen++
                    assertFalse("${f.name}: $name carries a password: $c", isPasswordRef(c))
                }
            }
            if (text.contains("Data.Builder")) {
                for (line in text.lines().filter { it.contains(".putString(") }) {
                    assertFalse("${f.name}: $line", isPasswordRef(line))
                }
            }
        }
        assertTrue("the scan found Data builders to check", seen > 0)
    }

    @Test
    fun `the only writer of the password key is SecretStore putSecret`() {
        val writers = mutableListOf<String>()
        for (f in appSources()) {
            val text = f.readText()
            if (f.name != "SecretStore.kt") {
                assertFalse(
                    f.name,
                    Regex("""putString\(\s*(KEY_IMAP_PASSWORD_SECRET|"imap_password_secret"|AppPrefs\.getImapPasswordSecretKey\(\))""")
                        .containsMatchIn(text),
                )
                for (c in calls(text, "putSecret")) writers.add("${f.name}: $c")
            }
        }
        assertTrue("password writes exist: $writers", writers.isNotEmpty())
        for (w in writers) assertTrue(w, w.contains("AppPrefs.getImapPasswordSecretKey()"))
    }

    @Test
    fun `putSecret stores only the encrypted blob`() {
        val f = appSources().first { it.name == "SecretStore.kt" }.readText()
        val body = f.substring(f.indexOf("fun putSecret"), f.indexOf("fun getSecret"))
        assertTrue(body.contains("cipher.doFinal("))
        val puts = Regex("""putString\(([^)]*)\)""").findAll(body).map { it.groupValues[1].replace(" ", "") }.toList()
        assertEquals("exactly one prefs write, of the encoded ciphertext", listOf("key,encoded"), puts)
        assertFalse("the plain value is never written", puts.any { it.contains("value") })
    }
}
