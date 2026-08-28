@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp

// Mirrors extract_chat_info's prefix-stripping (src/parser.py) so the queued
// name shown here matches the display name used everywhere else, without the
// repeated "WhatsApp Chat with " boilerplate eating the row's limited width.
private const val WA_PREFIX = "whatsapp chat with "

// How many queued files Home lists before handing off to the Sync queue
// screen. See the comment at the call site for why there is a cap at all.
private const val HOME_QUEUE_ROWS = 4

// internal, not private: the in-app export picker shows the same names over
// the same files, and two copies of this rule would eventually disagree.
internal fun displayNameFor(filename: String): String {
    val stem = filename.substringBeforeLast('.')
    return if (stem.lowercase().startsWith(WA_PREFIX)) stem.substring(WA_PREFIX.length) else stem
}

/**
 * One system setting standing between automatic syncing and actually running.
 *
 * Deliberately not styled as an error: nothing has failed, and the app has not
 * been asked to do anything it could not do. It is a warning about a sync that
 * will quietly not happen later, so it gets `surfaceVariant` -- present and
 * distinct from the cards around it, without the red that the app reserves for
 * a run that actually went wrong.
 *
 * The button label names the screen it opens rather than promising a fix; see
 * [BackgroundIssue.actionLabel].
 */
@Composable
private fun BackgroundHealthCard(issue: BackgroundIssue, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(issue.title, style = MaterialTheme.typography.titleSmall)
            Text(issue.detail, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onAction) { Text(issue.actionLabel) }
        }
    }
}

/**
 * Where the archive stands, above the controls that change it.
 *
 * Home could say what you can do next but not whether the last attempt
 * worked: the answer sat one navigation away in the sync log, and the only
 * thing on this screen resembling history was a transient "Last result" line
 * that says what *you* just did, not what the app has been doing in the
 * background. A watched folder syncs without anyone watching, so a failure
 * could sit unnoticed indefinitely.
 *
 * Deliberately not a tab strip. This screen has three top-level tabs under it
 * already; a second row of them inside Home would have made the history a
 * place to visit rather than something you simply see on arrival, which is
 * the whole point.
 *
 * Absent entirely until something has run -- an empty summary on a fresh
 * install is a box explaining that there is nothing to explain.
 */
@Composable
private fun SyncStatusBlock(summary: SyncSummary, onOpenSyncLog: () -> Unit) {
    val status = summary.lastStatus ?: "pending"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSyncLog),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RunStatusDot(status)
                Text(
                    text = when (summary.lastStatus) {
                        "failed" -> "Last sync failed"
                        "complete" -> "Last sync finished"
                        // Nothing has finished, but runs exist -- one is going
                        // on right now. The progress bar says the rest.
                        else -> "Sync in progress"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text("Sync log ›", style = MaterialTheme.typography.labelLarge)
            }
            if (summary.lastStatus != null) {
                Text(
                    "${relativeTime(summary.lastCompletedAt)} — ${summaryCountsText(summary)}" +
                        (summary.lastDisplayName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // The count is over the same 90-day window the log shows, so
            // tapping through lands on exactly these runs and not a longer or
            // shorter history.
            if (summary.failedRuns > 0) {
                Text(
                    "${plural(summary.failedRuns, "run")} failed in the last " +
                        "${summary.windowDays} days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private val CHUNK_SIZES = listOf("hour", "day", "week")
private val CHUNK_LABELS = mapOf(
    "hour" to "Hourly emails",
    "day" to "Daily emails",
    "week" to "Weekly emails",
)

@Composable
fun HomeScreen(
    accountLabel: String?,
    backendReady: Boolean,
    connectActionLabel: String,
    connectError: String?,
    onConnect: () -> Unit,
    inboxFiles: List<Pair<String, Long>>,
    onImportPick: () -> Unit,
    onPreview: (String) -> String,
    onRemoveFile: (String) -> Unit,
    chunkSize: String,
    onChunkSizeChange: (String) -> Unit,
    dryRunDefault: Boolean,
    onDryRunDefaultChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    lastResult: String,
    syncInProgress: Boolean,
    backgroundIssues: List<BackgroundIssue> = emptyList(),
    onBackgroundIssueAction: (BackgroundIssue) -> Unit = {},
    onOpenSyncLog: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    lastBackupAt: Long = 0L,
) {
    // Re-read whenever a sync starts or stops, so the block is right the
    // moment a run ends rather than on the next visit to this screen.
    var summary by remember { mutableStateOf<SyncSummary?>(null) }
    LaunchedEffect(syncInProgress, lastResult) {
        summary = try {
            loadSyncStatus()
        } catch (_: Exception) {
            // A summary is a convenience; failing to read it must never be
            // the reason Home does not render.
            null
        }
    }
    var previewText by remember { mutableStateOf<String?>(null) }
    var chunkMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        // Zero, deliberately: MainActivity's Scaffold has already padded
        // this NavHost for the status bar and the bottom bars, and insets
        // are not consumed by being turned into padding -- so a screen
        // Scaffold left on the default reserves the same strips a second
        // time. That silently cost about a row and a half of list height
        // on every screen, which is how two exports ended up below the
        // fold on the import picker.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { ChatMailTopBar(title = "Chat Mail Sync", subtitle = "Private mail archive") },
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .fadingEdges(scrollState, MaterialTheme.colorScheme.background)
                .verticalScrollbar(scrollState)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Only when there is something to do about it.
            //
            // This used to be here unconditionally, and in the settled case it
            // was pure restatement: the masthead carries a connection pill on
            // every screen in the app, so a second "Connected as ..." line
            // directly under it said the same thing twice and cost a row of
            // height at the top of a column whose primary button is at the
            // bottom. What it is worth keeping for is the unsettled case --
            // no account yet, or a connection that failed -- where it is not
            // a status line at all but the next thing to fix, with the button
            // that fixes it attached. The address itself was never the point
            // here; it lives on the Mail account screen.
            val connectionSettled = accountLabel != null && connectError == null
            if (!connectionSettled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (backendReady) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        // Was a hardcoded Material green, the one
                                        // colour in the app that belonged to no
                                        // palette. tertiary is the same idea, in
                                        // the app's own muted family, and follows
                                        // the theme into dark mode.
                                        .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                                )
                            }
                            Text(
                                text = accountLabel?.let { "Connected as $it" }
                                    ?: "Not connected",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        connectError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = onConnect) {
                        Text(connectActionLabel)
                    }
                }
                HorizontalDivider()
            }

            // Anything the system is doing that will stop automatic syncing.
            // Above the inbox because a queue of files means nothing if the
            // thing meant to send them has been put to sleep. It shares the top
            // of the column with the connection line for the same reason: both
            // are the app telling you it cannot do its job, and that is the
            // only class of thing allowed above the button.
            backgroundIssues.forEach { issue ->
                BackgroundHealthCard(issue = issue, onAction = { onBackgroundIssueAction(issue) })
            }

            // Inbox + sync — one card: these two are really one workflow
            // (what's waiting -> how to push it), not two separate features.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (inboxFiles.isEmpty()) "Nothing waiting to sync"
                        else "${plural(inboxFiles.size, "file")} ready to sync",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (inboxFiles.isEmpty()) {
                        // Spells out the WhatsApp menu path. "Export chat" as
                        // a bare quoted phrase assumed the user already knew
                        // where it lives, and it is buried three levels down
                        // -- it is the one step nobody guesses.
                        Text(
                            "In WhatsApp, open a chat and tap ⋮ → More → Export chat. " +
                                "Share the file to this app, or import it below.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // Capped, and the cap is the whole point. This card sits
                    // above "Sync now" in a page that scrolls, so a queue long
                    // enough to be worth managing is exactly a queue long
                    // enough to push the button that acts on it off the bottom
                    // of the screen -- thirty exports is roughly three
                    // screen-heights of rows. Four is what still leaves the
                    // split control and the button in view on the smallest
                    // phone we target. Everything past four lives on the Sync
                    // queue screen, which is also the only place with room for
                    // removing files in bulk.
                    inboxFiles.take(HOME_QUEUE_ROWS).forEach { (name, size) ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                displayNameFor(name),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            TextButton(
                                onClick = { previewText = onPreview(name) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                            ) {
                                Text("Preview")
                            }
                            IconButton(onClick = { onRemoveFile(name) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove ${displayNameFor(name)} from queue")
                            }
                        }
                    }
                    if (inboxFiles.size > HOME_QUEUE_ROWS) {
                        // Says how many are hidden rather than just "Show all",
                        // because the count is the reason to tap it.
                        TextButton(
                            onClick = onOpenQueue,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Show all ${inboxFiles.size} \u2013 manage the queue")
                        }
                    }

                    // Weighted by what the next step actually is. With an empty
                    // queue there is nothing to sync and importing IS the
                    // primary action, so it is filled and full width. Once
                    // files are queued the primary action becomes Sync now,
                    // and two filled buttons in one card would compete for the
                    // same tap. No count to agree with either way -- the
                    // picker is multi-select, so the plural is simply correct.
                    if (inboxFiles.isEmpty()) {
                        Button(onClick = onImportPick, modifier = Modifier.fillMaxWidth()) {
                            Text("Choose exports to import")
                        }
                    } else {
                        OutlinedButton(onClick = onImportPick, modifier = Modifier.fillMaxWidth()) {
                            Text("Add more exports…")
                        }
                    }

                    HorizontalDivider()

                    // Split-by-chunk-size: how many messages land in one email.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Split into: ", style = MaterialTheme.typography.bodyMedium)
                        Box {
                            TextButton(onClick = { chunkMenuOpen = true }) {
                                Text(CHUNK_LABELS[chunkSize] ?: chunkSize)
                            }
                            DropdownMenu(expanded = chunkMenuOpen, onDismissRequest = { chunkMenuOpen = false }) {
                                CHUNK_SIZES.forEach { size ->
                                    DropdownMenuItem(
                                        text = { Text(CHUNK_LABELS[size] ?: size) },
                                        onClick = { onChunkSizeChange(size); chunkMenuOpen = false },
                                    )
                                }
                            }
                        }
                    }

                    // The switch itself now lives in Settings. It is a
                    // persisted setting, not a per-run choice, and giving it a
                    // title-plus-subtitle row directly above the primary button
                    // made the least-used control on the screen the most
                    // prominent one. What stays here is the consequence: while
                    // it is on, that button quietly means something else, and
                    // leaving it on by accident means nothing ever reaches the
                    // mailbox. So it is stated where the button is, with the
                    // way out attached.
                    if (dryRunDefault) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Test run is on — nothing will be sent to your mailbox",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                TextButton(onClick = { onDryRunDefaultChange(false) }) {
                                    Text("Turn off")
                                }
                            }
                        }
                    }

                    Button(
                        onClick = onSyncNow,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !syncInProgress && inboxFiles.isNotEmpty() &&
                            (dryRunDefault || backendReady),
                    ) {
                        Text(
                            if (syncInProgress) "Current sync is on"
                            else if (dryRunDefault) "Run test sync"
                            else "Sync now"
                        )
                    }

                    // Offered once and then never again: before anything has
                    // ever run there is a real reason to want a rehearsal, and
                    // afterwards the setting is in Settings like every other
                    // setting. Shown as a link, not a switch, because from here
                    // it is a thing to do rather than a state to maintain.
                    if (!dryRunDefault && summary?.totalRuns.let { it == null || it == 0 }) {
                        TextButton(onClick = { onDryRunDefaultChange(true) }) {
                            Text("Try it first, without sending anything")
                        }
                    }
                }
            }

            // Below the card, not above it. What happened last is history,
            // and history that sits over the top of the control you came here
            // to press is history charging rent for the space. Everything
            // above this line is either something to do or something blocking
            // it from being done.
            summary?.takeIf { it.totalRuns > 0 }?.let {
                SyncStatusBlock(summary = it, onOpenSyncLog = onOpenSyncLog)
            }

            // A line, in the window, with the fix attached -- not a dialog and
            // not a banner at the top. Nothing here is urgent enough to stand
            // over the sync button, but it is worth saying, because the cost of
            // never reading it is every chat mailed a second time after a reset
            // into a mailbox that cannot tell the copies apart.
            //
            // Passed in rather than read here: a save happens on another
            // screen, and MainActivity re-reads it on the way back, so this
            // stays a screen that renders what it is given.
            if (Migration.backupIsStale(lastBackupAt)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (lastBackupAt <= 0L)
                            "No backup yet. Without one, a reset or a reinstall makes the app " +
                                "mail every chat again."
                        else Migration.describeLastBackup(lastBackupAt) +
                            " - old enough to be worth refreshing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenBackup) { Text("Back up") }
                }
            }

            previewText?.let {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(it, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            if (lastResult != "Nothing run yet.") {
                HorizontalDivider()
                Text("Last result:", style = MaterialTheme.typography.titleSmall)
                Text(lastResult, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
