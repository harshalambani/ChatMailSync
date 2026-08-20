package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The four device-bound names that can never change again.
 *
 * Three of them are `private const val`, and the fourth lives in a Gradle
 * script, so this test reads the *source files as text* rather than the
 * constants. That is deliberate and not a workaround: making them internal
 * purely so a test could see them would widen their visibility for the sake
 * of the guard, and the thing worth guarding is the literal a future rebrand
 * would edit, which is exactly what a text match catches.
 *
 * Why a guard exists at all. Each of these moved three times
 * (wagmail -> wamail -> chatmail -> chatmailsync) and every move was free for
 * a reason that has now expired: the only install was our own test device, and
 * at v1.9.0 the applicationId changed in the same commit so the build landed in
 * a fresh sandbox with nothing to orphan. Neither reason survives a store
 * listing. After that, the identical edit is silent — it compiles, it passes
 * every other test, it installs, and then:
 *
 *   - PREFS_NAME: the app reads a different, empty prefs file and comes back at
 *     defaults. Backend choice, IMAP host/port/email, watched folder: gone,
 *     with no error to explain it.
 *   - KEY_ALIAS: getOrCreateKey() finds nothing, generates a fresh key, and the
 *     saved password fails its GCM tag check. Recoverable — getSecret() clears
 *     the dead blob and returns null — but the user is simply asked to type
 *     their app password again for no visible reason.
 *   - the python root: the old tree is stranded with its sync_state.db, so the
 *     next sync believes it has never seen any of these chats and re-files
 *     every one into a brand-new thread. Mass duplication in the user's
 *     mailbox, which is the failure this app exists to avoid.
 *   - applicationId: the worst of the four. The other three reset or strand
 *     data inside one app; this one makes every existing install a *different*
 *     app that never receives another update.
 *
 * If you are here because this test failed: it is not asking you to update the
 * expected value. See PLATFORM-PARITY.md's P3 entry for the migration path a
 * rename would need first.
 */
class FrozenIdentifiersTest {

    private fun source(relative: String): String {
        // Gradle runs unit tests with the module directory as the working
        // directory, but that is a default rather than a promise, so walk up
        // until the path resolves rather than trusting it.
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative from ${File("").absolutePath}")
    }

    private fun kotlinSource(name: String) =
        source("app/src/main/java/com/chatmailsync/app/$name")

    @Test
    fun `the prefs file name is frozen, in both files that declare it`() {
        // Declared independently in two places against one file. They have
        // always matched; nothing enforced it until now, and a half-applied
        // rename is worse than a whole one -- settings would read from one
        // file and the password from another.
        val declaration = """private const val PREFS_NAME = "chatmailsync_prefs""""
        assertTrue(
            "AppPrefs.kt no longer declares chatmailsync_prefs",
            kotlinSource("AppPrefs.kt").contains(declaration),
        )
        assertTrue(
            "SecretStore.kt no longer declares chatmailsync_prefs",
            kotlinSource("SecretStore.kt").contains(declaration),
        )
    }

    @Test
    fun `the keystore alias is frozen`() {
        assertTrue(
            "SecretStore.kt no longer declares chatmailsync_imap_key",
            kotlinSource("SecretStore.kt")
                .contains("""private const val KEY_ALIAS = "chatmailsync_imap_key""""),
        )
    }

    @Test
    fun `the python root directory is frozen`() {
        // This is the one that leaves real bytes behind: the superseded tree
        // keeps its exports and its sync_state.db, and nothing in the app will
        // ever look at them again.
        assertTrue(
            "ChatMailApplication.kt no longer roots python at filesDir/chatmailsync",
            kotlinSource("ChatMailApplication.kt")
                .contains("""File(context.filesDir, "chatmailsync")"""),
        )
    }

    @Test
    fun `the application id is frozen`() {
        val gradle = source("app/build.gradle.kts")
        assertTrue(
            "applicationId is no longer com.chatmailsync.app",
            gradle.contains("""applicationId = "com.chatmailsync.app""""),
        )
        assertTrue(
            "namespace is no longer com.chatmailsync.app",
            gradle.contains("""namespace = "com.chatmailsync.app""""),
        )
    }

    @Test
    fun `no superseded spelling survives anywhere in these files`() {
        // The rename ran wagmail -> wamail -> chatmail -> chatmailsync, and the
        // first three are all still legible substrings of the fourth. A partial
        // edit that left, say, "chatmail_prefs" behind would pass the checks
        // above only if it also removed the current line -- but a *new*
        // identifier introduced alongside them would not be caught at all, so
        // check the whole file for any storage-shaped use of an old spelling.
        val dead = listOf("wagmail", "wamail", "chatmail_")
        for (name in listOf("AppPrefs.kt", "SecretStore.kt", "ChatMailApplication.kt")) {
            val text = kotlinSource(name)
            for (spelling in dead) {
                // The comments in these files recount the rename history on
                // purpose -- that history is why the freeze exists -- so only
                // code lines are checked.
                val offenders = text.lines()
                    .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                    .filter { it.contains(spelling, ignoreCase = true) }
                assertEquals("$name resurrects the superseded spelling '$spelling'", emptyList<String>(), offenders)
            }
        }
    }
}
