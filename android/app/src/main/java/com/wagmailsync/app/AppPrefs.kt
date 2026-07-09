package com.wagmailsync.app

import android.content.Context

/** Small SharedPreferences wrapper for the watched-folder feature (Home
 * feedback: "can we set an input folder to keep looking for changes in"). */
object AppPrefs {
    private const val PREFS_NAME = "wagmail_prefs"
    private const val KEY_WATCHED_FOLDER_URI = "watched_folder_uri"
    private const val KEY_AUTO_WATCH_ENABLED = "auto_watch_enabled"
    private const val KEY_IMPORTED_DOC_IDS = "imported_doc_ids"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_WATCH_INTERVAL_MINUTES = "watch_interval_minutes"
    private const val KEY_SYNCED_FILE_POLICY = "synced_file_policy"
    private const val KEY_CONNECTED_EMAIL = "connected_email"

    /** WorkManager's PeriodicWorkRequest has a hard 15-minute floor enforced
     * by the platform itself — no interval below this is achievable
     * regardless of what's requested. */
    const val MIN_WATCH_INTERVAL_MINUTES = 15L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getWatchedFolderUri(context: Context): String? =
        prefs(context).getString(KEY_WATCHED_FOLDER_URI, null)

    fun setWatchedFolderUri(context: Context, uri: String?) {
        prefs(context).edit().putString(KEY_WATCHED_FOLDER_URI, uri).apply()
    }

    fun isAutoWatchEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_WATCH_ENABLED, false)

    fun setAutoWatchEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_WATCH_ENABLED, enabled).apply()
    }

    /** Document IDs already copied into inbox/, so the periodic watcher never
     * re-imports the same file twice even across process restarts. */
    fun getImportedDocIds(context: Context): MutableSet<String> =
        HashSet(prefs(context).getStringSet(KEY_IMPORTED_DOC_IDS, emptySet()) ?: emptySet())

    fun addImportedDocId(context: Context, docId: String) {
        val current = getImportedDocIds(context)
        current.add(docId)
        prefs(context).edit().putStringSet(KEY_IMPORTED_DOC_IDS, current).apply()
    }

    /** "system" (default, follows OS) | "light" | "dark" — mirrors the
     * Windows GUI's manual light/dark toggle button, which is independent
     * of the OS theme. */
    fun getThemeMode(context: Context): String =
        prefs(context).getString(KEY_THEME_MODE, "system") ?: "system"

    fun setThemeMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode).apply()
    }

    fun getWatchIntervalMinutes(context: Context): Long =
        prefs(context).getLong(KEY_WATCH_INTERVAL_MINUTES, MIN_WATCH_INTERVAL_MINUTES)

    fun setWatchIntervalMinutes(context: Context, minutes: Long) {
        prefs(context).edit().putLong(KEY_WATCH_INTERVAL_MINUTES, minutes).apply()
    }

    /** "leave" (default, today's behavior) | "move" (into a `synced/`
     * subfolder of the watched tree) | "delete" — what to do with a file
     * once it's been successfully imported into inbox/, so the watched
     * folder doesn't accumulate old exports indefinitely. Defaults to
     * "leave" so existing installs see no behavior change until the user
     * opts in. */
    fun getSyncedFilePolicy(context: Context): String =
        prefs(context).getString(KEY_SYNCED_FILE_POLICY, "leave") ?: "leave"

    fun setSyncedFilePolicy(context: Context, policy: String) {
        prefs(context).edit().putString(KEY_SYNCED_FILE_POLICY, policy).apply()
    }

    /** The connected Gmail account's email, persisted separately from the
     * in-memory Compose state so WatchFolderWorker — which can run headless,
     * with no Activity and possibly after the app process was killed — knows
     * which account to silently request a token for. */
    fun getConnectedAccountEmail(context: Context): String? =
        prefs(context).getString(KEY_CONNECTED_EMAIL, null)

    fun setConnectedAccountEmail(context: Context, email: String?) {
        prefs(context).edit().putString(KEY_CONNECTED_EMAIL, email).apply()
    }
}
