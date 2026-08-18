package com.chatmailsync.app

import android.content.Context

/** Small SharedPreferences wrapper for the watched-folder feature (Home
 * feedback: "can we set an input folder to keep looking for changes in"). */
object AppPrefs {
    // This is a storage identifier, not a display string: changing it makes an
    // existing install read a different (empty) prefs file and come back at
    // defaults — backend choice, IMAP host/port/email, watched folder and the
    // encrypted password all gone, with no error to explain it. SecretStore
    // declares the same name independently against the same file — the two must
    // stay in step.
    //
    // It has moved three times: "wagmail_prefs" -> "wamail_prefs" on 2026-08-08,
    // -> "chatmail_prefs" at v1.9.0, -> the current value at v1.9.3. Each was
    // affordable for its own reason, and neither reason is permanent:
    //
    //   - 2026-08-08: the sole install was our own test device, cleared in the
    //     same change.
    //   - v1.9.0: the applicationId changed in that same commit
    //     (com.wamailsync.app -> com.chatmailsync.app), so the build installed
    //     as a *new app* with an empty sandbox. There was nothing to orphan.
    //   - v1.9.3: still one test device, and this settled the last
    //     inconsistency — the desktop side spells the product out in full
    //     (CHATMAILSYNC_ROOT, src/config.py), while these carried a clipped
    //     prefix inherited from the WhatsApp-and-Gmail era.
    //
    // Treat it as frozen from here. Once this ships on the Galaxy Store the
    // identical edit silently wipes every user's settings on update.
    private const val PREFS_NAME = "chatmailsync_prefs"
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
    private const val KEY_OAUTH_UNLOCKED = "oauth_unlocked"
    private const val KEY_IMAP_PROVIDER = "imap_provider"
    private const val KEY_IMAP_HOST = "imap_host"
    private const val KEY_IMAP_PORT = "imap_port"
    private const val KEY_IMAP_EMAIL = "imap_email"
    private const val KEY_IMAP_PASSWORD_SECRET = "imap_password_secret"
    private const val KEY_PENDING_SYNCED_FILES = "pending_synced_files"
    private const val KEY_LAST_CONNECTION_OK = "last_connection_ok"
    private const val KEY_LAST_CONNECTION_AT = "last_connection_at"
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

    /** Mirrors src/config.py's oauth_is_visible() exactly -- same three
     * grounds, same order, same truth table. Pure and Context-free on purpose:
     * this repo has no Kotlin test source set, so a function needing a Context
     * could not be unit tested without dragging in a whole Android test
     * runtime to assert one boolean. Everything that decides the answer lives
     * here; isOauthVisible() below only fetches the three values.
     *
     * The Gmail OAuth path is DEMOTED, not deleted (v1.6.0). Visibility is
     * gated; behaviour is not -- SyncWorker and WatchFolderWorker still honour
     * MAIL_BACKEND_GMAIL_OAUTH exactly as before, because a latched existing
     * user still runs that path.
     *
     * [connectedEmail] is the Android equivalent of the desktop's token.json:
     * evidence that OAuth has actually been used here. Without grounds (a) and
     * (b) an existing OAuth user would be stranded -- shown a backend they
     * cannot select, or quietly pushed onto IMAP and asked for an app password
     * they have never created.
     *
     * If you change this, change src/config.py:oauth_is_visible in the same
     * commit. One without the other means the two platforms disagree about
     * who is allowed to see Google sign-in, and only one of them is wrong. */
    fun oauthIsVisible(
        savedBackend: String?,
        connectedEmail: String?,
        unlocked: Boolean,
    ): Boolean =
        savedBackend == MAIL_BACKEND_GMAIL_OAUTH || connectedEmail != null || unlocked

    /** Null-safe read of the advanced unlock flag. Set by the tap gesture on
     * the version row in SettingsScreen; there is deliberately no re-lock UI,
     * because clearing app data is the reset and that is proportionate for a
     * maintainer-facing switch. Android has no user-settable environment
     * variable, which is why the desktop's CHATMAILSYNC_ENABLE_OAUTH has no twin
     * here and the gesture had to be the primary mechanism on both. */
    fun isOauthUnlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OAUTH_UNLOCKED, false)

    fun setOauthUnlocked(context: Context, unlocked: Boolean) {
        prefs(context).edit().putBoolean(KEY_OAUTH_UNLOCKED, unlocked).apply()
    }

    fun isOauthVisible(context: Context): Boolean =
        oauthIsVisible(
            getSavedMailBackend(context),
            getConnectedAccountEmail(context),
            isOauthUnlocked(context),
        )

    /** Persists the unlock flag the first time OAuth is seen in use, mirroring
     * src/config.py:should_latch_oauth. Closes a one-way trap: an existing
     * OAuth user who switches to IMAP would otherwise lose the option behind
     * them, and on Android signing out clears the connected email, so the
     * evidence is gone for good rather than merely hidden. Cheap enough to
     * call on every Settings open -- it writes only on the transition. */
    fun latchOauthIfInUse(context: Context) {
        if (isOauthUnlocked(context)) return
        if (getSavedMailBackend(context) == MAIL_BACKEND_GMAIL_OAUTH ||
            getConnectedAccountEmail(context) != null
        ) {
            setOauthUnlocked(context, true)
        }
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

    /** Did the last attempt to actually reach the mailbox succeed?
     *
     * null means no attempt has ever been made — saved credentials on their
     * own prove nothing, and until this release nothing in the app recorded
     * that a connection had ever worked: "Connected as …" only ever meant
     * "an address is stored". This is the fact behind the banner's status
     * dot, so an untested account can be told apart from a working one.
     *
     * A boolean and a timestamp, nothing else. No host, no address, and
     * above all no credential ever goes in here. */
    fun getLastConnectionOk(context: Context): Boolean? =
        if (!prefs(context).contains(KEY_LAST_CONNECTION_OK)) null
        else prefs(context).getBoolean(KEY_LAST_CONNECTION_OK, false)

    fun getLastConnectionAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_CONNECTION_AT, 0L)

    fun setLastConnectionResult(context: Context, ok: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_LAST_CONNECTION_OK, ok)
            .putLong(KEY_LAST_CONNECTION_AT, System.currentTimeMillis())
            .apply()
    }

    /** Forget any verdict — for when the account itself changes underneath
     * it (password forgotten, backend switched), where keeping the old
     * verdict would vouch for credentials that are no longer the ones in
     * use. */
    fun clearLastConnectionResult(context: Context) {
        prefs(context).edit()
            .remove(KEY_LAST_CONNECTION_OK)
            .remove(KEY_LAST_CONNECTION_AT)
            .apply()
    }

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
