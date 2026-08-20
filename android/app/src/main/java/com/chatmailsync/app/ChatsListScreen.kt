@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import android.content.Intent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/** The three states the dot distinguishes, and the words that go with them.
 *
 * Three, not four: the Windows list has a separate amber for "pending", but
 * "pending" here only ever means a run that started and did not record a
 * finish, which from the outside is indistinguishable from never having
 * synced -- the chat is not in the mailbox either way, and the fix is the
 * same. Four colours would be four things to learn for three outcomes. */
internal enum class ChatStatus(
    val description: String,
    /** On the filter chip, where the column is narrow and the word sits next
     * to a count. Same four labels as the Windows chip row. */
    val chipLabel: String,
    /** Answers "why is this list empty?" in that chip's own terms -- "No
     * chats archived yet" under a [Failed (0)] chip, on an inbox holding
     * forty chats, is a flat lie about the state of the app. */
    val emptyTitle: String,
) {
    SYNCED("Synced", "Synced", "No chats have synced yet."),
    FAILED("Last sync failed", "Failed", "Nothing has failed."),
    NOT_SYNCED("Not synced yet", "Never synced", "Every chat has been synced at least once."),
}

internal fun chatStatusOf(lastRunStatus: String?): ChatStatus = when (lastRunStatus) {
    "complete" -> ChatStatus.SYNCED
    "failed" -> ChatStatus.FAILED
    else -> ChatStatus.NOT_SYNCED
}

/** The row's at-a-glance state, matching the dot the Windows list has had
 * since _add_chat_row -- this gap ran Android-ward. Colour alone would fail
 * anyone who cannot separate the green from the red, so the same three words
 * are also on the status line and in the content description. */
@Composable
internal fun StatusDot(lastRunStatus: String?) {
    val status = chatStatusOf(lastRunStatus)
    // Theme roles, never raw hex: ChatMailTheme assigns every M3 role
    // deliberately, and a one-off green here would drift the moment the
    // palette moves. tertiary *is* the archival green, added for exactly
    // this kind of "OK" indicator.
    val color = when (status) {
        ChatStatus.SYNCED -> MaterialTheme.colorScheme.tertiary
        ChatStatus.FAILED -> MaterialTheme.colorScheme.error
        ChatStatus.NOT_SYNCED -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = status.description }
    )
}

@Composable
fun ChatsListScreen(onOpenChat: (String) -> Unit, onImportChat: () -> Unit) {
    var chats by remember { mutableStateOf(listOf<ChatSummary>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // null == the All chip. The text box filters by name, which only helps
    // someone who already knows which chat they want; "which of these failed?"
    // was not answerable at all without reading every dot in the list.
    var statusFilter by remember { mutableStateOf<ChatStatus?>(null) }
    val context = LocalContext.current

    // Most-recently-synced first (get_sync_summary's own SQL orders by
    // display_name, shared with the Windows CLI status command — resorting
    // here client-side, rather than in the shared query, keeps that command
    // unaffected).
    fun refresh() {
        chats = loadChatSummaries().sortedByDescending { it.lastRunAt ?: "" }
    }

    LaunchedEffect(Unit) { refresh() }

    val filteredChats = chats
        .filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) }
        .filter { statusFilter == null || chatStatusOf(it.lastRunStatus) == statusFilter }

    // Counted over the whole list, never the filtered view: a chip reading
    // "Failed (0)" only because Failed is selected would be answering its own
    // question.
    val statusCounts = ChatStatus.entries.associateWith { s ->
        chats.count { chatStatusOf(it.lastRunStatus) == s }
    }

    fun exportCsv() {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "chat_mail_sync_chats.csv")
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
        val uri = FileProvider.getUriForFile(context, "com.chatmailsync.app.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export chats as CSV"))
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
        val chipScroll = rememberScrollState()
        val chatListState = rememberLazyListState()
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
                // Counts on the chips answer the question without a tap in
                // most cases; the chips themselves are for the rest. One
                // scrolling row here -- the Windows panel is a fixed 236dp and
                // lays the same four out 2x2, which is a geometry difference,
                // not a wording one.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(chipScroll)
                        .fadingEdgesHorizontal(chipScroll)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = statusFilter == null,
                        onClick = { statusFilter = null },
                        label = { Text("All (${chats.size})") },
                    )
                    ChatStatus.entries.forEach { s ->
                        FilterChip(
                            selected = statusFilter == s,
                            onClick = { statusFilter = if (statusFilter == s) null else s },
                            label = { Text("${s.chipLabel} (${statusCounts[s] ?: 0})") },
                        )
                    }
                }
            }
            if (chats.isEmpty()) {
                // An empty screen is the first thing a new user sees, so it
                // carries the instructions rather than describing the absence:
                // the WhatsApp menu path is the one step nobody guesses, and
                // "Go to Home" was a signpost to a screen that then had to
                // explain itself all over again.
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "No chats archived yet.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "In WhatsApp, open a chat and tap ⋮ → More → Export chat, " +
                            "then import the .zip or .txt here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(
                        onClick = onImportChat,
                        modifier = Modifier.padding(top = 20.dp),
                    ) { Text("Import a chat export") }
                }
            } else if (filteredChats.isEmpty()) {
                val chip = statusFilter
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (chip != null) {
                        // The chip is the reason the list is empty, so the
                        // chip's own words go here -- and the way out is to
                        // clear it, not to be told about name matching.
                        Text(chip.emptyTitle, style = MaterialTheme.typography.titleMedium)
                        if (query.isNotBlank()) {
                            Text(
                                "The name filter \"$query\" is also on.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        TextButton(
                            onClick = { statusFilter = null; query = "" },
                            modifier = Modifier.padding(top = 12.dp),
                        ) { Text("Show all chats") }
                    } else {
                        Text(
                            "No chats match \"$query\".",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "The filter matches chat names only, not message text.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        TextButton(
                            onClick = { query = "" },
                            modifier = Modifier.padding(top = 12.dp),
                        ) { Text("Clear filter") }
                    }
                }
            } else {
            LazyColumn(
                state = chatListState,
                modifier = Modifier
                    .fillMaxSize()
                    .fadingEdges(chatListState, MaterialTheme.colorScheme.background)
                    .verticalScrollbar(chatListState),
            ) {
                items(filteredChats) { chat ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenChat(chat.chatId) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(chat.lastRunStatus)
                        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(chat.displayName, style = MaterialTheme.typography.titleSmall)
                            // Was "complete · 142 messages · 3 Aug, 2:14 PM":
                            // three facts of different kinds strung into one
                            // sentence. Now when on the left, how much on the
                            // right, and the status stays in the dot rather
                            // than being said twice -- except "failed", which
                            // is spelled out in the error colour, because a
                            // dot is the wrong amount of emphasis for the one
                            // row you must not scroll past.
                            val status = chatStatusOf(chat.lastRunStatus)
                            val when_ = if (chat.lastRunAt != null)
                                formatSyncTime(chat.lastRunAt) else ""
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = when (status) {
                                        ChatStatus.FAILED ->
                                            if (when_.isNotEmpty()) "Failed · $when_" else "Failed"
                                        else -> if (when_.isNotEmpty()) when_ else "Not synced yet"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (status == ChatStatus.FAILED)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                if (chat.messagesSynced > 0) {
                                    Text(
                                        "${chat.messagesSynced} messages",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            }
        }
    }
}
