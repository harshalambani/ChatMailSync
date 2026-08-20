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
object Migration {

    /** The extension the file picker suggests; the bundle is a zip underneath. */
    const val SUFFIX = ".cmsbackup"

    /** The MIME type for CreateDocument/OpenDocument. Deliberately generic:
     *  a custom type would leave the file un-openable by anything, including
     *  the picker the user restores from. */
    const val MIME_TYPE = "application/octet-stream"

    fun suggestedFileName(): String {
        val stamp = android.text.format.DateFormat.format("yyyy-MM-dd", System.currentTimeMillis())
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

            val counts = result.callAttr("get", "counts")
            val chats = counts.callAttr("get", "chats").toString().toIntOrNull() ?: 0
            val hashes = counts.callAttr("get", "hashes").toString().toIntOrNull() ?: 0
            return "Backup saved — ${plural(chats, "chat")}, ${plural(hashes, "message")} " +
                "already sent. Your mail password is not in it; the new phone will ask once."
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
            if (result.callAttr("get", "already_imported").toBoolean()) {
                return "That backup has already been restored on this phone. Nothing changed."
            }

            applySettings(context, result.callAttr("get", "settings_json").toString())

            val chats = result.callAttr("get", "chats_added").toString().toIntOrNull() ?: 0
            val hashes = result.callAttr("get", "hashes_added").toString().toIntOrNull() ?: 0
            return "Restored ${plural(chats, "chat")} and ${plural(hashes, "message")} of " +
                "history — those will not be sent again. Enter your mail password once to finish."
        } catch (e: Exception) {
            return "That backup could not be restored: ${e.message}"
        } finally {
            staged.delete()
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
}
