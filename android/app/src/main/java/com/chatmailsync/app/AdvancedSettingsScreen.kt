@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// internal, not private: Migration's restoreSummary (Batch 7b) reuses these
// same label maps for its restore-confirmation lines rather than keeping a
// second copy that could drift from what this screen shows.
internal val SYNCED_FILE_POLICY_LABELS = mapOf(
    "leave" to "Leave in place",
    "move" to "Move to a \"synced\" subfolder",
    "delete" to "Delete after import",
)

// Moved here from HomeScreen with the rest of the sync-tuning controls it
// keeps company with; not copied, so there is exactly one place that knows
// what a chunk size string means.
internal val CHUNK_SIZES = listOf("hour", "day", "week")
internal val CHUNK_LABELS = mapOf(
    "hour" to "Hourly emails",
    "day" to "Daily emails",
    "week" to "Weekly emails",
)

/**
 * The deeper-level settings screen (D8), as grouped list rows (UX-01):
 * AUTOMATIC IMPORT, WHAT GETS SENT, TESTING & TOOLS. Same settings, same
 * keys, same behaviour as before -- only the presentation changed. Each
 * dropdown and the date box now opens a dialog with OK and Cancel; Cancel
 * leaves the stored value as it was. Reached from Settings' "Advanced" row.
 */
@Composable
fun AdvancedSettingsScreen(
    onBack: () -> Unit,
    backLabel: String = "Settings",
    onOpenSyncLog: () -> Unit,
    onRunSetupAgain: () -> Unit = {},
    watchedFolderUri: String?,
    onChooseFolder: () -> Unit,
    onClearFolder: () -> Unit,
    autoWatchEnabled: Boolean,
    onAutoWatchChange: (Boolean) -> Unit,
    watchIntervalMinutes: Long,
    onWatchIntervalChange: (Long) -> Unit,
    onCheckNow: () -> Unit,
    syncInProgress: Boolean,
    syncedFilePolicy: String,
    onSyncedFilePolicyChange: (String) -> Unit,
    cutoffDate: String = "",
    onCutoffDateChange: (String) -> Unit = {},
    dryRunDefault: Boolean,
    onDryRunDefaultChange: (Boolean) -> Unit,
    chunkSize: String,
    onChunkSizeChange: (String) -> Unit,
    onTestConnection: ((String) -> Unit) -> Unit,
) {
    // Which dialog is open, if any. Plain strings, no secrets.
    var dialog by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    // True from the moment the row is tapped until a result (success,
    // failure or timeout) arrives -- also what stops a second tap starting a
    // second check while one is already running.
    var testingConnection by remember { mutableStateOf(false) }
    val hasFolder = watchedFolderUri != null

    when (dialog) {
        "watch_interval" -> OptionDialog(
            title = "Check interval",
            options = WATCH_INTERVAL_LABELS,
            current = watchIntervalMinutes,
            onCommit = onWatchIntervalChange,
            onDismiss = { dialog = null },
        )
        "after_import" -> OptionDialog(
            title = "After import, synced files",
            options = SYNCED_FILE_POLICY_LABELS.toList(),
            current = syncedFilePolicy,
            onCommit = onSyncedFilePolicyChange,
            onDismiss = { dialog = null },
        )
        "chunk_size" -> OptionDialog(
            title = "Chunk size",
            options = CHUNK_SIZES.map { it to (CHUNK_LABELS[it] ?: it) },
            current = chunkSize,
            onCommit = onChunkSizeChange,
            onDismiss = { dialog = null },
        )
        "cutoff_date" -> CutoffDialog(
            current = cutoffDate,
            onCommit = onCutoffDateChange,
            onDismiss = { dialog = null },
        )
    }

    Scaffold(
        // Zero, deliberately: MainActivity's Scaffold has already padded
        // this NavHost for the status bar and the bottom bars, and insets
        // are not consumed by being turned into padding -- so a screen
        // Scaffold left on the default reserves the same strips a second
        // time.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatMailTopBar(
                title = "Advanced",
                backLabel = backLabel,
                onBack = onBack,
            )
        },
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .fadingEdges(scrollState, MaterialTheme.colorScheme.background)
                .verticalScrollbar(scrollState)
                .verticalScroll(scrollState)
                .padding(bottom = 16.dp),
        ) {
            ADVANCED_SECTIONS.forEach { section ->
                SettingsSectionHeader(section.header)
                section.rows.forEachIndexed { index, row ->
                    when (row.id) {
                        "watched_folder" -> SettingsListRow(
                            icon = Icons.Filled.LocationOn,
                            title = "Watched folder",
                            subtitle = watchedFolderUri?.let { Uri.parse(it).lastPathSegment ?: it }
                                ?: "No folder chosen",
                            kind = if (hasFolder) SubtitleKind.VALUE else SubtitleKind.PLAIN,
                            onClick = onChooseFolder,
                            trailing = if (hasFolder) {
                                {
                                    TextButton(
                                        onClick = onClearFolder,
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ),
                                    ) { Text("Clear") }
                                }
                            } else {
                                null
                            },
                        )

                        "auto_import" -> SettingsListRow(
                            icon = Icons.Filled.PlayArrow,
                            title = "Auto-import",
                            subtitle = "Checks and syncs in the background on the interval below. " +
                                "Uses a small amount of battery.",
                            kind = SubtitleKind.PLAIN,
                            enabled = hasFolder,
                            onClick = { onAutoWatchChange(!autoWatchEnabled) },
                            chevron = false,
                            trailing = {
                                Switch(
                                    checked = autoWatchEnabled,
                                    onCheckedChange = onAutoWatchChange,
                                    enabled = hasFolder,
                                )
                            },
                        )

                        "watch_interval" -> SettingsListRow(
                            icon = Icons.Filled.Notifications,
                            title = "Check interval",
                            subtitle = WATCH_INTERVAL_LABELS.firstOrNull { it.first == watchIntervalMinutes }?.second
                                ?: "Every $watchIntervalMinutes min",
                            kind = SubtitleKind.VALUE,
                            enabled = hasFolder,
                            onClick = { dialog = "watch_interval" },
                        )

                        "after_import" -> SettingsListRow(
                            icon = Icons.Filled.CheckCircle,
                            title = "After import",
                            subtitle = SYNCED_FILE_POLICY_LABELS[syncedFilePolicy] ?: syncedFilePolicy,
                            kind = SubtitleKind.VALUE,
                            enabled = hasFolder,
                            onClick = { dialog = "after_import" },
                        )

                        "check_now" -> SettingsListRow(
                            icon = Icons.Filled.Send,
                            title = "Check and sync",
                            // Not "Sync now": that is Home's button, and this
                            // one is a smaller promise -- look in the watched
                            // folder first, and only then send what turned up.
                            subtitle = if (syncInProgress) {
                                "Current sync is on"
                            } else {
                                "Look in the watched folder, then send what turned up"
                            },
                            kind = SubtitleKind.PLAIN,
                            enabled = hasFolder && !syncInProgress,
                            onClick = onCheckNow,
                            chevron = false,
                        )

                        "cutoff_date" -> SettingsListRow(
                            icon = Icons.Filled.DateRange,
                            title = "Cutoff date",
                            subtitle = cutoffSubtitle(cutoffDate),
                            kind = SubtitleKind.VALUE,
                            onClick = { dialog = "cutoff_date" },
                        )

                        "chunk_size" -> SettingsListRow(
                            icon = Icons.Filled.Menu,
                            title = "Chunk size",
                            subtitle = CHUNK_LABELS[chunkSize] ?: chunkSize,
                            kind = SubtitleKind.VALUE,
                            onClick = { dialog = "chunk_size" },
                        )

                        "test_run" -> SettingsListRow(
                            icon = Icons.Filled.CheckCircle,
                            title = "Test run",
                            subtitle = "Rehearse without sending. Shows what would happen, " +
                                "writes nothing to your mailbox. Stays on until you turn it off.",
                            kind = SubtitleKind.PLAIN,
                            onClick = { onDryRunDefaultChange(!dryRunDefault) },
                            chevron = false,
                            trailing = {
                                Switch(checked = dryRunDefault, onCheckedChange = onDryRunDefaultChange)
                            },
                        )

                        "test_connection" -> SettingsListRow(
                            icon = Icons.Filled.MailOutline,
                            title = "Test connection",
                            // Directly in the row, always -- the same spot
                            // whether it is the in-progress line or the result.
                            subtitle = testResult ?: "Check that the mail server accepts your sign-in",
                            kind = SubtitleKind.PLAIN,
                            enabled = !testingConnection,
                            chevron = false,
                            onClick = {
                                testingConnection = true
                                testResult = "Testing connection…"
                                onTestConnection { result ->
                                    testingConnection = false
                                    testResult = result
                                }
                            },
                        )

                        "sync_log" -> SettingsListRow(
                            icon = Icons.Filled.List,
                            title = "Sync log",
                            subtitle = "What each sync did, run by run",
                            kind = SubtitleKind.PLAIN,
                            onClick = onOpenSyncLog,
                        )

                        // Re-opens the first-run walkthrough on demand without
                        // resetting anything: an existing mailbox, folder or
                        // interval only changes if the walkthrough is
                        // completed with new values.
                        "run_setup_again" -> SettingsListRow(
                            icon = Icons.Filled.Refresh,
                            title = "Run setup again",
                            subtitle = "Goes through mail setup and auto-import again. " +
                                "Nothing is cleared unless you choose to change it.",
                            kind = SubtitleKind.PLAIN,
                            onClick = onRunSetupAgain,
                        )
                    }
                    if (index != section.rows.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
    }
}
