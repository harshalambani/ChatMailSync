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
}
