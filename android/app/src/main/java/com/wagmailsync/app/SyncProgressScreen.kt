@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wagmailsync.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
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
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun SyncProgressScreen(
    workManager: WorkManager,
    workId: UUID?,
    onDone: () -> Unit,
) {
    val workInfo = workId?.let {
        workManager.getWorkInfoByIdFlow(it).collectAsState(initial = null).value
    }
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

    Scaffold(
        topBar = { WagmailTopBar(title = "Sync progress") },
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
                                "${(fraction * 100).roundToInt()}%",
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
                }
            }
        }
    }
}
