package com.chatmailsync.app

import android.app.Application
import android.content.Context
import com.chatmailsync.core.mail.CoreApi
import java.io.File

/**
 * CUT-02: builds the process-wide Kotlin core ([CoreApi]) lazily, on first use. It replaces the
 * embedded Python runtime that used to be started here: nothing in this app starts Python at run
 * time any more (a unit test guards that). The app's version is handed to the core so archived
 * emails' traceability index stamps BuildConfig.VERSION_NAME.
 */
class ChatMailApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        removeDeadChaquopyFolder(applicationContext)
    }

    /** One-time housekeeping on a background thread, off the main thread and out of the sync
     * path. See [ChaquopyCleanup]. */
    private fun removeDeadChaquopyFolder(ctx: Context) {
        Thread({
            try {
                ChaquopyCleanup.run(
                    filesDir = ctx.filesDir,
                    isDone = { AppPrefs.isChaquopyCleanupDone(ctx) },
                    markDone = { AppPrefs.setChaquopyCleanupDone(ctx) },
                    log = { android.util.Log.w("ChatMailApplication", it) },
                )
            } catch (e: Exception) {
                android.util.Log.w("ChatMailApplication", "chaquopy cleanup failed, will retry")
            }
        }, "chaquopy-cleanup").apply { isDaemon = true }.start()
    }

    companion object {
        @Volatile
        private var appContext: Context? = null

        /** The one core for the process. Stop, progress and the single-flight lock live in it. */
        fun core(context: Context? = null): CoreApi = CoreHolder.get {
            val ctx = (context?.applicationContext ?: appContext)
                ?: error("ChatMailApplication has not started")
            CoreApi(
                root = pythonRoot(ctx),
                openDb = { AndroidStateDb(it) },
                redact = { SecretStore.getSecret(ctx, AppPrefs.getImapPasswordSecretKey()) },
                appVersion = BuildConfig.VERSION_NAME,
            )
        }

        /** Root directory handed to the core (CoreApi root) — everything else
         * (data/inbox, data/processed, data/sync_state.db, auth/) is
         * derived from this by RootPaths.
         *
         * Renamed alongside the SharedPreferences and Keystore identifiers each
         * time they moved — "wagmail" -> "wamail" (2026-08-08) -> "chatmail"
         * (v1.9.0) -> the current value (v1.9.3); AppPrefs carries the account
         * of why each step was affordable. Unlike those two, this one leaves
         * real bytes behind: the superseded tree keeps its chat exports and
         * sync_state.db, and nothing in the app will ever look at them again.
         * The rename is only complete once the device's app storage is cleared
         * (or the app reinstalled) — otherwise it is a rename plus an orphan.
         *
         * The v1.9.0 step was the exception: the applicationId changed in the
         * same commit, so that build installed into a fresh sandbox and had no
         * old tree of its own to strand. This one does — clear it. */
        fun pythonRoot(context: Context): File = File(context.filesDir, "chatmailsync")

        fun inboxDir(context: Context): File =
            File(pythonRoot(context), "data/inbox").apply { mkdirs() }

        /** Mirrors the core's listInbox()'s own definition of "something
         * waiting" (any plain file directly under data/inbox/) — the single
         * source of truth both MainActivity's Home "Sync now" and
         * WatchFolderWorker's "Sync now" consult for whether there's a
         * backlog to deliver, instead of each inventing its own notion of
         * pending work. */
        fun hasPendingInboxFiles(context: Context): Boolean =
            inboxDir(context).listFiles { f -> f.isFile }?.isNotEmpty() == true
    }
}
