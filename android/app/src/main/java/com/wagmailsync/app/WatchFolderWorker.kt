package com.wagmailsync.app

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
    }

    override suspend fun doWork(): Result {
        val folderUriString = AppPrefs.getWatchedFolderUri(applicationContext)
            ?: return Result.success()

        val treeUri = Uri.parse(folderUriString)
        val folder = DocumentFile.fromTreeUri(applicationContext, treeUri)
            ?: return Result.success()

        val alreadyImported = AppPrefs.getImportedDocIds(applicationContext)
        val policy = AppPrefs.getSyncedFilePolicy(applicationContext)
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
                    applySyncedFilePolicy(policy, folder, doc)
                }
            }
        }

        if (importedCount == 0) {
            return Result.success(
                workDataOf(
                    KEY_IMPORTED_COUNT to 0,
                    KEY_RESULT_TEXT to "No new files found",
                )
            )
        }

        val resultText = triggerAutoSync(importedCount)
        return Result.success(
            workDataOf(
                KEY_IMPORTED_COUNT to importedCount,
                KEY_RESULT_TEXT to resultText,
            )
        )
    }

    /** Imported files sit in the local inbox until actually pushed to Gmail
     * — Home feedback made clear watched-folder automation should be
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
        val email = AppPrefs.getConnectedAccountEmail(applicationContext)
        if (email == null) {
            notify("Imported $importedCount new file(s) — open the app to connect Gmail and sync")
            return "Imported $importedCount new file(s) — connect Gmail in the app to sync"
        }

        val scopeString = "oauth2:" + GMAIL_SCOPES.joinToString(" ") { it.scopeUri }
        val token = try {
            withContext(Dispatchers.IO) {
                GoogleAuthUtil.getToken(applicationContext, Account(email, "com.google"), scopeString)
            }
        } catch (e: Exception) {
            notify("Imported $importedCount new file(s) — reconnect Gmail in the app to sync them")
            return "Imported $importedCount new file(s) — reconnect Gmail to sync"
        }

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(
                Data.Builder()
                    .putString(SyncWorker.KEY_ACCESS_TOKEN, token)
                    .putBoolean(SyncWorker.KEY_DRY_RUN, false)
                    .putString(SyncWorker.KEY_CHUNK_SIZE, AppPrefs.getChunkSize(applicationContext))
                    .putString(SyncWorker.KEY_TRIGGER, "watched_folder")
                    .build()
            )
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
        return "Imported $importedCount new file(s) — syncing to Gmail…"
    }

    /** Best-effort — a failure here (e.g. the user granted only read access
     * to the watched tree before write-permission persistence was added)
     * must not fail the whole import; the file is already safely copied
     * into inbox/ by this point regardless. */
    private fun applySyncedFilePolicy(policy: String, folder: DocumentFile, doc: DocumentFile) {
        try {
            when (policy) {
                "delete" -> doc.delete()
                "move" -> {
                    val syncedDir = folder.findFile("synced")?.takeIf { it.isDirectory }
                        ?: folder.createDirectory("synced")
                        ?: return
                    try {
                        DocumentsContract.moveDocument(
                            applicationContext.contentResolver,
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
                            applicationContext.contentResolver.openInputStream(doc.uri)?.use { input ->
                                applicationContext.contentResolver.openOutputStream(copy.uri)?.use { output ->
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
            // Leave the file in place; it's already imported either way.
        }
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
            .setContentTitle("WhatsApp Chat Sync to Gmail")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
