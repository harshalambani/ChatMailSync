@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.chaquo.python.Python
import kotlin.math.roundToInt

@Composable
fun SyncProgressScreen(
    workManager: WorkManager,
    onDone: () -> Unit,
    // Leaving a *running* sync is not the same as finishing one: the work
    // carries on, so this pops the screen without pruning it (see
    // onDoneAndPrune) and hands the run over to the collapsed sync bar, which
    // follows the user onto every other screen and taps back to here.
    onMinimize: () -> Unit = onDone,
) {
    // Unique work name (not an in-memory UUID) — lets this screen re-find
    // an in-flight sync even if the app process was killed and relaunched
    // mid-sync, since a UUID captured only in Compose state wouldn't
    // survive that.
    val workInfo = workManager
        .getWorkInfosForUniqueWorkFlow(SyncWorker.UNIQUE_WORK_NAME_MANUAL_SYNC)
        .collectAsState(initial = emptyList())
        .value
        .firstOrNull()
    // Local only — request_stop() is fire-and-forget on the Python side
    // (src/android_api.py sets a threading.Event the in-flight sync polls
    // between files, same mechanism as the Windows GUI's Stop button). This
    // just tracks that a request was made so the button doesn't look like
    // it did nothing while the current file finishes.
    var stopRequested by remember { mutableStateOf(false) }

    // The sync request's input Data (including the OAuth access token) sits
    // in WorkManager's own SQLite DB until pruned. pruneWork() deletes the
    // finished work's row outright, so calling it while this screen is still
    // observing that same workId's Flow makes the next emission come back
    // null -- which the screen below reads as "not started yet" and gets
    // stuck showing an indeterminate "Starting..." forever. So prune only
    // once the user dismisses this screen (onDoneAndPrune), not the instant
    // the state flips to finished.
    fun onDoneAndPrune() {
        workManager.pruneWork()
        onDone()
    }

    val running = workInfo?.state == WorkInfo.State.RUNNING ||
        workInfo?.state == WorkInfo.State.ENQUEUED

    Scaffold(
        // Zero, deliberately: MainActivity's Scaffold has already padded
        // this NavHost for the status bar and the bottom bars, and insets
        // are not consumed by being turned into padding -- so a screen
        // Scaffold left on the default reserves the same strips a second
        // time. That silently cost about a row and a half of list height
        // on every screen, which is how two exports ended up below the
        // fold on the import picker.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // Only while it is running: once the run is over the only way out
            // is [Done], which also prunes. A back arrow sitting next to that
            // button would be two exits with different consequences.
            if (running) {
                ChatMailTopBar(
                    title = "Sync progress",
                    backLabel = "Home",
                    onBack = onMinimize,
                )
            } else {
                ChatMailTopBar(title = "Sync progress")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (workInfo?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    Text("Sync complete", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            workInfo.outputData.getString(SyncWorker.KEY_RESULT) ?: "",
                            modifier = Modifier.padding(14.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = ::onDoneAndPrune) { Text("Done") }
                }
                WorkInfo.State.FAILED -> {
                    Text("Sync failed", style = MaterialTheme.typography.titleMedium)
                    Text(
                        workInfo.outputData.getString(SyncWorker.KEY_ERROR) ?: "Unknown error",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = ::onDoneAndPrune) { Text("Done") }
                }
                WorkInfo.State.CANCELLED -> {
                    Text("Sync cancelled")
                    Button(onClick = ::onDoneAndPrune) { Text("Done") }
                }
                null -> {
                    Text("Starting…")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    val fraction = workInfo.progress.getFloat(SyncWorker.KEY_PROGRESS_FRACTION, -1f)
                    // Rounded on the Python side and carried here, so this
                    // screen and the collapsed bar can never differ by a
                    // point on the same run.
                    val percent = workInfo.progress.getInt(SyncWorker.KEY_PROGRESS_PERCENT, -1)
                    val text = workInfo.progress.getString(SyncWorker.KEY_PROGRESS_TEXT) ?: "Syncing…"
                    if (fraction in 0f..1f) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(text, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (percent >= 0) "$percent%" else "${(fraction * 100).roundToInt()}%",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (stopRequested) {
                        Text(
                            "Stopping — finishing the current file…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        OutlinedButton(
                            onClick = {
                                stopRequested = true
                                Python.getInstance().getModule("src.android_api").callAttr("request_stop")
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("Cancel sync") }
                    }

                    // Says out loud what the back arrow above now does --
                    // otherwise leaving this screen looks like it might be
                    // the same thing as cancelling. "The bar at the bottom"
                    // was ambiguous on a phone that already has two bars of
                    // its own down there, so the sentence now says where the
                    // bar comes from and what it is.
                    Text(
                        "You can leave this screen — the sync keeps running, and a progress bar appears at the bottom of every other screen to bring you back.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Rolling milestone log (file started/finished, inbox
                    // scan result) — Windows' GUI has always shown a log
                    // box; Android previously showed only the single
                    // current-line status with nothing to scroll back
                    // through.
                    val logLines = workInfo.progress.getString(SyncWorker.KEY_LOG_LINES)
                    if (!logLines.isNullOrBlank()) {
                        val logScroll = rememberScrollState()
                        // The card's colour is named rather than defaulted so the
                        // fade below can be painted in exactly the same ground.
                        val logGround = MaterialTheme.colorScheme.surfaceContainerLow
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = logGround),
                        ) {
                            Text(
                                logLines,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .fadingEdges(logScroll, logGround, top = false)
                                    .verticalScrollbar(logScroll)
                                    .verticalScroll(logScroll)
                                    .padding(14.dp),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
