@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wamailsync.app

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
fun ChatDetailScreen(
    chatId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onSyncThisChat: () -> Unit,
    syncInProgress: Boolean,
) {
    var chat by remember { mutableStateOf<ChatSummary?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var resetMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val isGmailBackend = remember {
        AppPrefs.resolveMailBackend(context) == AppPrefs.MAIL_BACKEND_GMAIL_OAUTH
    }

    LaunchedEffect(chatId) {
        chat = loadChatSummaries().find { it.chatId == chatId }
    }

    Scaffold(
        topBar = {
            WaMailTopBar(
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
                Text("Mail thread exists: ${if (c.hasThread) "Yes" else "No"}")

                // Gated on the *backend*, not just on a thread existing: under
                // IMAP the stored thread id is the RFC 822 Message-ID we
                // generated (that's what IMAP threads on via References/
                // In-Reply-To), so hasThread is always true there and this
                // button used to always render — pointing at Gmail for someone
                // who archives to Outlook or Fastmail, and at a thread id
                // Gmail has never heard of. Hidden rather than disabled: there
                // is no cross-provider equivalent of this deep link, so on IMAP
                // there is nothing the user could do to enable it.
                if (isGmailBackend) {
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
                }

                // android_api.sync()'s chat_filter param already existed but
                // nothing on Android called it with one — every sync always
                // covered the whole inbox. This is the CLI's `--chat` filter,
                // surfaced here for "just re-sync this one chat" without
                // waiting on everything else queued up.
                OutlinedButton(
                    onClick = onSyncThisChat,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !syncInProgress,
                ) {
                    Text(if (syncInProgress) "Current sync is on" else "Sync just this chat")
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
                // Previously said "re-importing... will create a new Gmail
                // thread," implying the user always has to manually re-pick
                // the file — but android_api.reset() already restores it
                // from processed/ back to inbox/ automatically when found
                // (see file_restored below), so the common case just needs
                // "Sync now" with no re-import step at all.
                Text(
                    "Local sync state will be cleared, and a new mail thread will be " +
                        "created the next time this chat is synced. Mail already in your " +
                        "mailbox is NOT deleted."
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
                        "won't be kept for re-syncing. Mail already in your mailbox is " +
                        "NOT deleted."
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
