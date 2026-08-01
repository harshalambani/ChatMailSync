@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wamailsync.app

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.chaquo.python.Python
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// state.py's _now() writes "YYYY-MM-DDTHH:MM:SS" (datetime.isoformat, no
// timezone) — parse with LocalDateTime, not Instant.
private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("d MMM, h:mm a")

fun formatSyncTime(raw: String?): String {
    if (raw == null) return "never"
    return try {
        LocalDateTime.parse(raw).format(DISPLAY_FORMAT)
    } catch (_: Exception) {
        raw
    }
}

data class ChatSummary(
    val chatId: String,
    val displayName: String,
    val hasThread: Boolean,
    val gmailThreadId: String?,
    val lastRunStatus: String?,
    val messagesSynced: Long,
    val lastRunAt: String?,
    val sourceFilename: String?,
)

fun loadChatSummaries(): List<ChatSummary> {
    val result = Python.getInstance().getModule("src.android_api").callAttr("status")
    return result.asList().map { row ->
        ChatSummary(
            chatId = row.callAttr("get", "chat_id").toString(),
            displayName = row.callAttr("get", "display_name").toString(),
            hasThread = row.callAttr("get", "has_thread")?.toString() == "1" ||
                row.callAttr("get", "has_thread")?.toString()?.equals("true", ignoreCase = true) == true,
            gmailThreadId = row.callAttr("get", "gmail_thread_id")?.toString()?.takeIf { it != "None" },
            lastRunStatus = row.callAttr("get", "last_run_status")?.toString()?.takeIf { it != "None" },
            messagesSynced = row.callAttr("get", "messages_synced")?.toString()?.toLongOrNull() ?: 0L,
            lastRunAt = row.callAttr("get", "last_run_at")?.toString()?.takeIf { it != "None" },
            sourceFilename = row.callAttr("get", "source_filename")?.toString()?.takeIf { it != "None" },
        )
    }
}

/** RFC 4180-style: wrap in quotes, double any internal quotes — a display
 * name containing `"` would otherwise silently corrupt the CSV. */
private fun csvField(value: String): String = "\"${value.replace("\"", "\"\"")}\""

/** Mirrors the Windows GUI's footer stats label (gui.py _footer_stats_label)
 * — "N chats · M messages synced · last sync ...". Android's Chats tab had
 * no equivalent aggregate summary. */
fun formatFooterStats(chats: List<ChatSummary>): String {
    val totalChats = chats.size
    val totalMsgs = chats.sumOf { it.messagesSynced }
    val lastSyncSuffix = chats.mapNotNull { it.lastRunAt }.maxOrNull()?.let { raw ->
        try {
            val dt = LocalDateTime.parse(raw)
            "  ·  last sync ${dt.format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))}"
        } catch (_: Exception) {
            ""
        }
    } ?: ""
    return "$totalChats chat${if (totalChats != 1) "s" else ""}  ·  " +
        "$totalMsgs message${if (totalMsgs != 1L) "s" else ""} synced$lastSyncSuffix"
}

@Composable
fun ChatsListScreen(onOpenChat: (String) -> Unit) {
    var chats by remember { mutableStateOf(listOf<ChatSummary>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val context = LocalContext.current

    // Most-recently-synced first (get_sync_summary's own SQL orders by
    // display_name, shared with the Windows CLI status command — resorting
    // here client-side, rather than in the shared query, keeps that command
    // unaffected).
    fun refresh() {
        chats = loadChatSummaries().sortedByDescending { it.lastRunAt ?: "" }
    }

    LaunchedEffect(Unit) { refresh() }

    val filteredChats = if (query.isBlank()) chats else
        chats.filter { it.displayName.contains(query, ignoreCase = true) }

    fun exportCsv() {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "wa_chat_sync_chats.csv")
        file.writeText(buildString {
            appendLine("chat_id,display_name,status,messages_synced,last_run_at,source_file")
            chats.forEach { c ->
                appendLine(
                    listOf(
                        csvField(c.chatId),
                        csvField(c.displayName),
                        csvField(c.lastRunStatus ?: ""),
                        c.messagesSynced.toString(),
                        csvField(c.lastRunAt ?: ""),
                        csvField(c.sourceFilename ?: ""),
                    ).joinToString(",")
                )
            }
        })
        val uri = FileProvider.getUriForFile(context, "com.wamailsync.app.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export chats as CSV"))
    }

    Scaffold(
        topBar = {
            WaMailTopBar(
                title = "Chats",
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.List, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Export list as CSV") },
                            onClick = { menuOpen = false; exportCsv() },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (chats.isNotEmpty()) {
                Text(
                    formatFooterStats(chats),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Filter chats…") },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear filter")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (chats.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Nothing synced yet.", style = MaterialTheme.typography.bodyLarge)
                    Text("Go to Home to import and sync a WhatsApp export.", style = MaterialTheme.typography.bodySmall)
                }
            } else if (filteredChats.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("No chats match \"$query\".", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredChats) { chat ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenChat(chat.chatId) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Text(chat.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${chat.lastRunStatus ?: "pending"} · ${chat.messagesSynced} messages · " +
                                formatSyncTime(chat.lastRunAt),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider()
                }
            }
            }
        }
    }
}
