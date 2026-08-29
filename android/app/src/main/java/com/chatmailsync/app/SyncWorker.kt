package com.chatmailsync.app

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
 * -> mail insert/append -> processed — via the shared Python core, as a foreground
 * service so Android doesn't kill it mid-sync.
 *
 * Phase A5: reports live progress by polling android_api.get_progress() on a
 * timer while sync() runs on another thread — Chaquopy has no supported way
 * for Python to call back into an arbitrary Kotlin object as a function (a
 * Kotlin object with a __call__ method raised "is not callable" when tried
 * in Phase A4), so a push-callback bridge isn't an option.
 *
 * The IMAP password deliberately never travels through inputData.
 * WorkManager persists a request's Data to an on-disk Room database
 * (WorkDatabase) for the lifetime of the request, unencrypted — not
 * acceptable for a durable account password. Instead, only the backend name
 * goes in KEY_MAIL_BACKEND; this worker reads host/port/email from AppPrefs
 * and the password from SecretStore (Keystore-encrypted) itself, inside
 * withDispatcherIO, each time it runs.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_MAIL_BACKEND = "mail_backend"
        const val KEY_DRY_RUN = "dry_run"
        const val KEY_CHUNK_SIZE = "chunk_size"
        const val KEY_TRIGGER = "trigger"
        const val KEY_CHAT_FILTER = "chat_filter"
        const val KEY_RESULT = "result"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS_TEXT = "progress_text"
        const val KEY_PROGRESS_FRACTION = "progress_fraction"
        /** The same fraction as a whole number, or -1 when there is no honest
         * one yet. Rounded in src/progress.py rather than here so the
         * collapsed sync bar and the Windows window can never disagree by a
         * percentage point. */
        const val KEY_PROGRESS_PERCENT = "progress_percent"
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
        // A dry run touches no mail server, so no credentials are needed at
        // all — only a real sync requires them. Previously Home's dry-run
        // button called android_api.sync() directly on the click handler
        // (blocking the UI thread for however long a large export took, no
        // Stop button, no progress); routing it through this same Worker
        // fixes that for free, same as the real-sync path.
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
            // What was last handed to WorkManager/the notification, as
            // distinct from what the last event said. The poll below runs on a
            // fixed 250ms tick whether or not any event arrived, and it used
            // to re-post the identical payload every time: setProgress() is a
            // Data write into WorkManager's Room database and notify()
            // rebuilds a system notification, so a single chat sitting on one
            // chunk for 3.5 minutes cost ~840 disk writes and as many
            // notification updates to say nothing new. Measured on device
            // 2026-08-07 via WM-WorkProgressUpdater in logcat.
            //
            // NaN rather than -1f as the "nothing posted yet" fraction, so the
            // first comparison is always unequal (NaN != NaN) without
            // colliding with a value an event could legitimately produce.
            var postedText: String? = null
            var postedFraction = Float.NaN
            var postedLog: String? = null
            val pollJob = launch(Dispatchers.IO) {
                // Check immediately (not delay-then-check): a quick sync —
                // e.g. all files already up to date, nothing but dedup
                // checks to do — can finish inside the first poll interval,
                // and a leading delay meant its progress was never read
                // before the job completed.
                //
                // What comes back is the whole state of the run so far, not
                // the events since the last look, so a slow tick can't drop
                // anything: the status line, the monotonic fraction and the
                // milestone log are all derived in src/progress.py, which
                // the Windows GUI renders from too. Kotlin's job here is to
                // move those three strings onto the notification and into
                // WorkManager's Data — not to decide what they say.
                while (isActive) {
                    val state = try {
                        androidApi.callAttr("progress_state")
                    } catch (_: Exception) {
                        null
                    }
                    val text = state?.let { stateString(it, "line") }?.takeIf { it.isNotBlank() }
                    if (text != null) {
                        val fraction = stateString(state, "fraction")?.toFloatOrNull() ?: -1f
                        val percent = stateString(state, "percent")?.toIntOrNull() ?: -1
                        val log = stateString(state, "log") ?: ""
                        if (text != postedText ||
                            fraction != postedFraction ||
                            log != postedLog
                        ) {
                            notify(text)
                            setProgress(
                                workDataOf(
                                    KEY_PROGRESS_TEXT to text,
                                    KEY_PROGRESS_FRACTION to fraction,
                                    KEY_PROGRESS_PERCENT to percent,
                                    KEY_LOG_LINES to log,
                                )
                            )
                            postedText = text
                            postedFraction = fraction
                            postedLog = log
                        }
                    }
                    delay(250)
                }
            }
            try {
                val statsResult =
                    withDispatcherIO(backend, chunkSize, dryRun, trigger, chatFilter, androidApi)
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
            else -> null
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

    /** One field out of progress_state()'s dict, or null. Kotlin never has to
     * interpret these — src/progress.py has already decided what they say. */
    private fun stateString(state: com.chaquo.python.PyObject?, key: String): String? =
        try {
            state?.callAttr("get", key)?.toString()?.takeIf { it != "None" }
        } catch (_: Exception) {
            null
        }

    private fun notify(text: String) {
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Chat Mail Sync")
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
    // Files a single email could never carry. Deliberately separate from
    // `errors`: nothing failed, the message and its text were archived, and
    // only the media stayed behind -- permanently, on this and every future
    // run. Mirrors SyncStats.media_omitted.
    val mediaOmitted: List<String>,
    val stopped: Boolean,
) {
    fun format(): String {
        val lines = mutableListOf(
            if (stopped) "Stopped early — $messagesSynced message(s) synced" else "Done",
            "Files   : found=$filesFound  synced=$filesSynced  skipped=$filesSkipped  failed=$filesFailed",
            "Messages: parsed=$messagesParsed  synced=$messagesSynced  skipped=$messagesSkipped",
        )
        if (chatsRecovered > 0) lines.add("Recovered $chatsRecovered interrupted run(s)")
        if (mediaOmitted.isNotEmpty()) {
            lines.add("Media too large to email (archived without the file - it stays in your WhatsApp export):")
            mediaOmitted.forEach { lines.add("  - $it") }
        }
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

            fun stringsOf(key: String): List<String> {
                val obj = try { result.callAttr("get", key) } catch (_: Exception) { null }
                return try {
                    obj?.asList()?.map { it.toString() } ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }

            val errors = stringsOf("errors")
            // Older Python side, or a dry run, simply has no such key -- and
            // stringsOf answers with an empty list rather than throwing, so an
            // app paired with a build that predates media_omitted still works.
            val mediaOmitted = stringsOf("media_omitted")

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
                mediaOmitted = mediaOmitted,
            )
        }
    }
}
