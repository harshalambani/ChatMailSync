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
         * derived from this by config.py's _apply_root(). */
        fun pythonRoot(context: Context): File = File(context.filesDir, "wagmail")

        fun inboxDir(context: Context): File =
            File(pythonRoot(context), "data/inbox").apply { mkdirs() }
    }
}
