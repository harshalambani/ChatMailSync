package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The device-bound and process-bound names that can never change again.
 *
 * Started as four (see below); extended in the Phase 0 cleanup to also pin
 * the WorkManager unique work names, the worker class names, the watch-folder
 * notification channel id, every AppPrefs SharedPreferences key, and the
 * app_state table's key strings — all names that, like the original four,
 * fail *silently* on a rename rather than crashing.
 *
 * Most of these are `private const val`, and applicationId lives in a Gradle
 * script, so this test reads the *source files as text* rather than the
 * constants. That is deliberate and not a workaround: making them internal
 * purely so a test could see them would widen their visibility for the sake
 * of the guard, and the thing worth guarding is the literal a future rebrand
 * would edit, which is exactly what a text match catches — change any pinned
 * value and its assertion fails.
 *
 * Why a guard exists at all. The original four moved three times
 * (wagmail -> wamail -> chatmail -> chatmailsync) and every move was free for
 * a reason that has now expired: the only install was our own test device, and
 * at v1.9.0 the applicationId changed in the same commit so the build landed in
 * a fresh sandbox with nothing to orphan. Neither reason survives a store
 * listing. After that, the identical edit is silent — it compiles, it passes
 * every other test, it installs, and then:
 *
 *   - PREFS_NAME / an AppPrefs key: the app reads a different, empty prefs
 *     file (or a single setting silently resets to default). Backend choice,
 *     IMAP host/port/email, watched folder: gone, with no error to explain it.
 *   - KEY_ALIAS: getOrCreateKey() finds nothing, generates a fresh key, and the
 *     saved password fails its GCM tag check. Recoverable — getSecret() clears
 *     the dead blob and returns null — but the user is simply asked to type
 *     their app password again for no visible reason.
 *   - the python root: the old tree is stranded with its sync_state.db, so the
 *     next sync believes it has never seen any of these chats and re-files
 *     every one into a brand-new thread. Mass duplication in the user's
 *     mailbox, which is the failure this app exists to avoid.
 *   - applicationId: the worst of the original four. The other three reset or
 *     strand data inside one app; this one makes every existing install a
 *     *different* app that never receives another update.
 *   - a WorkManager unique work name or worker class name: WorkManager silently
 *     stops recognising previously scheduled/enqueued work as the same work —
 *     duplicate periodic jobs, or a stored reference to a class that no longer
 *     exists.
 *   - the notification channel id: the user's existing per-channel settings
 *     (muted, importance) don't carry over; the old channel is orphaned.
 *   - an app_state key (src/state.py): the self-sender-learned row is orphaned
 *     under the old key; the app reads back "never learned" for a sender it
 *     already resolved.
 *
 * If you are here because this test failed: it is not asking you to update the
 * expected value. A rename would need a real migration path worked out first,
 * not just an updated assertion.
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
    fun `the WorkManager unique work names are frozen`() {
        // Same class of risk as the applicationId/prefs-name checks above: a
        // renamed unique-work-name string doesn't fail loudly, it just makes
        // WorkManager treat the next enqueue as unrelated to any previously
        // scheduled work, so an existing periodic watch-folder job or an
        // in-flight manual sync silently stops being deduplicated/cancelled
        // by the code that thinks it still owns that name.
        val syncWorker = kotlinSource("SyncWorker.kt")
        assertTrue(
            "SyncWorker.kt no longer declares UNIQUE_WORK_NAME_MANUAL_SYNC = \"manual_sync\"",
            syncWorker.contains("""const val UNIQUE_WORK_NAME_MANUAL_SYNC = "manual_sync""""),
        )

        val watchFolderWorker = kotlinSource("WatchFolderWorker.kt")
        assertTrue(
            "WatchFolderWorker.kt no longer declares UNIQUE_WORK_NAME = \"watch_folder\"",
            watchFolderWorker.contains("""const val UNIQUE_WORK_NAME = "watch_folder""""),
        )
        assertTrue(
            "WatchFolderWorker.kt no longer declares UNIQUE_WORK_NAME_ONCE = \"watch_folder_once\"",
            watchFolderWorker.contains("""const val UNIQUE_WORK_NAME_ONCE = "watch_folder_once""""),
        )
        assertTrue(
            "WatchFolderWorker.kt no longer declares UNIQUE_WORK_NAME_AUTO_SYNC = \"watch_folder_auto_sync\"",
            watchFolderWorker.contains(
                """const val UNIQUE_WORK_NAME_AUTO_SYNC = "watch_folder_auto_sync""""
            ),
        )
    }

    @Test
    fun `the worker class names are frozen`() {
        // WorkManager persists the fully-qualified worker class name in its own
        // WorkDatabase to reconstruct and run enqueued/periodic work after a
        // process death or reboot. Renaming the class leaves that stored name
        // pointing at a class that no longer exists — WorkManager fails the
        // work silently rather than crashing.
        assertTrue(
            "SyncWorker.kt no longer declares class SyncWorker",
            kotlinSource("SyncWorker.kt").contains("class SyncWorker("),
        )
        assertTrue(
            "WatchFolderWorker.kt no longer declares class WatchFolderWorker",
            kotlinSource("WatchFolderWorker.kt").contains("class WatchFolderWorker("),
        )
    }

    @Test
    fun `the watch-folder notification channel id is frozen`() {
        // A changed channel id makes Android treat it as a brand-new channel:
        // the user's existing per-channel notification settings (muted,
        // importance level, etc.) don't carry over, and the old channel is
        // orphaned in system settings until the app is uninstalled.
        assertTrue(
            "WatchFolderWorker.kt no longer declares NOTIFICATION_CHANNEL_ID = \"watch_folder_channel\"",
            kotlinSource("WatchFolderWorker.kt")
                .contains("""const val NOTIFICATION_CHANNEL_ID = "watch_folder_channel""""),
        )
    }

    @Test
    fun `every AppPrefs key string is frozen`() {
        // Same failure mode as PREFS_NAME itself (see the class doc): each of
        // these is a SharedPreferences key inside chatmailsync_prefs. Renaming
        // any one of them makes AppPrefs silently read back the default for
        // that single setting instead of the value the user set, because the
        // old key's value is still sitting under the old name in the same file.
        val text = kotlinSource("AppPrefs.kt")
        val keys = mapOf(
            "KEY_WATCHED_FOLDER_URI" to "watched_folder_uri",
            "KEY_AUTO_WATCH_ENABLED" to "auto_watch_enabled",
            "KEY_IMPORTED_DOC_IDS" to "imported_doc_ids",
            "KEY_THEME_MODE" to "theme_mode",
            "KEY_WATCH_INTERVAL_MINUTES" to "watch_interval_minutes",
            "KEY_SYNCED_FILE_POLICY" to "synced_file_policy",
            "KEY_CONNECTED_EMAIL" to "connected_email",
            "KEY_CHUNK_SIZE" to "chunk_size",
            "KEY_DRY_RUN_DEFAULT" to "dry_run_default",
            "KEY_MAIL_BACKEND" to "mail_backend",
            "KEY_OAUTH_REMOVED_NOTICE_SHOWN" to "oauth_removed_notice_shown",
            "KEY_IMAP_PROVIDER" to "imap_provider",
            "KEY_IMAP_HOST" to "imap_host",
            "KEY_IMAP_PORT" to "imap_port",
            "KEY_IMAP_EMAIL" to "imap_email",
            "KEY_IMAP_PASSWORD_SECRET" to "imap_password_secret",
            "KEY_PENDING_SYNCED_FILES" to "pending_synced_files",
            "KEY_LAST_CONNECTION_OK" to "last_connection_ok",
            "KEY_LAST_CONNECTION_AT" to "last_connection_at",
            "KEY_LAST_BACKUP_AT" to "last_backup_at",
            "KEY_CUTOFF_DATE" to "cutoff_date",
            "KEY_FIRST_RUN_DONE" to "first_run_done",
        )
        for ((const, value) in keys) {
            assertTrue(
                "AppPrefs.kt no longer declares $const = \"$value\"",
                text.contains("""private const val $const = "$value""""),
            )
        }
    }

    @Test
    fun `the app_state table's keys are frozen`() {
        // app_state (src/state.py) is the shared Python core's own small
        // key-value store, keyed by these literal strings from
        // android_api.py's self-sender flow. A rename here is the Python-side
        // twin of the AppPrefs risk above: the old row is orphaned under the
        // old key and the app reads back "never learned" for a sender that
        // was, in fact, already resolved.
        val text = source("src/state.py")
        assertTrue(
            "state.py no longer declares SELF_SENDER_OVERRIDE = \"self_sender_override\"",
            text.contains("""SELF_SENDER_OVERRIDE = "self_sender_override""""),
        )
        assertTrue(
            "state.py no longer declares SELF_SENDER_LEARNED = \"self_sender_learned\"",
            text.contains("""SELF_SENDER_LEARNED = "self_sender_learned""""),
        )
        assertTrue(
            "state.py no longer declares SELF_SENDER_LEARNED_PENDING = " +
                "\"self_sender_learned_pending\"",
            text.contains(
                """SELF_SENDER_LEARNED_PENDING = "self_sender_learned_pending""""
            ),
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
