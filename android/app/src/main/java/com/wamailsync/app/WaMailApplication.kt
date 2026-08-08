package com.wamailsync.app

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
class WaMailApplication : Application() {
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
         * Renamed from "wagmail" on 2026-08-08. Unlike the SharedPreferences
         * and Keystore renames done alongside it, this one leaves real bytes
         * behind: the old filesDir/wagmail tree keeps its chat exports and
         * sync_state.db, and nothing in the app will ever look at them again.
         * The rename is only complete once the device's app storage is cleared
         * (or the app reinstalled) — otherwise it is a rename plus an orphan. */
        fun pythonRoot(context: Context): File = File(context.filesDir, "wamail")

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
