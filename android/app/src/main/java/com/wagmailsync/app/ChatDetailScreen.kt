@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wagmailsync.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python

@Composable
fun ChatDetailScreen(chatId: String, onBack: () -> Unit, onDeleted: () -> Unit) {
    var chat by remember { mutableStateOf<ChatSummary?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var resetMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(chatId) {
        chat = loadChatSummaries().find { it.chatId == chatId }
    }

    Scaffold(
        topBar = {
            WagmailTopBar(
                title = chat?.displayName ?: chatId,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val c = chat
            if (c == null) {
                Text("Loading…")
            } else {
                Text("Status: ${c.lastRunStatus ?: "pending"}", style = MaterialTheme.typography.bodyLarge)
                Text("Messages synced: ${c.messagesSynced}")
                Text("Last sync: ${formatSyncTime(c.lastRunAt)}")
                Text("Gmail thread exists: ${if (c.hasThread) "Yes" else "No"}")

                OutlinedButton(
                    onClick = {
                        // Same #all/{thread_id} deep link the Windows GUI uses
                        // (gui.py) — direct thread-ID jump, which is reliable
                        // where label/search URL fragments were not when tried
                        // on mobile Gmail web.
                        val uri = Uri.parse("https://mail.google.com/mail/u/0/#all/${c.gmailThreadId}")
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = c.hasThread,
                ) {
                    Text("Open in Gmail")
                }

                Button(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Reset (re-sync from scratch)")
                }

                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete from list")
                }

                resetMessage?.let { Text(it) }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset this chat?") },
            text = {
                Text(
                    "Local sync state will be cleared. Mail already in Gmail is " +
                        "NOT deleted. Re-importing the same export will create a new " +
                        "Gmail thread rather than continuing the old one."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    val result = Python.getInstance().getModule("src.android_api")
                        .callAttr("reset", chatId)
                    val ok = result.callAttr("get", "ok")?.toString() == "True"
                    val fileRestored = result.callAttr("get", "file_restored")?.toString() == "True"
                    resetMessage = when {
                        !ok -> "Reset failed: ${result.callAttr("get", "error")}"
                        fileRestored -> "Reset complete. The export file was moved back to your inbox — it'll be re-synced next time you sync."
                        else -> "Reset complete. Re-import the export to rebuild this chat."
                    }
                    chat = loadChatSummaries().find { it.chatId == chatId }
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this chat?") },
            text = {
                Text(
                    "This removes the chat from your list entirely — unlike Reset, it " +
                        "won't be kept for re-syncing. Mail already in Gmail is NOT deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    val result = Python.getInstance().getModule("src.android_api")
                        .callAttr("delete_chat", chatId)
                    val ok = result.callAttr("get", "ok")?.toString() == "True"
                    if (ok) onDeleted() else resetMessage = "Delete failed: ${result.callAttr("get", "error")}"
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
