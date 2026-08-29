@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    // Whether the *mailbox* is Gmail. IMAP is the only backend, and many
    // users here point it at imap.gmail.com with an app password. It matters
    // for the reset instructions, because Gmail has no real folders - an IMAP
    // folder is a label, and deleting a label does not delete the mail, it
    // just unlabels it and leaves every message in All Mail. "Delete that
    // folder" is therefore the one instruction that will make a Gmail user
    // answer "yes, I deleted it" honestly and still get duplicates.
    val isGmailMailbox = remember {
        AppPrefs.getImapHost(context).lowercase().let {
            it.contains("gmail") || it.contains("googlemail")
        }
    }

    LaunchedEffect(chatId) {
        chat = loadChatSummaries().find { it.chatId == chatId }
    }

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
            ChatMailTopBar(
                title = chat?.displayName ?: chatId,
                backLabel = "Chats",
                onBack = onBack,
                // The title here is a chat name, which can be long and is the
                // only thing identifying the screen -- it gets the whole band.
                showConnection = false,
            )
        },
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            // Scrollable now that the facts have headings above them: on a
            // short screen the four action buttons were the first thing to go
            // off the bottom, and they are the reason the screen exists.
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .fadingEdges(scrollState, MaterialTheme.colorScheme.background)
                .verticalScrollbar(scrollState)
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val c = chat
            if (c == null) {
                Text("Loading…")
            } else {
                // Was four bare sentences and four buttons in one flat column,
                // with "Status: complete" leading -- a database value, read
                // out. Now the state is said once at the top in the same three
                // words the list's dot uses, the facts sit under a ruled "This
                // chat" heading as label/value pairs (DetailSection and
                // DetailField, shared with the run detail screen), and the
                // four buttons are visibly a separate group rather than the
                // continuation of a list of facts.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusDot(c.lastRunStatus)
                    Text(
                        chatStatusOf(c.lastRunStatus).description,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                DetailSection("This chat")
                DetailField("Messages synced", c.messagesSynced.toString())
                DetailField("Last sync", if (c.lastRunAt != null) formatSyncTime(c.lastRunAt) else "Never")
                // Not "has a Gmail thread": under IMAP the stored id is the
                // RFC 822 Message-ID we generated, which is what threads the
                // archive there.
                DetailField("Mail thread exists", if (c.hasThread) "Yes" else "No")
                c.sourceFilename?.let { DetailField("Export file", it) }

                DetailSection("Actions")

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
                    // Not "re-sync from scratch": this button syncs nothing. It
                    // clears the record so that a *later* sync starts over.
                    Text("Reset (forget sync history)")
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
        DestructiveAlertDialog(
            title = if (hasMail) "Delete the old mail first" else "Reset this chat?",
            confirmText = if (hasMail) "Yes, I deleted it" else "Reset",
            onConfirm = { if (hasMail) resetStage = 2 else performReset() },
            onDismiss = { resetStage = 0 },
        ) {
            if (hasMail) {
                // The old text said mail in your mailbox is "NOT deleted" and
                // stopped there — true, and exactly backwards as reassurance:
                // it is precisely because the old copies survive that a later
                // sync files a second copy of every message.
                DialogBody(
                    "${plural(archivedCount, "message")} " +
                        "${if (archivedCount == 1) "is" else "are"} already archived in"
                )
                FolderChip(mailboxFolder)
                DialogBody(
                    "Resetting makes the app forget it sent " +
                        "${if (archivedCount == 1) "it" else "them"}, so the next sync files " +
                        "a second copy. This app can never delete mail - only you can."
                )
                DialogRule()
                if (isGmailMailbox) {
                    // Gmail has no folders to delete. Moving a message to
                    // [Gmail]/Trash is the only action that strips every label
                    // and takes it out of All Mail; removing the label leaves
                    // the message in place, fully intact and still a duplicate
                    // target for the next sync.
                    NumberedStep(1, "In Gmail, open that label and select every conversation.")
                    NumberedStep(2, "Delete them. Deleting the label itself is not enough - the mail stays in All Mail.")
                    NumberedStep(3, "Empty the Bin.")
                } else {
                    NumberedStep(1, "In your mail app, delete that folder.")
                    NumberedStep(2, "Empty the trash, if your provider keeps one.")
                }
                DialogRule()
                DialogBody("Have you already deleted that mail?", emphasis = true)
            } else {
                // android_api.reset() restores the export from processed/
                // back to inbox/ when it finds it (see file_restored), so
                // the common case needs no manual re-import.
                DialogBody(
                    "This clears what the app remembers about this chat. Nothing has " +
                        "been archived for it yet, so no duplicate mail can result."
                )
                DialogBody(
                    "A new mail thread is created the next time this chat is synced."
                )
            }
        }
    }

    // Gate 2. Deliberately a second tap on a second screen: the cost of being
    // wrong here is duplicate mail only the user can clean up by hand.
    //
    // Wording note: this used to confirm "Reset and re-archive" / "Reset and
    // archive this chat again from scratch?", which read as though tapping it
    // would start sending mail there and then. It does not - performReset()
    // only clears local state and moves the export back to the inbox. Saying
    // otherwise was misleading in the more dangerous direction too: someone
    // who thinks the duplicates arrive immediately will look at their mailbox,
    // see nothing, and assume it was fine, when the duplication actually
    // happens at the next sync.
    if (resetStage == 2) {
        DestructiveAlertDialog(
            title = "Confirm reset",
            confirmText = "Reset",
            onConfirm = { performReset() },
            onDismiss = { resetStage = 0 },
        ) {
            DialogBody("You've said this folder is now empty:")
            FolderChip(mailboxFolder)
            DialogBody(
                "Resetting clears the app's record of this chat. No mail is sent now - " +
                    "the next sync re-archives all ${plural(archivedCount, "message")} " +
                    "into a fresh thread."
            )
            DialogBody(
                "If any of the old mail is still there, that sync gives you a second " +
                    "copy of it, and only you can clean it up.",
                emphasis = true,
            )
        }
    }

    if (showDeleteConfirm) {
        DestructiveAlertDialog(
            title = "Delete this chat?",
            confirmText = "Delete",
            onConfirm = {
                showDeleteConfirm = false
                val result = Python.getInstance().getModule("src.android_api")
                    .callAttr("delete_chat", chatId)
                val ok = result.callAttr("get", "ok")?.toString() == "True"
                if (ok) onDeleted() else resetMessage = "Delete failed: ${result.callAttr("get", "error")}"
            },
            onDismiss = { showDeleteConfirm = false },
        ) {
            DialogBody(
                "This removes the chat from your list entirely — unlike Reset, it " +
                    "won't be kept for re-syncing. Mail already in your mailbox is " +
                    "not deleted."
            )
            DialogBody(
                "It also forgets which messages were already archived, so if you ever " +
                    "import this export again you will get a second copy of all of them " +
                    "unless you delete the old mail first."
            )
        }
    }
}
