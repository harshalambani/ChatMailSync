package com.wamailsync.app

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
 *
 * IMAP app-password support: unlike the OAuth access token, the IMAP
 * password deliberately never travels through inputData. WorkManager
 * persists a request's Data to an on-disk Room database (WorkDatabase) for
 * the lifetime of the request, unencrypted — fine for a short-lived OAuth
 * token, not acceptable for a durable account password. Instead, only the
 * backend name goes in KEY_MAIL_BACKEND; when it's MAIL_BACKEND_IMAP this
 * worker reads host/port/email from AppPrefs and the password from
 * SecretStore (Keystore-encrypted) itself, inside withDispatcherIO, each
 * time it runs.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_MAIL_BACKEND = "mail_backend"
        const val KEY_DRY_RUN = "dry_run"
        const val KEY_CHUNK_SIZE = "chunk_size"
        const val KEY_TRIGGER = "trigger"
        const val KEY_CHAT_FILTER = "chat_filter"
        const val KEY_RESULT = "result"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS_TEXT = "progress_text"
        const val KEY_PROGRESS_FRACTION = "progress_fraction"
        const val KEY_LOG_LINES = "log_lines"
        const val NOTIFICATION_CHANNEL_ID = "sync_channel"
        const val NOTIFICATION_ID = 1001
        /** Unique work name for a manual Home-triggered sync (real or dry
         * run) — lets MainActivity/SyncProgressScreen re-find this run's
         * WorkInfo by name after a process restart, instead of relying on
         * an in-memory UUID that's lost the moment the process dies. */
        const val UNIQUE_WORK_NAME_MANUAL_SYNC = "manual_sync"
    }

    override suspend fun doWork(): Result {
        val dryRun = inputData.getBoolean(KEY_DRY_RUN, false)
        val backend = inputData.getString(KEY_MAIL_BACKEND)
            ?: AppPrefs.resolveMailBackend(applicationContext)
        // A dry run touches no Gmail API, so no credentials are needed at
        // all — only a real sync requires them. Previously Home's dry-run
        // button called android_api.sync() directly on the click handler
        // (blocking the UI thread for however long a large export took, no
        // Stop button, no progress); routing it through this same Worker
        // fixes that for free, same as the real-sync path.
        val token = inputData.getString(KEY_ACCESS_TOKEN)
        if (backend == AppPrefs.MAIL_BACKEND_GMAIL_OAUTH && token == null && !dryRun) {
            return Result.failure(workDataOf(KEY_ERROR to "Missing access token"))
        }
        if (backend == AppPrefs.MAIL_BACKEND_IMAP && !dryRun) {
            val host = AppPrefs.getImapHost(applicationContext)
            val email = AppPrefs.getImapEmail(applicationContext)
            val password = SecretStore.getSecret(applicationContext, AppPrefs.getImapPasswordSecretKey())
            if (host.isBlank() || email.isBlank() || password.isNullOrEmpty()) {
                return Result.failure(
                    workDataOf(
                        KEY_ERROR to "No saved IMAP app password — open Settings > Mail account and save your email app password."
                    )
                )
            }
        }
        val chunkSize = inputData.getString(KEY_CHUNK_SIZE)
        val trigger = inputData.getString(KEY_TRIGGER) ?: "manual"
        val chatFilter = inputData.getString(KEY_CHAT_FILTER)

        setForeground(createForegroundInfo(if (dryRun) "Starting test run…" else "Starting sync…"))

        return coroutineScope {
            val androidApi = Python.getInstance().getModule("src.android_api")
            var lastFraction = -1f
            var lastText: String? = null
            // Milestone lines only (file started/finished, inbox scan
            // result) — not every "chunk" tick, which fires many times per
            // file and would spam a log rather than read like one. Capped
            // so this stays well under WorkManager Data's ~10KB limit for a
            // long sync. Mirrors (in spirit) the Windows GUI's rolling log
            // box (gui_worker._QueueHandler), which Android never had.
            val logLines = ArrayDeque<String>()
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
                        milestoneText(event)?.let { line ->
                            logLines.addLast(line)
                            while (logLines.size > 50) logLines.removeFirst()
                        }
                    }
                    lastText?.let { text ->
                        notify(text)
                        setProgress(
                            workDataOf(
                                KEY_PROGRESS_TEXT to text,
                                KEY_PROGRESS_FRACTION to lastFraction,
                                KEY_LOG_LINES to logLines.joinToString("\n"),
                            )
                        )
                    }
                    delay(250)
                }
            }
            try {
                val statsResult =
                    withDispatcherIO(backend, token, chunkSize, dryRun, trigger, chatFilter, androidApi)
                val label = if (dryRun) "Test run complete" else "Sync complete"
                notify("$label — ${statsResult.messagesSynced} message(s) synced")
                Result.success(workDataOf(KEY_RESULT to statsResult.format()))
            } catch (e: Exception) {
                notify("Sync failed: ${e.message}")
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.toString())))
            } finally {
                pollJob.cancel()
                // Reconcile watched-folder synced_file_policy against
                // whatever actually made it to processed/ this attempt (or
                // an earlier one) — a dry run never touches inbox/, so
                // there's nothing to reconcile. Best-effort: a failure here
                // must never turn a successful/failed sync result into
                // something else.
                if (!dryRun) {
                    try {
                        WatchFolderWorker.applyPendingSyncedFilePolicies(applicationContext)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private suspend fun withDispatcherIO(
        backend: String,
        token: String?,
        chunkSize: String?,
        dryRun: Boolean,
        trigger: String,
        chatFilter: String?,
        androidApi: com.chaquo.python.PyObject,
    ): SyncStatsResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
        // Built fresh on every run, never held past this function — the IMAP
        // password is read from SecretStore here (not passed in), so it
        // never sits in a field, a log, or WorkManager's Data.
        val transport = when {
            dryRun -> null
            backend == AppPrefs.MAIL_BACKEND_IMAP -> {
                val host = AppPrefs.getImapHost(applicationContext)
                val port = AppPrefs.getImapPort(applicationContext)
                val email = AppPrefs.getImapEmail(applicationContext)
                val password = SecretStore.getSecret(applicationContext, AppPrefs.getImapPasswordSecretKey())
                Python.getInstance().getModule("src.mail_client")
                    .callAttr("build_imap_transport", host, port, email, password)
            }
            else -> token?.let {
                Python.getInstance().getModule("src.mail_client").callAttr("set_token", it)
            }
        }
        try {
            val result = androidApi.callAttr("sync", transport, chunkSize, dryRun, chatFilter, null, trigger)
            SyncStatsResult.from(result)
        } finally {
            if (backend == AppPrefs.MAIL_BACKEND_IMAP && transport != null) {
                try {
                    transport.callAttr("close")
                } catch (_: Exception) {
                    // Best-effort logout — the sync result itself is unaffected.
                }
            }
        }
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

    /** Milestone-only subset of progressText's events, for the scrollable
     * log pane — deliberately excludes "chunk" (fires many times per file;
     * that's what the single-line live progress text is for). */
    private fun milestoneText(event: com.chaquo.python.PyObject): String? {
        val type = try {
            event.callAttr("get", "type")?.toString()
        } catch (_: Exception) {
            null
        } ?: return null
        return when (type) {
            "files_total" -> {
                val n = eventGet(event, "n")
                if (n == "0") "Inbox is empty" else "Found $n file(s) to sync"
            }
            "syncing" -> "Starting: ${eventGet(event, "name")}"
            "file_done" -> "Finished ${eventGet(event, "done")} / ${eventGet(event, "total")} files"
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
            .setContentTitle("WA Mail Sync")
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
