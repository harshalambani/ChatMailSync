package com.chatmailsync.app

import android.content.Context
import android.net.Uri
import com.chaquo.python.Python
import org.json.JSONObject
import java.io.File

/**
 * Moving an install to a new phone.
 *
 * The decision this exists to serve: the app is write-only and the mailbox *is*
 * the archive, so a new phone starting from nothing would still work -- it would
 * simply mail every message a second time, into a mailbox that has no conflict
 * resolution because we deliberately have none. What cannot be rebuilt from the
 * mailbox is the record of which messages have already been sent. That record,
 * plus the handful of preferences worth retyping, is what crosses.
 *
 * Kotlin owns the file and Python owns the contents. SAF hands us a content://
 * URI, which Python cannot open, so the shape below is always the same: copy
 * between the URI and a cache file, and let src/migration.py do everything that
 * involves knowing what a bundle is. That keeps one implementation of the merge
 * rules for both front-ends rather than two that agree until they don't.
 */
/** Named for meaning, not colour -- a pill's tone is never the only thing
 *  that says what it means, its label already does, but this is what a
 *  renderer maps to an actual colour pair. */
enum class BackupPillTone { GOOD, WARN, BAD }

/** What [Migration.backupPillState] returns: the words for the pill and the
 *  tone to render them in. */
data class BackupPillInfo(val label: String, val tone: BackupPillTone)

object Migration {

    /** The extension the file picker suggests; the bundle is a zip underneath. */
    const val SUFFIX = ".cmsbackup"

    /** The MIME type for CreateDocument/OpenDocument. Deliberately generic:
     *  a custom type would leave the file un-openable by anything, including
     *  the picker the user restores from. */
    const val MIME_TYPE = "application/octet-stream"

    fun suggestedFileName(): String {
        val stamp = android.text.format.DateFormat.format("yyyy-MM-dd-HHmm", System.currentTimeMillis())
        return "chat-mail-sync-$stamp$SUFFIX"
    }

    private fun api() = Python.getInstance().getModule("src.android_api")

    /**
     * The preferences worth carrying, as the JSON string the bridge takes.
     *
     * Read this list against [migration._PORTABLE_SETTINGS] on the Python side:
     * that is the allow-list, and anything not on it is dropped there even if it
     * is offered here. The password is not offered here either -- it lives in
     * the Keystore, sealed to *this* device, and a portable copy of it would be
     * a plaintext password in a file people mail to themselves.
     */
    private fun currentSettings(context: Context): String = JSONObject().apply {
        put("chunk_size", AppPrefs.getChunkSize(context))
        put("watch_interval_minutes", AppPrefs.getWatchIntervalMinutes(context))
        put("synced_file_policy", AppPrefs.getSyncedFilePolicy(context))
        put("theme_mode", AppPrefs.getThemeMode(context))
        put("dry_run_default", AppPrefs.isDryRunDefault(context))
        put("mail_backend", AppPrefs.resolveMailBackend(context))
        put("imap_provider", AppPrefs.getImapProvider(context))
        put("imap_host", AppPrefs.getImapHost(context))
        put("imap_port", AppPrefs.getImapPort(context))
        put("imap_email", AppPrefs.getImapEmail(context))
    }.toString()

    /** How old a backup has to be before the app stops treating it as cover.
     *
     * Thirty days is a judgement, not a rule: it is long enough that someone
     * who keeps up is never nagged, and short enough that the re-mailing a
     * lost record would cause is bounded by roughly a month of chats. */
    const val BACKUP_STALE_AFTER_DAYS = 30L

    fun backupIsStale(atMillis: Long, now: Long = System.currentTimeMillis()): Boolean =
        atMillis <= 0L || now - atMillis > BACKUP_STALE_AFTER_DAYS * 24L * 60L * 60L * 1000L

    /**
     * The status pill shown on Settings' "Backup & restore" nav row.
     *
     * Pure so it can be unit-tested without Compose or a Context:
     * [lastBackupAt] is [AppPrefs.getLastBackupAt], [now] defaults to the
     * real clock but is overridable for the exactly-30-days boundary test.
     * The "no backup at all" case is checked before [backupIsStale] so its
     * own `atMillis <= 0L` branch is never what answers it here -- the two
     * still agree (both call it BAD/stale), this just picks the more
     * specific label ("No backup" vs "Backup due").
     *
     * A future [lastBackupAt] (clock skew, or a restored device with a
     * clock behind the backup's own timestamp) is deliberately treated as
     * GOOD rather than crashing or reporting "due"/"No backup": the backup
     * demonstrably exists, so understating that would be its own kind of
     * lie.
     */
    fun backupPillState(lastBackupAt: Long, now: Long = System.currentTimeMillis()): BackupPillInfo = when {
        lastBackupAt <= 0L -> BackupPillInfo("No backup", BackupPillTone.BAD)
        backupIsStale(lastBackupAt, now) -> BackupPillInfo("Backup due", BackupPillTone.WARN)
        else -> BackupPillInfo(
            "Backed up " + java.text.SimpleDateFormat(
                "d MMM", java.util.Locale.getDefault(),
            ).format(java.util.Date(lastBackupAt)),
            BackupPillTone.GOOD,
        )
    }

    /** "Last backup: 28 Aug 2026", or the plain fact that there isn't one. */
    fun describeLastBackup(atMillis: Long): String =
        if (atMillis <= 0L) "No backup saved yet."
        else "Last backup: " + java.text.SimpleDateFormat(
            "d MMM yyyy", java.util.Locale.getDefault(),
        ).format(java.util.Date(atMillis))

    /** Write a backup to the picked [uri]. Returns the line to show the user. */
    fun exportTo(context: Context, uri: Uri): String {
        val staged = File(context.cacheDir, "backup-out$SUFFIX")
        try {
            val result = api().callAttr(
                "export_backup",
                staged.absolutePath,
                currentSettings(context),
                BuildConfig.VERSION_NAME,
            )
            if (!result.callAttr("get", "ok").toBoolean()) {
                return result.callAttr("get", "error").toString()
            }
            context.contentResolver.openOutputStream(uri)?.use { out ->
                staged.inputStream().use { it.copyTo(out) }
            } ?: return "That location could not be written to."

            // Only here, where the bytes are known to have reached the file
            // the user picked -- a stamp written on "Save a backup" being
            // tapped would tell them they are protected when they are not.
            AppPrefs.setLastBackupAt(context)

            val counts = result.callAttr("get", "counts")
            val chats = counts.callAttr("get", "chats").toString().toIntOrNull() ?: 0
            val hashes = counts.callAttr("get", "hashes").toString().toIntOrNull() ?: 0
            return "Backup saved — ${plural(chats, "chat")}, ${plural(hashes, "message")} " +
                "already sent. Your mail password is not in it; the restored device asks once."
        } catch (e: Exception) {
            return "The backup could not be saved: ${e.message}"
        } finally {
            staged.delete()
        }
    }

    /**
     * Merge the backup at [uri] into this install, then apply its preferences.
     *
     * Merge, never replace: an older backup must not be able to delete newer
     * history, because deleting history here does not lose data, it re-sends it.
     */
    fun importFrom(context: Context, uri: Uri): String {
        val staged = File(context.cacheDir, "backup-in$SUFFIX")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { input.copyTo(it) }
            } ?: return "That file could not be opened."

            val result = api().callAttr("import_backup", staged.absolutePath)
            if (!result.callAttr("get", "ok").toBoolean()) {
                return result.callAttr("get", "error").toString()
            }
            // Recorded before the early return as well as after it: a bundle
            // that was already restored here still covers this device's
            // history, and the second attempt is exactly when someone is
            // checking whether they are protected.
            recordCoverFrom(context, result)

            if (result.callAttr("get", "already_imported").toBoolean()) {
                return "That backup has already been restored on this phone. Nothing changed."
            }

            applySettings(context, result.callAttr("get", "settings_json").toString())

            val chats = result.callAttr("get", "chats_added").toString().toIntOrNull() ?: 0
            val hashes = result.callAttr("get", "hashes_added").toString().toIntOrNull() ?: 0
            // Named only when there are any. A per-chat floor is a decision the
            // user made by hand and would not think to make again, so it has to
            // be visible that it survived -- but a phone that never set one
            // should not be told about a feature it does not use.
            val cutoffs = result.callAttr("get", "cutoffs_added").toString().toIntOrNull() ?: 0
            val carried =
                if (cutoffs == 0) ""
                else " ${plural(cutoffs, "per-chat cutoff date")} came with it."
            // "Enter your mail password once to finish" is a next step, not a
            // fact -- and it is only a next step when there is no password yet.
            // Restoring onto a phone that is already connected was telling the
            // user to supply something the app already had.
            val connected =
                if (AppPrefs.resolveMailBackend(context) == AppPrefs.MAIL_BACKEND_IMAP)
                    AppPrefs.hasImapPassword(context)
                else AppPrefs.getConnectedAccountEmail(context) != null
            val finish = if (connected) "" else " Enter your mail password once to finish."
            return "Restored ${plural(chats, "chat")} and ${plural(hashes, "message")} of " +
                "history — those will not be sent again.$carried$finish"
        } catch (e: Exception) {
            return "That backup could not be restored: ${e.message}"
        } finally {
            staged.delete()
        }
    }

    /**
     * Date this install's cover from the bundle just restored.
     *
     * A restore leaves the phone protected by the file it was restored from,
     * so the last-backup stamp moves to that file's own creation date. Without
     * this, a phone that had just been rebuilt from a backup was told "No
     * backup yet -- a reset makes the app mail every chat again" directly
     * above the fifty messages the restore had stopped it from re-mailing.
     *
     * Never moved backwards: restoring an old bundle onto a device that has a
     * newer one must not make it look less protected than it is.
     */
    private fun recordCoverFrom(context: Context, result: com.chaquo.python.PyObject) {
        val made = result.callAttr("get", "created_at_epoch_ms")
            ?.toString()?.toLongOrNull() ?: 0L
        if (made > 0L && made > AppPrefs.getLastBackupAt(context)) {
            AppPrefs.setLastBackupAt(context, made)
        }
    }

    /** What is in a backup, without restoring it. Null if it cannot be read. */
    fun describe(context: Context, uri: Uri): String? {
        val staged = File(context.cacheDir, "backup-peek$SUFFIX")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { input.copyTo(it) }
            } ?: return null
            val info = api().callAttr("describe_backup", staged.absolutePath)
            if (!info.callAttr("get", "ok").toBoolean()) return null
            val made = info.callAttr("get", "created_at").toString().take(10)
            val chats = info.callAttr("get", "chats").toString().toIntOrNull() ?: 0
            return "From $made — ${plural(chats, "chat")}"
        } catch (e: Exception) {
            return null
        } finally {
            staged.delete()
        }
    }

    /**
     * Apply restored preferences, key by key rather than in a loop.
     *
     * Each one has its own typed setter with its own clamping (the watch
     * interval has a platform floor of 15 minutes; the port is an Int), and a
     * generic "write whatever came in" loop would bypass all of it on data that
     * arrived from a file anyone can edit.
     */
    private fun applySettings(context: Context, json: String) {
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            return
        }
        if (obj.has("chunk_size")) AppPrefs.setChunkSize(context, obj.optString("chunk_size"))
        if (obj.has("watch_interval_minutes")) {
            // Floored at the platform minimum on the way in. WorkManager will
            // not schedule below 15 minutes anyway, and a bundle is a file on
            // disk that anyone can edit.
            AppPrefs.setWatchIntervalMinutes(
                context,
                obj.optLong("watch_interval_minutes")
                    .coerceAtLeast(AppPrefs.MIN_WATCH_INTERVAL_MINUTES),
            )
        }
        if (obj.has("synced_file_policy")) {
            AppPrefs.setSyncedFilePolicy(context, obj.optString("synced_file_policy"))
        }
        if (obj.has("theme_mode")) AppPrefs.setThemeMode(context, obj.optString("theme_mode"))
        if (obj.has("dry_run_default")) {
            AppPrefs.setDryRunDefault(context, obj.optBoolean("dry_run_default"))
        }
        if (obj.has("mail_backend")) AppPrefs.setMailBackend(context, obj.optString("mail_backend"))
        if (obj.has("imap_provider")) {
            AppPrefs.setImapProvider(context, obj.optString("imap_provider"))
        }
        if (obj.has("imap_host")) AppPrefs.setImapHost(context, obj.optString("imap_host"))
        if (obj.has("imap_port")) AppPrefs.setImapPort(context, obj.optInt("imap_port", 993))
        if (obj.has("imap_email")) AppPrefs.setImapEmail(context, obj.optString("imap_email"))
        // Not restored, on purpose: the mail password stays on the old device,
        // so any saved verdict about a connection would be a green light on a
        // phone that cannot connect yet.
        AppPrefs.clearLastConnectionResult(context)
    }

    /**
     * The exact prefs [applySettings] can change on a restore -- and so the
     * exact prefs MainActivity's post-restore reload must re-read.
     *
     * The bug this exists to prevent: MainActivity holds each of these in a
     * `remember { mutableStateOf(AppPrefs.getX(context)) }` read once at
     * first composition (imapProvider, imapEmail, chunk size, watch
     * interval, synced-file policy, theme mode, dry-run default, mail
     * backend, host and port). [applySettings] writes straight to AppPrefs
     * and never told any of that remembered state to re-read, so after a
     * real restore (verified: force-stop and relaunch showed the restored
     * Yahoo account correctly) the still-live Compose state kept showing
     * the pre-restore values -- Mail account showed "Gmail" with a blank
     * email, and Save & connect there would have overwritten the just-
     * restored settings with form defaults.
     *
     * One list, used by both sides ([RestorableSettingsTest] asserts
     * MainActivity's own reload key list equals this one), so a key added
     * to [applySettings] and not to the reload fails a test instead of
     * quietly reintroducing this bug for that one field.
     */
    val RESTORABLE_SETTINGS_KEYS: List<String> = listOf(
        "chunk_size",
        "watch_interval_minutes",
        "synced_file_policy",
        "theme_mode",
        "dry_run_default",
        "mail_backend",
        "imap_provider",
        "imap_host",
        "imap_port",
        "imap_email",
    )

    /** The values behind [RESTORABLE_SETTINGS_KEYS], read fresh. */
    data class RestorableSettings(
        val chunkSize: String,
        val watchIntervalMinutes: Long,
        val syncedFilePolicy: String,
        val themeMode: String,
        val dryRunDefault: Boolean,
        val mailBackend: String,
        val imapProvider: String,
        val imapHost: String,
        val imapPort: Int,
        val imapEmail: String,
    )

    /**
     * Builds [RestorableSettings] from a generic key -> value [reader]
     * rather than a Context directly, so it can be unit-tested with a fake
     * map standing in for AppPrefs (see RestorableSettingsTest) with no
     * Robolectric involved. [readRestorableSettings] below is the real,
     * AppPrefs-backed caller MainActivity uses after a restore.
     *
     * Never reads the password: [AppPrefs.getImapPasswordSecretKey]'s key
     * is not in [RESTORABLE_SETTINGS_KEYS], and this function has no path
     * to SecretStore at all to read it even by mistake.
     */
    fun buildRestorableSettings(reader: (String) -> Any?): RestorableSettings = RestorableSettings(
        chunkSize = reader("chunk_size") as? String ?: "",
        watchIntervalMinutes = (reader("watch_interval_minutes") as? Long)
            ?: AppPrefs.MIN_WATCH_INTERVAL_MINUTES,
        syncedFilePolicy = reader("synced_file_policy") as? String ?: "",
        themeMode = reader("theme_mode") as? String ?: "system",
        dryRunDefault = (reader("dry_run_default") as? Boolean) ?: false,
        mailBackend = reader("mail_backend") as? String ?: "",
        imapProvider = reader("imap_provider") as? String ?: "",
        imapHost = reader("imap_host") as? String ?: "",
        imapPort = (reader("imap_port") as? Int) ?: 993,
        imapEmail = reader("imap_email") as? String ?: "",
    )

    /** [buildRestorableSettings], reading today's real AppPrefs values --
     *  what MainActivity calls after every restore attempt (success,
     *  failure or already-imported alike) to re-seed its remembered state.
     *  Harmless when nothing changed: reading unchanged AppPrefs back into
     *  the same state is a no-op, not a wipe to defaults. */
    fun readRestorableSettings(context: Context): RestorableSettings = buildRestorableSettings { key ->
        when (key) {
            "chunk_size" -> AppPrefs.getChunkSize(context)
            "watch_interval_minutes" -> AppPrefs.getWatchIntervalMinutes(context)
            "synced_file_policy" -> AppPrefs.getSyncedFilePolicy(context)
            "theme_mode" -> AppPrefs.getThemeMode(context)
            "dry_run_default" -> AppPrefs.isDryRunDefault(context)
            "mail_backend" -> AppPrefs.resolveMailBackend(context)
            "imap_provider" -> AppPrefs.getImapProvider(context)
            "imap_host" -> AppPrefs.getImapHost(context)
            "imap_port" -> AppPrefs.getImapPort(context)
            "imap_email" -> AppPrefs.getImapEmail(context)
            else -> null
        }
    }
}
