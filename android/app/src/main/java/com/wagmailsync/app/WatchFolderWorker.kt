package com.wagmailsync.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
        const val NOTIFICATION_CHANNEL_ID = "watch_folder_channel"
        const val NOTIFICATION_ID = 1002

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchFolderWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
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

        for (doc in folder.listFiles()) {
            if (!doc.isFile) continue
            val docId = doc.uri.toString()
            if (docId in alreadyImported) continue

            val outcome = ImportManager.importUri(applicationContext, doc.uri)
            if (outcome != null) {
                AppPrefs.addImportedDocId(applicationContext, docId)
                if (!outcome.alreadyQueued) importedCount++
            }
        }

        if (importedCount > 0) {
            notify("Imported $importedCount new file(s) from watched folder")
        }
        return Result.success()
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
            .setContentTitle("WA Chat Sync to Gmail")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
