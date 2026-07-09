@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wagmailsync.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python

private val TRIGGER_LABELS = mapOf(
    "manual" to "Manual",
    "watched_folder" to "Watched folder",
)

data class SyncRunLogEntry(
    val runId: Long,
    val displayName: String,
    val status: String,
    val trigger: String,
    val messagesParsed: Long,
    val messagesSynced: Long,
    val messagesSkipped: Long,
    val errorMessage: String?,
    val startedAt: String?,
)

fun loadSyncLog(days: Int = 90): List<SyncRunLogEntry> {
    val result = Python.getInstance().getModule("src.android_api").callAttr("sync_log", days)
    fun getStr(row: com.chaquo.python.PyObject, key: String): String? =
        row.callAttr("get", key)?.toString()?.takeIf { it != "None" }
    return result.asList().map { row ->
        SyncRunLogEntry(
            runId = getStr(row, "run_id")?.toLongOrNull() ?: 0L,
            displayName = getStr(row, "display_name") ?: "Unknown chat",
            status = getStr(row, "status") ?: "pending",
            trigger = getStr(row, "trigger") ?: "manual",
            messagesParsed = getStr(row, "messages_parsed")?.toLongOrNull() ?: 0L,
            messagesSynced = getStr(row, "messages_synced")?.toLongOrNull() ?: 0L,
            messagesSkipped = getStr(row, "messages_skipped")?.toLongOrNull() ?: 0L,
            errorMessage = getStr(row, "error_message"),
            startedAt = getStr(row, "started_at"),
        )
    }
}

@Composable
fun SyncLogScreen(onBack: () -> Unit) {
    var runs by remember { mutableStateOf(listOf<SyncRunLogEntry>()) }

    LaunchedEffect(Unit) { runs = loadSyncLog() }

    Scaffold(
        topBar = {
            WagmailTopBar(
                title = "Sync log",
                subtitle = "Last 90 days",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (runs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No syncs in the last 90 days.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(runs) { run ->
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Text(run.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${run.status} · ${TRIGGER_LABELS[run.trigger] ?: run.trigger} · " +
                                "${run.messagesSynced} synced, ${run.messagesSkipped} skipped · " +
                                formatSyncTime(run.startedAt),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (run.status == "failed" && run.errorMessage != null) {
                            Text(
                                run.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
