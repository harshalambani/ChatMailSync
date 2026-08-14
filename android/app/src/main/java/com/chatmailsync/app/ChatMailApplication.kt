package com.chatmailsync.app

import android.app.Application
import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/**
 * Starts the embedded Python runtime once for the process and points the
 * shared core (src/config.py) at Android's app-private storage before
 * anything else touches it — config.set_root() must run before any other
 * src.* module resolves a path-derived constant (see config.py's docstring).
 */
class ChatMailApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        Python.getInstance()
            .getModule("src.config")
            .callAttr("set_root", pythonRoot(this).absolutePath)
    }

    companion object {
        /** Root directory handed to config.set_root() — everything else
         * (data/inbox, data/processed, data/sync_state.db, auth/) is
         * derived from this by config.py's _apply_root().
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

        /** Mirrors android_api.list_inbox()'s own definition of "something
         * waiting" (any plain file directly under data/inbox/) — the single
         * source of truth both MainActivity's Home "Sync now" and
         * WatchFolderWorker's "Sync now" consult for whether there's a
         * backlog to deliver, instead of each inventing its own notion of
         * pending work. */
        fun hasPendingInboxFiles(context: Context): Boolean =
            inboxDir(context).listFiles { f -> f.isFile }?.isNotEmpty() == true
    }
}
