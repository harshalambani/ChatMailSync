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
    // Reset is a two-gate flow when mail already exists for this chat, so it
    // needs more than a boolean: stage 1 tells the user what to go and delete,
    // stage 2 makes them confirm they did it. See resetStage below.
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var resetMessage by remember { mutableStateOf<String?>(null) }
    var resetStage by remember { mutableStateOf(0) }
    var archivedCount by remember { mutableStateOf(0) }
    var mailboxFolder by remember { mutableStateOf("") }
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
                    onClick = {
                        // Ask Python how much mail is already out there before
                        // showing anything, so the dialog can name a real count
                        // and the real folder instead of a vague warning.
                        val preview = Python.getInstance().getModule("src.android_api")
                            .callAttr("reset_preview", chatId)
                        archivedCount = preview.callAttr("get", "archived_count")
                            ?.toString()?.toIntOrNull() ?: 0
                        mailboxFolder = preview.callAttr("get", "mailbox_folder")
                            ?.toString() ?: ""
                        resetStage = 1
                    },
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

    // Shared by both exits from the gate: the no-mail-yet case (stage 1) and
    // the confirmed case (stage 2). Passes confirmed_mailbox_cleared, which
    // src.state.reset_chat refuses to proceed without.
    val performReset = {
        resetStage = 0
        val result = Python.getInstance().getModule("src.android_api")
            .callAttr("reset", chatId, true)
        val ok = result.callAttr("get", "ok")?.toString() == "True"
        val fileRestored = result.callAttr("get", "file_restored")?.toString() == "True"
        resetMessage = when {
            !ok -> "Reset failed: ${result.callAttr("get", "error")}"
            fileRestored -> "Reset complete. The export file was moved back to your inbox — it'll be re-synced next time you sync."
            else -> "Reset complete. Re-import the export to rebuild this chat."
        }
        chat = loadChatSummaries().find { it.chatId == chatId }
    }

    // Gate 1. With nothing archived there is nothing to duplicate, so this is
    // the whole flow. With mail already out there it becomes the instruction
    // step, and the confirm button advances to gate 2 rather than resetting.
    if (resetStage == 1) {
        val hasMail = archivedCount > 0
        AlertDialog(
            onDismissRequest = { resetStage = 0 },
            title = { Text(if (hasMail) "Delete the old mail first" else "Reset this chat?") },
            text = {
                // The old text said mail in your mailbox is "NOT deleted" and
                // stopped there — true, and exactly backwards as reassurance:
                // it is precisely because the old copies survive that a re-sync
                // files a second copy of every message.
                Text(
                    if (hasMail) {
                        "$archivedCount message(s) from this chat are already archived in:" +
                            "\n\n    $mailboxFolder\n\n" +
                            "Re-syncing does NOT replace them. It adds a second copy of all " +
                            "$archivedCount, in a new thread. This app can never delete mail, " +
                            "so nothing here can undo that afterwards.\n\n" +
                            "Before continuing:\n" +
                            "1. Open your mail app.\n" +
                            "2. Delete the folder '$mailboxFolder' (or all mail inside it).\n" +
                            "3. Empty the trash, if your provider keeps one.\n\n" +
                            "Have you already deleted that mail?"
                    } else {
                        // android_api.reset() restores the export from processed/
                        // back to inbox/ when it finds it (see file_restored), so
                        // the common case needs no manual re-import.
                        "Local sync state will be cleared, and a new mail thread will be " +
                            "created the next time this chat is synced. Nothing has been " +
                            "archived for this chat yet, so no duplicate mail can result."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (hasMail) resetStage = 2 else performReset()
                }) { Text(if (hasMail) "Yes, I deleted it" else "Reset") }
            },
            dismissButton = {
                TextButton(onClick = { resetStage = 0 }) { Text("Cancel") }
            },
        )
    }

    // Gate 2. Deliberately a second tap on a second screen: the cost of being
    // wrong here is duplicate mail only the user can clean up by hand.
    if (resetStage == 2) {
        AlertDialog(
            onDismissRequest = { resetStage = 0 },
            title = { Text("Confirm re-archive") },
            text = {
                Text(
                    "Last check. If '$mailboxFolder' still has mail in it, you will end " +
                        "up with $archivedCount duplicate message(s) that only you can " +
                        "clean up.\n\nReset and archive this chat again from scratch?"
                )
            },
            confirmButton = {
                TextButton(onClick = { performReset() }) { Text("Reset and re-archive") }
            },
            dismissButton = {
                TextButton(onClick = { resetStage = 0 }) { Text("Cancel") }
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
                        "NOT deleted.\n\n" +
                        "It also forgets which messages were already archived, so if you " +
                        "ever import this export again you will get a second copy of all " +
                        "of them unless you delete the old mail first."
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
