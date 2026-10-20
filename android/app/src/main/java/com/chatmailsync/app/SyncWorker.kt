package com.chatmailsync.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chaquo.python.Python
import kotlinx.coroutines.CancellationException
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
         * collapsed sync bar and the full progress screen can never disagree
         * by a percentage point. */
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_LOG_LINES = "log_lines"
        const val NOTIFICATION_CHANNEL_ID = "sync_channel"
        const val NOTIFICATION_ID = 1001
        /** BUG-13: the final result. Never the foreground id, never WatchFolderWorker's 1002. */
        const val RESULT_NOTIFICATION_ID = 1003
        /** BUG-13: a new channel; NOTIFICATION_CHANNEL_ID above is frozen and never renamed. */
        const val RESULT_CHANNEL_ID = "sync_results_channel"
        const val RESULT_CHANNEL_NAME = "Sync results"
        /** Unique work name for a manual Home-triggered sync (real or dry
         * run) — lets MainActivity/SyncProgressScreen re-find this run's
         * WorkInfo by name after a process restart, instead of relying on
         * an in-memory UUID that's lost the moment the process dies. */
        const val UNIQUE_WORK_NAME_MANUAL_SYNC = "manual_sync"
    }

    override suspend fun doWork(): Result {
        // BUG-02: if the user tapped Stop on this exact work and the process
        // was then killed, WorkManager re-runs it with the same id. Honour the
        // Stop: do not start the engine. Result.success is terminal (never
        // re-run) and matches how a normal Stop already finishes; it writes
        // no sync_runs row because the engine is never started.
        val stopMark = AppPrefs.stopMark(applicationContext)
        val myId = id.toString()
        if (stopMark.consumeIfStopped(myId)) {
            return Result.success(workDataOf(KEY_RESULT to "Stopped before it started"))
        }
        try {
            return runSync()
        } finally {
            stopMark.clearOnFinish(myId)
        }
    }

    private suspend fun runSync(): Result {
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

        // BUG-13: a new sync clears the previous run's result, so results never stack.
        clearResultNotification()
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
            // BUG-13: false once the final result is being posted, so a poll tick still in
            // flight can never put a stale "Syncing..." back on the progress id.
            val progressOpen = java.util.concurrent.atomic.AtomicBoolean(true)
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
                // milestone log are all derived in src/progress.py. Kotlin's
                // job here is to move those three strings onto the
                // notification and into WorkManager's Data — not to decide
                // what they say.
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
                        if (progressOpen.get() && (text != postedText ||
                            fraction != postedFraction ||
                            log != postedLog)
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
                progressOpen.set(false)
                pollJob.cancel()
                postResult(
                    syncResultNotice(dryRun, statsResult.stopped, statsResult.messagesSynced, statsResult.filesFailed)
                )
                Result.success(workDataOf(KEY_RESULT to statsResult.format()))
            } catch (e: CancellationException) {
                // WorkManager stopped us (system stop, REPLACE enqueue, cancelUniqueWork).
                // Not a failure: quiet notice, then rethrow so coroutine cancellation works
                // and the finally block below still runs.
                progressOpen.set(false)
                pollJob.cancel()
                postResult(syncCancelledNotice(dryRun))
                throw e
            } catch (e: Exception) {
                progressOpen.set(false)
                pollJob.cancel()
                postResult(syncFailedNotice(e.message))
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
            // Read here rather than passed in: every run this app starts has
            // to obey the same floor, and a parameter is one more place for
            // a future call site to forget it.
            //
            // Handed over raw. SyncManager normalises it and raises on a date
            // it cannot compare, and that is the behaviour we want: a corrupt
            // preference should fail the run, not silently drop the floor and
            // mail out messages the user asked never to receive. A failed sync
            // can be retried; a sent email cannot be unsent.
            val cutoff = AppPrefs.getCutoffDate(applicationContext)
            val result = androidApi.callAttr(
                "sync", transport, chunkSize, dryRun, chatFilter, null, trigger, cutoff,
            )
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
            .setContentIntent(openAppIntent())
            .build()

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun clearResultNotification() {
        try {
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(RESULT_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    /**
     * BUG-13: the final result goes out under its OWN id and channel. It used to reuse the
     * foreground id, so when the worker ended WorkManager removed it together with the
     * progress notification. Dismissable, auto-cancelled, taps open the app. Never throws:
     * with POST_NOTIFICATIONS denied (Android 13+) the system silently drops the post.
     */
    private fun postResult(notice: ResultNotice) {
        try {
            val manager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(RESULT_CHANNEL_ID, RESULT_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val builder = NotificationCompat.Builder(applicationContext, RESULT_CHANNEL_ID)
                .setContentTitle("Chat Mail Sync")
                .setContentText(notice.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
            // A plain success stays quiet (no sound, no pop-up) but is still kept in the shade.
            if (!notice.alert) builder.setSilent(true)
            manager.notify(RESULT_NOTIFICATION_ID, builder.build())
        } catch (_: Exception) {
        }
    }

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
 * screen shows a readable breakdown instead of a raw Python dict repr.
 */
private data class SyncStatsResult(
    val filesFound: Int,
    val filesSynced: Int,
    val filesSkipped: Int,
    val filesFailed: Int,
    val messagesParsed: Int,
    val messagesSynced: Int,
    val messagesSkipped: Int,
    // Its own number, never folded into messagesSkipped: "skipped" means the
    // app had already sent it, and being told 4,000 messages were skipped when
    // the real answer is "your cutoff held them back" is the one explanation
    // this feature exists to give. Mirrors SyncStats.messages_cutoff.
    val messagesCutoff: Int,
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
        val lines = mutableListOf<String>()
        // BUG-12: a failure line first, so it is not buried under the counts.
        exportsKeptLine(filesFailed)?.let { lines.add(it) }
        lines.add(
            if (stopped) "Stopped early — $messagesSynced message(s) synced" else "Done"
        )
        lines.addAll(listOf(
            "Files   : found=$filesFound  synced=$filesSynced  skipped=$filesSkipped  failed=$filesFailed",
            "Messages: parsed=$messagesParsed  synced=$messagesSynced  skipped=$messagesSkipped",
        ))
        if (messagesCutoff > 0) {
            lines.add("Held back $messagesCutoff message(s) from before your cutoff date")
        }
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
                messagesCutoff = intOf("messages_cutoff"),
                stopped = try { result.callAttr("get", "stopped")?.toString() == "True" } catch (_: Exception) { false },
                chatsRecovered = intOf("chats_recovered"),
                errors = errors,
                mediaOmitted = mediaOmitted,
            )
        }
    }
}

/**
 * BUG-12: the plain line telling the user how many exports could not be read and were kept in
 * the inbox, or null when none failed. Never carries a chat name.
 */
internal fun exportsKeptLine(filesFailed: Int): String? = when {
    filesFailed <= 0 -> null
    filesFailed == 1 -> "1 export could not be read and was kept - see Sync log"
    else -> "$filesFailed exports could not be read and were kept - see Sync log"
}

/** The finish notification: the existing text, plus the kept-export line when any failed. */
internal fun syncNotificationText(label: String, messagesSynced: Int, filesFailed: Int): String {
    val base = "$label — $messagesSynced message(s) synced"
    val kept = exportsKeptLine(filesFailed) ?: return base
    return "$base; $kept"
}

/** What the final notification says, and whether it should alert (sound/pop-up) or stay quiet. */
internal data class ResultNotice(val text: String, val alert: Boolean)

/**
 * BUG-13: a finished run. A failed or kept export alerts; a plain success (and a user's own
 * Stop) is quiet. Carries no chat name.
 */
internal fun syncResultNotice(dryRun: Boolean, stopped: Boolean, messagesSynced: Int, filesFailed: Int): ResultNotice {
    val label = when {
        stopped -> if (dryRun) "Test run stopped" else "Sync stopped"
        else -> if (dryRun) "Test run complete" else "Sync complete"
    }
    return ResultNotice(syncNotificationText(label, messagesSynced, filesFailed), alert = filesFailed > 0)
}

internal fun syncFailedNotice(message: String?): ResultNotice =
    ResultNotice("Sync failed: $message", alert = true)

/** BUG-13: WorkManager cancelled the worker. Never a failure, never an alert; no stats on this path. */
internal fun syncCancelledNotice(dryRun: Boolean): ResultNotice =
    ResultNotice(if (dryRun) "Test run stopped" else "Sync stopped", alert = false)
