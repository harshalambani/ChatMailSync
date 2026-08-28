package com.chatmailsync.app

import android.accounts.Account
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Optional background counterpart to the manual "Import a WhatsApp export"
 * button (Home feedback: "can we set an input folder, so you can keep
 * looking for changes there"). Off by default — user opts in per Settings,
 * since periodic background work has a real battery cost that's their call,
 * not ours, to make (per user's explicit "leave it as a user option").
 *
 * WorkManager's PeriodicWorkRequest has a hard 15-minute floor — this cannot
 * poll more often than that regardless of interval requested.
 */
class WatchFolderWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "watch_folder"
        const val UNIQUE_WORK_NAME_ONCE = "watch_folder_once"
        /** Unique name for the chained SyncWorker request triggered after an
         * import (see triggerAutoSync) — shared by both the manual "Sync
         * now" path and the periodic background watcher, so MainActivity can
         * observe live sync progress for either one the same way, whenever
         * the app happens to be open while one is running. */
        const val UNIQUE_WORK_NAME_AUTO_SYNC = "watch_folder_auto_sync"
        const val NOTIFICATION_CHANNEL_ID = "watch_folder_channel"
        const val NOTIFICATION_ID = 1002
        const val KEY_IMPORTED_COUNT = "imported_count"
        const val KEY_RESULT_TEXT = "result_text"

        /** [intervalMinutes] is clamped to WorkManager's 15-minute floor
         * regardless of what's passed in — Android enforces this
         * platform-side, not just as a WorkManager default. Uses UPDATE
         * (not KEEP) so changing the interval in Settings takes effect on
         * the existing periodic work rather than being ignored because a
         * request under this unique name already exists. */
        fun enqueue(context: Context, intervalMinutes: Long = AppPrefs.MIN_WATCH_INTERVAL_MINUTES) {
            val request = PeriodicWorkRequestBuilder<WatchFolderWorker>(
                maxOf(intervalMinutes, AppPrefs.MIN_WATCH_INTERVAL_MINUTES),
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        /** "Check now" — runs immediately regardless of the periodic
         * schedule or whether auto-watch is even turned on, as long as a
         * folder is chosen. REPLACE means tapping it again while one run is
         * still in flight restarts rather than queuing a second one. */
        fun enqueueOnce(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME_ONCE,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WatchFolderWorker>().build(),
            )
        }

        /** Called from SyncWorker after every real (non-dry-run) sync attempt,
         * success or failure — reconciles AppPrefs' pending_synced_files ledger
         * against what's actually still sitting in inbox/. A pending entry
         * whose file is no longer in inbox/ was moved to processed/ by
         * sync_manager.py, which only ever does that on confirmed delivery or
         * a dedup-skip (never on a parse failure) — so "gone from inbox/" is
         * the same ground truth already relied on elsewhere as "delivered",
         * not a new invented signal. Only once that's true do we act on
         * synced_file_policy for that file's original watched-folder source.
         *
         * A pending entry whose file is still in inbox/ (sync failed, was
         * skipped, or hasn't run yet) is left untouched — safe to retry on
         * the next call, and never mistaken for "must have delivered by now". */
        fun applyPendingSyncedFilePolicies(context: Context) {
            val pending = AppPrefs.getPendingSyncedFiles(context)
            if (pending.isEmpty()) return

            val delivered = pending.keys.filter { name ->
                !ChatMailApplication.inboxDir(context).resolve(name).exists()
            }
            if (delivered.isEmpty()) return

            val policy = AppPrefs.getSyncedFilePolicy(context)
            // Resolve the watched folder once per call, not once per file —
            // there is only ever one global watched folder, not one per
            // pending entry (see AppPrefs.getPendingSyncedFiles' doc comment).
            val folder = if (policy != "leave") {
                AppPrefs.getWatchedFolderUri(context)?.let { uriString ->
                    try {
                        DocumentFile.fromTreeUri(context, Uri.parse(uriString))
                    } catch (_: Exception) {
                        null
                    }
                }
            } else {
                null
            }

            for (name in delivered) {
                val sourceUri = pending.getValue(name)
                // Always clear the ledger entry regardless of what follows —
                // the file is confirmed delivered either way, and leaving a
                // resolved entry around would just mean re-attempting a
                // move/delete against a doc that may no longer exist next time.
                AppPrefs.removePendingSyncedFile(context, name)

                if (policy == "leave" || folder == null) continue
                val doc = try {
                    DocumentFile.fromSingleUri(context, Uri.parse(sourceUri))
                } catch (_: Exception) {
                    null
                }
                // SAF permission revoked, source already gone, etc. — nothing
                // more to do; the file is safely delivered either way.
                if (doc == null || !doc.exists()) continue
                applySyncedFilePolicy(context, policy, folder, doc)
            }
        }

        /** Best-effort — a failure here (e.g. the user granted only read access
         * to the watched tree before write-permission persistence was added)
         * must not affect the sync result itself; the file is already safely
         * delivered by this point regardless. */
        private fun applySyncedFilePolicy(context: Context, policy: String, folder: DocumentFile, doc: DocumentFile) {
            try {
                when (policy) {
                    "delete" -> doc.delete()
                    "move" -> {
                        val syncedDir = folder.findFile("synced")?.takeIf { it.isDirectory }
                            ?: folder.createDirectory("synced")
                            ?: return
                        try {
                            DocumentsContract.moveDocument(
                                context.contentResolver,
                                doc.uri,
                                folder.uri,
                                syncedDir.uri,
                            )
                        } catch (_: Exception) {
                            // Some SAF providers don't support moveDocument;
                            // fall back to a manual copy + delete of the source.
                            val copy = syncedDir.createFile(
                                doc.type ?: "application/octet-stream",
                                doc.name ?: "export",
                            )
                            if (copy != null) {
                                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                                    context.contentResolver.openOutputStream(copy.uri)?.use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                doc.delete()
                            }
                        }
                    }
                    // "leave" (default): nothing to do.
                }
            } catch (_: Exception) {
                // Leave the file in place; it's already delivered either way.
            }
        }
    }

    override suspend fun doWork(): Result {
        val folderUriString = AppPrefs.getWatchedFolderUri(applicationContext)
            ?: return Result.success()

        val treeUri = Uri.parse(folderUriString)
        val folder = DocumentFile.fromTreeUri(applicationContext, treeUri)
            ?: return Result.success()

        val alreadyImported = AppPrefs.getImportedDocIds(applicationContext)
        var importedCount = 0

        // listFiles() only sees the watched tree's immediate children, not
        // recursing into subfolders — this is what already keeps a "move to
        // synced/" policy from re-scanning its own destination folder on the
        // next run without any extra filtering needed.
        for (doc in folder.listFiles()) {
            if (!doc.isFile) continue
            val docId = doc.uri.toString()
            if (docId in alreadyImported) continue

            val outcome = ImportManager.importUri(applicationContext, doc.uri)
            if (outcome != null) {
                AppPrefs.addImportedDocId(applicationContext, docId)
                if (!outcome.alreadyQueued) {
                    importedCount++
                    // synced_file_policy is applied only once this file is
                    // actually delivered (see applyPendingSyncedFilePolicies),
                    // not here at import time — applying "move"/"delete" right
                    // after import would relocate/erase the user's source zip
                    // even if delivery never happens (e.g. no mail account
                    // configured yet, see the importedCount==0 fallback below).
                    AppPrefs.addPendingSyncedFile(applicationContext, outcome.file.name, docId)
                }
            }
        }

        if (importedCount == 0) {
            // Nothing new this run, but inbox/ can still hold files that a
            // *previous* run already imported (and ledgered in
            // imported_doc_ids) yet never actually delivered — e.g. imported
            // before a mail account was configured. Without this check, every
            // subsequent "Sync now" reported a false "No new files found"
            // forever, since alreadyImported skips them here unconditionally.
            if (!ChatMailApplication.hasPendingInboxFiles(applicationContext)) {
                return Result.success(
                    workDataOf(
                        KEY_IMPORTED_COUNT to 0,
                        KEY_RESULT_TEXT to "No new files found",
                    )
                )
            }
        }

        val resultText = triggerAutoSync(importedCount)
        return Result.success(
            workDataOf(
                KEY_IMPORTED_COUNT to importedCount,
                KEY_RESULT_TEXT to resultText,
            )
        )
    }

    /** Imported files sit in the local inbox until actually pushed to the
     * user's mailbox — Home feedback made clear watched-folder automation should be
     * hands-off end to end, not "import automatically, then still have to
     * open the app and tap Sync now." This runs headless (no Activity, and
     * possibly after the app process was killed), so it can't use
     * AuthorizationClient's interactive consent flow. GoogleAuthUtil.getToken
     * is the classic blocking API that works from a plain Context and
     * returns a token non-interactively if the account already granted these
     * scopes, or throws (UserRecoverableAuthException / GoogleAuthException)
     * if interactive consent would be required — in which case this just
     * skips the auto-sync and tells the user to reconnect in the app,
     * instead of crashing or hanging. */
    private suspend fun triggerAutoSync(importedCount: Int): String {
        // "Imported N new file(s)" only makes sense when this run actually
        // imported something — when it's 0 and we're only here because
        // doWork() found an undelivered backlog from a previous run, say so
        // instead of the misleading "Imported 0 new file(s)".
        val lead = if (importedCount > 0) "Imported $importedCount new file(s)" else "Syncing previously imported file(s)"
        // "Rehearse without sending ... stays on until you turn it off" has to
        // mean every path, not just the Sync now button. This one was pinned to
        // false, so importing anything while the toggle was on quietly mailed it
        // for real -- the one promise the toggle makes, broken by the one path
        // the user is not watching.
        val dryRun = AppPrefs.isDryRunDefault(applicationContext)
        val backend = AppPrefs.resolveMailBackend(applicationContext)
        val dataBuilder = Data.Builder()
            .putBoolean(SyncWorker.KEY_DRY_RUN, dryRun)
            .putString(SyncWorker.KEY_CHUNK_SIZE, AppPrefs.getChunkSize(applicationContext))
            .putString(SyncWorker.KEY_TRIGGER, "watched_folder")
            .putString(SyncWorker.KEY_MAIL_BACKEND, backend)

        if (dryRun) {
            // A rehearsal never opens a connection, so it needs no password and
            // no token -- and must not be blocked by the absence of either.
        } else if (backend == AppPrefs.MAIL_BACKEND_IMAP) {
            // No token to fetch — SyncWorker reads host/email from AppPrefs
            // and the password from SecretStore itself. Only check here that
            // *something* is saved, so a clear notification fires instead of
            // silently enqueuing a run that's guaranteed to fail.
            if (!AppPrefs.hasImapPassword(applicationContext) || AppPrefs.getImapHost(applicationContext).isBlank()) {
                notify("$lead — open Settings > Mail account and save your IMAP app password to sync")
                return "$lead — save an IMAP app password in Settings > Mail account to sync"
            }
        } else {
            val email = AppPrefs.getConnectedAccountEmail(applicationContext)
            if (email == null) {
                notify("$lead — open the app to connect Gmail and sync")
                return "$lead — connect Gmail in the app to sync"
            }

            val scopeString = "oauth2:" + GMAIL_SCOPES.joinToString(" ") { it.scopeUri }
            val token = try {
                withContext(Dispatchers.IO) {
                    GoogleAuthUtil.getToken(applicationContext, Account(email, "com.google"), scopeString)
                }
            } catch (e: Exception) {
                notify("$lead — reconnect Gmail in the app to sync them")
                return "$lead — reconnect Gmail to sync"
            }
            dataBuilder.putString(SyncWorker.KEY_ACCESS_TOKEN, token)
        }

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(dataBuilder.build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // Unique (not plain enqueue): lets MainActivity observe this run's
        // live progress via getWorkInfosForUniqueWorkFlow(), the same way
        // Home already observes its own manual real-sync — REPLACE means a
        // later auto-sync (e.g. the next periodic tick) simply supersedes
        // whatever the last one's WorkInfo showed.
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME_AUTO_SYNC,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        // SyncWorker's own foreground-service notification covers the actual
        // sync result ("Sync complete — N synced" / "Sync failed: ...") —
        // no separate notification needed here.
        // Not "syncing to Gmail": this line is reached on both branches, so an
        // IMAP user archiving into Fastmail was being told their mail was on its
        // way to Gmail. The backend-specific messages above are the place to
        // name a provider; this one is shared.
        return if (dryRun) "$lead — test run only, nothing will be sent"
        else "$lead — syncing to your mailbox…"
    }

    private fun notify(text: String) {
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Watched folder",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Chat Mail Sync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
