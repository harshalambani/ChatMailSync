@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val TRIGGER_LABELS = mapOf(
    "manual" to "Manual",
    "watched_folder" to "Watched folder",
)

// Says what happened rather than naming the column's value: "complete" is a
// state name, and someone opening a run's detail is asking a question.
private val STATUS_HEADLINES = mapOf(
    "complete" to "Finished",
    "failed" to "Failed",
    "pending" to "Still running",
)

private val LONG_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy 'at' h:mm a")

data class SyncRunLogEntry(
    val runId: Long,
    val chatId: String,
    val displayName: String,
    val status: String,
    val trigger: String,
    val messagesParsed: Long,
    val messagesSynced: Long,
    val messagesSkipped: Long,
    val errorMessage: String?,
    val startedAt: String?,
    val completedAt: String?,
    val lastSyncedTs: String?,
    /** Finished cleanly and uploaded nothing. Computed in the shared core
     * (state.is_uneventful_run, stamped on by android_api.sync_log) rather
     * than restated here, so both front-ends fold away the same runs. */
    val uneventful: Boolean,
)

fun loadSyncLog(days: Int = 90): List<SyncRunLogEntry> {
    val result = Python.getInstance().getModule("src.android_api").callAttr("sync_log", days)
    fun getStr(row: com.chaquo.python.PyObject, key: String): String? =
        row.callAttr("get", key)?.toString()?.takeIf { it != "None" }
    return result.asList().map { row ->
        SyncRunLogEntry(
            runId = getStr(row, "run_id")?.toLongOrNull() ?: 0L,
            chatId = getStr(row, "chat_id") ?: "",
            displayName = getStr(row, "display_name") ?: "Unknown chat",
            status = getStr(row, "status") ?: "pending",
            trigger = getStr(row, "trigger") ?: "manual",
            messagesParsed = getStr(row, "messages_parsed")?.toLongOrNull() ?: 0L,
            messagesSynced = getStr(row, "messages_synced")?.toLongOrNull() ?: 0L,
            messagesSkipped = getStr(row, "messages_skipped")?.toLongOrNull() ?: 0L,
            errorMessage = getStr(row, "error_message"),
            startedAt = getStr(row, "started_at"),
            completedAt = getStr(row, "completed_at"),
            lastSyncedTs = getStr(row, "last_synced_ts"),
            uneventful = getStr(row, "uneventful")?.equals("True", ignoreCase = true) == true,
        )
    }
}

/** The one-line summary of what a run moved. A run that uploaded nothing says
 * so in words -- "0 synced, 0 skipped" is the same information and reads as a
 * malfunction. Mirrors gui.py's _run_counts_text. */
private fun runCountsText(run: SyncRunLogEntry): String = when {
    run.status == "failed" ->
        if (run.messagesSynced > 0) "${run.messagesSynced} synced before it failed"
        else "Nothing uploaded"
    run.messagesSynced == 0L && run.messagesSkipped == 0L -> "Nothing new"
    run.messagesSkipped > 0L -> "${run.messagesSynced} synced, ${run.messagesSkipped} already there"
    else -> "${run.messagesSynced} synced"
}

private fun formatRunTimeLong(raw: String?): String {
    if (raw == null) return "—"
    return try {
        LocalDateTime.parse(raw).format(LONG_FORMAT)
    } catch (_: Exception) {
        raw
    }
}

/** How long a run took, or null when that cannot be worked out. */
private fun runDuration(run: SyncRunLogEntry): String? {
    val started = run.startedAt ?: return null
    val completed = run.completedAt ?: return null
    val seconds = try {
        java.time.Duration.between(
            LocalDateTime.parse(started), LocalDateTime.parse(completed),
        ).seconds
    } catch (_: Exception) {
        return null
    }
    if (seconds < 0) return null
    if (seconds < 60) return "$seconds second${if (seconds != 1L) "s" else ""}"
    val minutes = seconds / 60
    val restSeconds = seconds % 60
    if (minutes < 60) return if (restSeconds > 0) "$minutes min $restSeconds s" else "$minutes min"
    val hours = minutes / 60
    val restMinutes = minutes % 60
    return if (restMinutes > 0) "$hours h $restMinutes min" else "$hours h"
}

@Composable
private fun RunStatusDot(status: String) {
    val color = when (status) {
        "complete" -> MaterialTheme.colorScheme.tertiary
        "failed" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = STATUS_HEADLINES[status] ?: status }
    )
}

/**
 * Ninety days of sync runs, and a way into any one of them.
 *
 * Two things make it readable rather than merely complete, and both are
 * mirrored one-for-one by the Windows _SyncLogPanel:
 *
 *  - Routine no-op runs fold away. A watched folder produces one row per chat
 *    per tick whether or not anything moved, and the runs worth finding are
 *    the ones that uploaded something or failed. Consecutive uneventful runs
 *    collapse *in place* -- a counted row you can unfold -- rather than being
 *    dropped or floated to the bottom, so the chronology stays honest.
 *  - The filter is All / Errors with live counts on the chips, so "were there
 *    any failures?" is answered before you press anything.
 *
 * The two are deliberately orthogonal: a failed run is never uneventful, so
 * under Errors the fold simply has nothing to do.
 */
@Composable
fun SyncLogScreen(
    onBack: () -> Unit,
    backLabel: String = "Back",
    onOpenRun: (Long) -> Unit = {},
) {
    var runs by remember { mutableStateOf(listOf<SyncRunLogEntry>()) }
    var errorsOnly by remember { mutableStateOf(false) }
    // Keyed by the run_id each fold starts at, not by position: a refresh that
    // inserts newer runs above must not reopen or re-fold an unrelated group.
    var expanded by remember { mutableStateOf(setOf<Long>()) }

    LaunchedEffect(Unit) { runs = loadSyncLog() }

    val errorCount = runs.count { it.status == "failed" }
    val visible = if (errorsOnly) runs.filter { it.status == "failed" } else runs

    // Fold each *consecutive* stretch of uneventful runs. In place, because a
    // global "hide no-ops" switch silently changes what "the run above this
    // one" means -- which is the reading this screen exists to support.
    val blocks = remember(visible, expanded) { foldUneventful(visible, expanded) }

    Scaffold(
        topBar = {
            ChatMailTopBar(
                title = "Sync log",
                subtitle = "Last 90 days",
                // The only pushed screen whose label cannot be a constant: it
                // is reached both from Settings and from a finished run on
                // Home, and a hardcoded word would lie on one of the two
                // paths. The caller knows which, so the caller names it.
                backLabel = backLabel,
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (runs.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Counts come from the whole 90 days, never from what is
                    // currently shown: a chip reading "Errors (0)" only
                    // because Errors is selected would be answering its own
                    // question.
                    FilterChip(
                        selected = !errorsOnly,
                        onClick = { errorsOnly = false },
                        label = { Text("All (${runs.size})") },
                    )
                    FilterChip(
                        selected = errorsOnly,
                        onClick = { errorsOnly = true },
                        label = { Text("Errors ($errorCount)") },
                    )
                }
            }

            if (visible.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Says what the screen *will* hold rather than only that
                    // it is empty -- read cold, "No syncs" is as easily a
                    // fault as an accurate nothing-has-happened-yet.
                    Text(
                        if (errorsOnly) "No failed runs in the last 90 days."
                        else "No syncs in the last 90 days.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (errorsOnly)
                            "Everything that ran, finished. Switch to All to see the " +
                                "full history."
                        else
                            "Every sync run lands here - what was uploaded, when, and " +
                                "anything that failed. Nothing has run yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(blocks) { block ->
                        when (block) {
                            is LogBlock.Run -> {
                                SyncRunRow(
                                    run = block.run,
                                    muted = block.muted,
                                    onClick = { onOpenRun(block.run.runId) },
                                )
                                HorizontalDivider()
                            }
                            is LogBlock.Fold -> {
                                val label = if (block.expanded)
                                    "Hide ${block.count} run${if (block.count != 1) "s" else ""} " +
                                        "with nothing new"
                                else
                                    "${block.count} run${if (block.count != 1) "s" else ""} " +
                                        "with nothing new  —  Show"
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expanded = if (block.key in expanded)
                                                expanded - block.key else expanded + block.key
                                        }
                                        .padding(horizontal = 20.dp, vertical = 10.dp),
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A rendered line in the log: either a run, or the counted stand-in for a
 * stretch of runs that changed nothing. */
sealed interface LogBlock {
    data class Run(val run: SyncRunLogEntry, val muted: Boolean) : LogBlock
    data class Fold(val key: Long, val count: Int, val expanded: Boolean) : LogBlock
}

/** Collapse each consecutive stretch of uneventful runs into a Fold, keeping
 * the surrounding order untouched. Pulled out of the composable so it can be
 * reasoned about (and, later, tested) without a UI. */
fun foldUneventful(runs: List<SyncRunLogEntry>, expanded: Set<Long>): List<LogBlock> {
    val out = mutableListOf<LogBlock>()
    var i = 0
    while (i < runs.size) {
        if (!runs[i].uneventful) {
            out.add(LogBlock.Run(runs[i], muted = false))
            i++
            continue
        }
        var j = i
        while (j < runs.size && runs[j].uneventful) j++
        val key = runs[i].runId
        if (key in expanded) {
            for (k in i until j) out.add(LogBlock.Run(runs[k], muted = true))
            out.add(LogBlock.Fold(key, j - i, expanded = true))
        } else {
            out.add(LogBlock.Fold(key, j - i, expanded = false))
        }
        i = j
    }
    return out
}

/**
 * One run: dot, chat and when on the first line; counts and how on the second.
 *
 * This replaces a single "complete · Manual · 12 synced, 3 skipped · 3 Aug"
 * string. Four unrelated facts separated by middots read as one sentence and
 * scan as none of them -- the status is a colour, the time belongs on the
 * right edge where the eye goes to compare rows, and the counts are the only
 * part worth reading in full.
 */
@Composable
private fun SyncRunRow(run: SyncRunLogEntry, muted: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RunStatusDot(run.status)
            Text(
                run.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            Text(
                formatSyncTime(run.startedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 22.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                runCountsText(run),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                TRIGGER_LABELS[run.trigger] ?: run.trigger,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (run.status == "failed" && run.errorMessage != null) {
            Text(
                run.errorMessage.lineSequence().firstOrNull() ?: run.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 22.dp, top = 2.dp),
            )
        }
    }
}

/**
 * One run, in full: what it touched, when, and why it stopped.
 *
 * The list row has room for four facts; a run has a dozen, and the ones that
 * matter when something went wrong -- how long it took, how many messages
 * were parsed versus actually uploaded, the whole error rather than its first
 * line -- are exactly the ones a single row cannot carry.
 *
 * Reloads the 90-day log and picks the run out of it rather than taking a
 * parcelled object through the nav argument: the list is small, the query is
 * the same shared one the list used, and a route that carries only an id
 * survives process death, which a parcelled row would not.
 */
@Composable
fun SyncRunDetailScreen(runId: Long, onBack: () -> Unit) {
    var run by remember { mutableStateOf<SyncRunLogEntry?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(runId) {
        run = loadSyncLog().firstOrNull { it.runId == runId }
        loaded = true
    }

    val current = run
    Scaffold(
        topBar = {
            ChatMailTopBar(
                title = current?.displayName ?: "Sync run",
                backLabel = "Back to sync log",
                onBack = onBack,
            )
        },
    ) { padding ->
        if (current == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                // Only after the load has actually run: showing "not found"
                // during the first frame would accuse the app of losing a run
                // it is still fetching.
                if (loaded) {
                    Text("That run is no longer in the log.", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The log covers the last 90 days, and a chat's runs are removed " +
                            "when the chat is reset or deleted.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RunStatusDot(current.status)
                Text(
                    STATUS_HEADLINES[current.status] ?: current.status,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            if (current.status == "failed" && current.errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text(
                        current.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            DetailSection("Messages")
            DetailField("Parsed from the export", current.messagesParsed.toString())
            DetailField("Uploaded to your mailbox", current.messagesSynced.toString())
            // Named rather than left as a bare "skipped" count: skipped means
            // already in the mailbox from an earlier run, and read cold a
            // skipped count looks like something went missing.
            DetailField("Already there, so skipped", current.messagesSkipped.toString())

            DetailSection("Timing")
            DetailField("Started", formatRunTimeLong(current.startedAt))
            DetailField(
                "Finished",
                if (current.completedAt != null) formatRunTimeLong(current.completedAt)
                else "—  (did not finish)",
            )
            runDuration(current)?.let { DetailField("Took", it) }

            DetailSection("Run")
            DetailField("Chat", current.displayName)
            DetailField("Started by", TRIGGER_LABELS[current.trigger] ?: current.trigger)
            current.lastSyncedTs?.let { DetailField("Newest message synced", it) }
            DetailField("Run number", current.runId.toString())
        }
    }
}

@Composable
private fun DetailSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
    HorizontalDivider()
}

@Composable
private fun DetailField(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}
