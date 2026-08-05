package com.wamailsync.app

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
    private const val KEY_CHUNK_SIZE = "chunk_size"
    private const val KEY_DRY_RUN_DEFAULT = "dry_run_default"
    private const val KEY_MAIL_BACKEND = "mail_backend"
    private const val KEY_IMAP_PROVIDER = "imap_provider"
    private const val KEY_IMAP_HOST = "imap_host"
    private const val KEY_IMAP_PORT = "imap_port"
    private const val KEY_IMAP_EMAIL = "imap_email"
    private const val KEY_IMAP_PASSWORD_SECRET = "imap_password_secret"
    private const val KEY_PENDING_SYNCED_FILES = "pending_synced_files"
    /** Control character used to pack a "filename<sep>sourceUri" pair into
     * one StringSet element — SharedPreferences has no native Map type, and
     * this can't collide with a real filename or content:// Uri. */
    private const val PENDING_SYNCED_FILE_SEPARATOR = "\u0001"

    /** WorkManager's PeriodicWorkRequest has a hard 15-minute floor enforced
     * by the platform itself — no interval below this is achievable
     * regardless of what's requested. */
    const val MIN_WATCH_INTERVAL_MINUTES = 15L

    /** Mirrors src/config.py's MAIL_BACKEND_GMAIL_OAUTH / MAIL_BACKEND_IMAP —
     * kept public (not private const like the KEY_* prefs keys) so
     * SyncWorker, WatchFolderWorker, MainActivity and SettingsScreen can all
     * compare against the same literal instead of duplicating the string. */
    const val MAIL_BACKEND_GMAIL_OAUTH = "gmail_oauth"
    const val MAIL_BACKEND_IMAP = "imap"

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

    /** "hour" | "day" (default) | "week" — Home's "Split into" picker.
     * Previously Compose `remember`-only state, reset to "day" on every
     * process death, and WatchFolderWorker's auto-sync hardcoded "day"
     * regardless of what the user had actually picked. Windows mirrors this
     * via data/.settings.json's chunk_size. */
    fun getChunkSize(context: Context): String =
        prefs(context).getString(KEY_CHUNK_SIZE, "day") ?: "day"

    fun setChunkSize(context: Context, chunkSize: String) {
        prefs(context).edit().putString(KEY_CHUNK_SIZE, chunkSize).apply()
    }

    fun isDryRunDefault(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DRY_RUN_DEFAULT, false)

    fun setDryRunDefault(context: Context, dryRun: Boolean) {
        prefs(context).edit().putBoolean(KEY_DRY_RUN_DEFAULT, dryRun).apply()
    }

    /** Null when the user has never explicitly picked a backend — distinct
     * from resolveMailBackend()'s resolved value, so callers that need to
     * know "did the user choose this, or are we defaulting" (e.g. the
     * Settings dropdown's initial selection) can tell the difference. */
    fun getSavedMailBackend(context: Context): String? =
        prefs(context).getString(KEY_MAIL_BACKEND, null)

    fun setMailBackend(context: Context, backend: String) {
        prefs(context).edit().putString(KEY_MAIL_BACKEND, backend).apply()
    }

    /** Mirrors src/config.py:resolve_mail_backend exactly: an explicit saved
     * choice always wins; otherwise an already-connected OAuth account wins
     * (the upgrade guard — an existing OAuth user must never be silently
     * switched onto IMAP just because IMAP is now the default for fresh
     * installs); otherwise IMAP, since that's DEFAULT_MAIL_BACKEND on the
     * Windows side too. */
    fun resolveMailBackend(context: Context): String {
        getSavedMailBackend(context)?.let { return it }
        if (getConnectedAccountEmail(context) != null) return MAIL_BACKEND_GMAIL_OAUTH
        return MAIL_BACKEND_IMAP
    }

    fun getImapProvider(context: Context): String =
        prefs(context).getString(KEY_IMAP_PROVIDER, "gmail") ?: "gmail"

    fun setImapProvider(context: Context, provider: String) {
        prefs(context).edit().putString(KEY_IMAP_PROVIDER, provider).apply()
    }

    fun getImapHost(context: Context): String =
        prefs(context).getString(KEY_IMAP_HOST, "") ?: ""

    fun setImapHost(context: Context, host: String) {
        prefs(context).edit().putString(KEY_IMAP_HOST, host).apply()
    }

    fun getImapPort(context: Context): Int =
        prefs(context).getInt(KEY_IMAP_PORT, 993)

    fun setImapPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_IMAP_PORT, port).apply()
    }

    fun getImapEmail(context: Context): String =
        prefs(context).getString(KEY_IMAP_EMAIL, "") ?: ""

    fun setImapEmail(context: Context, email: String) {
        prefs(context).edit().putString(KEY_IMAP_EMAIL, email).apply()
    }

    /** The app password itself never lives in this class' plain
     * SharedPreferences fields — only Keystore-encrypted, via SecretStore,
     * under this same key name. Kept here (rather than inlining the literal
     * at every call site) so the key name has exactly one source of truth. */
    fun getImapPasswordSecretKey(): String = KEY_IMAP_PASSWORD_SECRET

    fun hasImapPassword(context: Context): Boolean =
        SecretStore.getSecret(context, KEY_IMAP_PASSWORD_SECRET) != null

    /** Inbox filename -> watched-folder source doc URI, recorded at import
     * time and only consumed once WatchFolderWorker.applyPendingSyncedFilePolicies
     * confirms the file has actually left inbox/ (moved to processed/ by a
     * completed sync) — applying synced_file_policy at import time, before
     * delivery, risked moving/deleting a user's source zip for a chat that
     * was never actually sent. A leftover entry here is always safe: it's
     * only acted on once the corresponding file is independently observed
     * to be gone from inbox/, never on trust that a prior run "must have"
     * delivered it. */
    fun getPendingSyncedFiles(context: Context): MutableMap<String, String> {
        val raw = prefs(context).getStringSet(KEY_PENDING_SYNCED_FILES, emptySet()) ?: emptySet()
        val map = LinkedHashMap<String, String>()
        for (entry in raw) {
            val parts = entry.split(PENDING_SYNCED_FILE_SEPARATOR, limit = 2)
            if (parts.size == 2) map[parts[0]] = parts[1]
        }
        return map
    }

    fun addPendingSyncedFile(context: Context, filename: String, sourceUri: String) {
        val current = getPendingSyncedFiles(context)
        current[filename] = sourceUri
        persistPendingSyncedFiles(context, current)
    }

    fun removePendingSyncedFile(context: Context, filename: String) {
        val current = getPendingSyncedFiles(context)
        if (current.remove(filename) != null) persistPendingSyncedFiles(context, current)
    }

    private fun persistPendingSyncedFiles(context: Context, map: Map<String, String>) {
        val encoded = map.entries
            .map { (name, uri) -> "$name$PENDING_SYNCED_FILE_SEPARATOR$uri" }
            .toSet()
        prefs(context).edit().putStringSet(KEY_PENDING_SYNCED_FILES, encoded).apply()
    }

    /** Clears every saved IMAP field, including the Keystore-encrypted
     * password — used by Settings' "Forget saved password". Does not touch
     * mail_backend itself, matching Windows' equivalent Disconnect (which
     * only clears credentials, leaving the backend selection as-is). */
    fun clearImapSettings(context: Context) {
        prefs(context).edit()
            .remove(KEY_IMAP_PROVIDER)
            .remove(KEY_IMAP_HOST)
            .remove(KEY_IMAP_PORT)
            .remove(KEY_IMAP_EMAIL)
            .apply()
        SecretStore.clearSecret(context, KEY_IMAP_PASSWORD_SECRET)
    }
}
