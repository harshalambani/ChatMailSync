package com.wagmailsync.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phase A4: runs one real (non-dry-run) sync pass — inbox -> parse -> dedup
 * -> Gmail insert -> processed — via the shared Python core, as a foreground
 * service so Android doesn't kill it mid-sync. The access token is handed in
 * as plain WorkManager input data: short-lived (Google access tokens expire
 * in ~1h) and this worker always runs immediately after being enqueued from
 * a live Activity that just held the token, never persisted or scheduled for
 * later.
 *
 * Phase A5: reports live progress by polling android_api.get_progress() on a
 * timer while sync() runs on another thread — Chaquopy has no supported way
 * for Python to call back into an arbitrary Kotlin object as a function (a
 * Kotlin object with a __call__ method raised "is not callable" when tried
 * in Phase A4), so a push-callback bridge isn't an option.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_DRY_RUN = "dry_run"
        const val KEY_CHUNK_SIZE = "chunk_size"
        const val KEY_RESULT = "result"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS_TEXT = "progress_text"
        const val KEY_PROGRESS_FRACTION = "progress_fraction"
        const val NOTIFICATION_CHANNEL_ID = "sync_channel"
        const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result {
        val token = inputData.getString(KEY_ACCESS_TOKEN)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing access token"))
        val dryRun = inputData.getBoolean(KEY_DRY_RUN, false)
        val chunkSize = inputData.getString(KEY_CHUNK_SIZE)

        setForeground(createForegroundInfo("Starting sync…"))

        return coroutineScope {
            val androidApi = Python.getInstance().getModule("src.android_api")
            var lastFraction = -1f
            var lastText: String? = null
            val pollJob = launch(Dispatchers.IO) {
                // Check immediately (not delay-then-check): a quick sync —
                // e.g. all files already up to date, nothing but dedup
                // checks to do — can finish inside the first poll interval,
                // and a leading delay meant its "syncing"/"file_done" events
                // were never read before the job completed. Drain *every*
                // event queued since the last check (get_progress_events()),
                // not just the newest one, so a burst of several files'
                // worth of events between polls isn't collapsed down to a
                // single stale snapshot.
                while (isActive) {
                    val events = try {
                        androidApi.callAttr("get_progress_events").asList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    for (event in events) {
                        eventFraction(event)?.let { lastFraction = it }
                        progressText(event)?.let { lastText = it }
                    }
                    lastText?.let { text ->
                        notify(text)
                        setProgress(
                            workDataOf(
                                KEY_PROGRESS_TEXT to text,
                                KEY_PROGRESS_FRACTION to lastFraction,
                            )
                        )
                    }
                    delay(250)
                }
            }
            try {
                val statsResult = withDispatcherIO(token, chunkSize, dryRun, androidApi)
                notify("Sync complete — ${statsResult.messagesSynced} message(s) synced")
                Result.success(workDataOf(KEY_RESULT to statsResult.format()))
            } catch (e: Exception) {
                notify("Sync failed: ${e.message}")
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.toString())))
            } finally {
                pollJob.cancel()
            }
        }
    }

    private suspend fun withDispatcherIO(
        token: String,
        chunkSize: String?,
        dryRun: Boolean,
        androidApi: com.chaquo.python.PyObject,
    ): SyncStatsResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val transport = Python.getInstance().getModule("src.gmail_client").callAttr("set_token", token)
        val result = androidApi.callAttr("sync", transport, chunkSize, dryRun, null, null)
        SyncStatsResult.from(result)
    }

    private fun progressText(event: com.chaquo.python.PyObject): String? {
        val type = try {
            event.callAttr("get", "type")?.toString()
        } catch (_: Exception) {
            null
        } ?: return null
        return when (type) {
            "files_total" -> {
                val n = eventGet(event, "n")
                if (n == "0") "Inbox is empty" else "Found $n file(s)…"
            }
            "syncing" -> "Syncing: ${eventGet(event, "name")}"
            "file_done" -> "${eventGet(event, "done")} / ${eventGet(event, "total")} files"
            // "chunk" carries the true whole-sync percentage — the local
            // engine already knows the total number of *new* messages before
            // any network call is made (a parse+dedup pre-scan run upfront),
            // so this advances continuously even while one large chat's
            // chunks are still being pushed, instead of freezing at a
            // file-count fraction for however long that one file takes.
            "chunk" -> "Syncing: ${eventGet(event, "name")} — " +
                "${eventGet(event, "msgs_done")} / ${eventGet(event, "total_msgs")} messages"
            else -> null
        }
    }

    private fun eventFraction(event: com.chaquo.python.PyObject): Float? {
        val type = try {
            event.callAttr("get", "type")?.toString()
        } catch (_: Exception) {
            null
        }
        return when (type) {
            "chunk" -> {
                val done = eventGet(event, "global_done").toIntOrNull() ?: return null
                val total = eventGet(event, "global_total").toIntOrNull() ?: return null
                if (total > 0) done.toFloat() / total.toFloat() else null
            }
            // Fallback for spans with no chunk data yet (e.g. before the
            // first network call, or a run that's all dedup-skips with
            // nothing to push) — file-count is coarser but better than
            // nothing.
            "file_done" -> {
                val done = eventGet(event, "done").toIntOrNull() ?: return null
                val total = eventGet(event, "total").toIntOrNull() ?: return null
                if (total > 0) done.toFloat() / total.toFloat() else null
            }
            else -> null
        }
    }

    private fun eventGet(event: com.chaquo.python.PyObject, key: String): String =
        try {
            event.callAttr("get", key).toString()
        } catch (_: Exception) {
            "?"
        }

    private fun notify(text: String) {
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("WhatsApp Chat Sync to Gmail")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

    private fun createForegroundInfo(text: String): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Sync",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val notification = buildNotification(text)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}

/** Formats a raw android_api.sync() result the same way for the real-sync
 * worker output and the Home screen's dry-run result banner. */
fun formatSyncStats(result: com.chaquo.python.PyObject): String =
    SyncStatsResult.from(result).format()

/**
 * Mirrors SyncStats.__str__ (src/sync_manager.py) so the Android result
 * screen shows the same breakdown as the Windows GUI's log box, instead of
 * a raw Python dict repr.
 */
private data class SyncStatsResult(
    val filesFound: Int,
    val filesSynced: Int,
    val filesSkipped: Int,
    val filesFailed: Int,
    val messagesParsed: Int,
    val messagesSynced: Int,
    val messagesSkipped: Int,
    val chatsRecovered: Int,
    val errors: List<String>,
    val stopped: Boolean,
) {
    fun format(): String {
        val lines = mutableListOf(
            if (stopped) "Stopped early — $messagesSynced message(s) synced" else "Done",
            "Files   : found=$filesFound  synced=$filesSynced  skipped=$filesSkipped  failed=$filesFailed",
            "Messages: parsed=$messagesParsed  synced=$messagesSynced  skipped=$messagesSkipped",
        )
        if (chatsRecovered > 0) lines.add("Recovered $chatsRecovered interrupted run(s)")
        if (errors.isNotEmpty()) {
            lines.add("Errors:")
            errors.forEach { lines.add("  - $it") }
        }
        return lines.joinToString("\n")
    }

    companion object {
        fun from(result: com.chaquo.python.PyObject): SyncStatsResult {
            fun intOf(key: String): Int =
                try { result.callAttr("get", key)?.toString()?.toIntOrNull() ?: 0 } catch (_: Exception) { 0 }

            val errorsObj = try { result.callAttr("get", "errors") } catch (_: Exception) { null }
            val errors = try {
                errorsObj?.asList()?.map { it.toString() } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            return SyncStatsResult(
                filesFound = intOf("files_found"),
                filesSynced = intOf("files_synced"),
                filesSkipped = intOf("files_skipped"),
                filesFailed = intOf("files_failed"),
                messagesParsed = intOf("messages_parsed"),
                messagesSynced = intOf("messages_synced"),
                messagesSkipped = intOf("messages_skipped"),
                stopped = try { result.callAttr("get", "stopped")?.toString() == "True" } catch (_: Exception) { false },
                chatsRecovered = intOf("chats_recovered"),
                errors = errors,
            )
        }
    }
}
