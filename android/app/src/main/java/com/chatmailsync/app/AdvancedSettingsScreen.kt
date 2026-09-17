@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val SYNCED_FILE_POLICY_LABELS = mapOf(
    "leave" to "Leave in place",
    "move" to "Move to a \"synced\" subfolder",
    "delete" to "Delete after import",
)

// Moved here from HomeScreen with the rest of the sync-tuning controls it
// keeps company with; not copied, so there is exactly one place that knows
// what a chunk size string means.
private val CHUNK_SIZES = listOf("hour", "day", "week")
private val CHUNK_LABELS = mapOf(
    "hour" to "Hourly emails",
    "day" to "Daily emails",
    "week" to "Weekly emails",
)

/**
 * The deeper-level settings screen (D8): everything that used to sit on
 * Settings' first screen but is not an everyday decision -- watched folder
 * and its auto-import schedule, cutoff date, test run, sync log, chunk
 * size, and a connection self-test. Reached from Settings' "Advanced" row;
 * nothing here is new, it was all moved rather than rebuilt.
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
    // What is on screen, which is not the same as what is saved: a
    // half-typed "2026-0" is neither a cutoff nor a mistake yet, so it lives
    // here and only reaches the preference once it reads as a date.
    var cutoffText by remember { mutableStateOf(cutoffDate) }
    var intervalMenuOpen by remember { mutableStateOf(false) }
    var policyMenuOpen by remember { mutableStateOf(false) }
    var chunkMenuOpen by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    // True from the moment the button is tapped until a result (success,
    // failure or timeout) arrives -- shown immediately rather than waiting
    // on the network, and also what disables the button so a second tap
    // cannot start a second check while one is already running.
    var testingConnection by remember { mutableStateOf(false) }

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
                .padding(14.dp)
                .fadingEdges(scrollState, MaterialTheme.colorScheme.background)
                .verticalScrollbar(scrollState)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Watched folder", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    watchedFolderUri?.let { Uri.parse(it).lastPathSegment ?: it }
                        ?: "No folder chosen",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (watchedFolderUri != null) {
                    TextButton(
                        onClick = onClearFolder,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Clear") }
                }
            }
            OutlinedButton(onClick = onChooseFolder) {
                Text(if (watchedFolderUri == null) "Choose folder" else "Change folder")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-import from this folder")
                    Text(
                        "Checks and syncs in the background on the interval below. Uses a small " +
                            "amount of battery — leave off if you'd rather import manually or with " +
                            "\"Check and sync\".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = autoWatchEnabled,
                    onCheckedChange = onAutoWatchChange,
                    enabled = watchedFolderUri != null,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OutlinedButton(
                        onClick = { intervalMenuOpen = true },
                        enabled = watchedFolderUri != null,
                    ) {
                        Text(WATCH_INTERVAL_LABELS.firstOrNull { it.first == watchIntervalMinutes }?.second ?: "Every $watchIntervalMinutes min")
                    }
                    DropdownMenu(expanded = intervalMenuOpen, onDismissRequest = { intervalMenuOpen = false }) {
                        WATCH_INTERVAL_LABELS.forEach { (minutes, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { onWatchIntervalChange(minutes); intervalMenuOpen = false },
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = onCheckNow,
                    enabled = watchedFolderUri != null && !syncInProgress,
                ) {
                    // Not "Sync now": that is Home's button, and this one is a
                    // different, smaller promise -- look in the watched folder
                    // first, and only then send whatever turned up. It does both,
                    // so it names both. Short because it shares its row with the
                    // interval menu; the section heading above already supplies
                    // "watched folder", so the button doesn't have to repeat it.
                    Text(if (syncInProgress) "Current sync is on" else "Check and sync")
                }
            }
            Text("After import, synced files:", style = MaterialTheme.typography.bodyMedium)
            Box {
                OutlinedButton(
                    onClick = { policyMenuOpen = true },
                    enabled = watchedFolderUri != null,
                ) {
                    Text(SYNCED_FILE_POLICY_LABELS[syncedFilePolicy] ?: syncedFilePolicy)
                }
                DropdownMenu(expanded = policyMenuOpen, onDismissRequest = { policyMenuOpen = false }) {
                    SYNCED_FILE_POLICY_LABELS.forEach { (policy, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { onSyncedFilePolicyChange(policy); policyMenuOpen = false },
                        )
                    }
                }
            }

            HorizontalDivider()

            // A floor, never a window: "do not send me anything from before
            // this". There is no matching "to" field on purpose -- the app's
            // whole job is to keep going forwards, and a ceiling would mean it
            // stops.
            Text("Cutoff date", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = cutoffText,
                    onValueChange = {
                        cutoffText = it
                        // Committed the moment it reads, and withheld while it
                        // does not. There is no Save button on this screen, so
                        // a date the app cannot compare must be refused here,
                        // under the field, rather than stored and discovered
                        // later as a sync that quietly sent nothing.
                        if (CutoffDate.isReadable(it)) onCutoffDateChange(it.trim())
                    },
                    label = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    isError = !CutoffDate.isReadable(cutoffText),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { cutoffText = ""; onCutoffDateChange("") },
                    enabled = cutoffText.isNotEmpty(),
                ) { Text("Clear") }
            }
            if (!CutoffDate.isReadable(cutoffText)) {
                Text(
                    "Enter the date as YYYY-MM-DD, or leave it blank for no cutoff.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Messages older than this are never sent. Leave it blank to send " +
                    "everything. A chat that has already been synced past this date " +
                    "is unaffected — the app never goes back over ground it has " +
                    "covered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text("Test run", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Rehearse without sending",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Shows what would happen — writes nothing to your mailbox. " +
                            "Stays on until you turn it off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = dryRunDefault, onCheckedChange = onDryRunDefaultChange)
            }

            HorizontalDivider()

            Text("Chunk size", style = MaterialTheme.typography.titleMedium)
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

            HorizontalDivider()

            TextButton(onClick = onOpenSyncLog) { Text("Sync log") }

            HorizontalDivider()

            // "Developer tools" heading dropped, same reasoning as on Mail
            // account: this button is connection-diagnostic, not a dev-only
            // affordance.
            Text("Mail server", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                enabled = !testingConnection,
                onClick = {
                    testingConnection = true
                    testResult = "Testing connection…"
                    onTestConnection { result ->
                        testingConnection = false
                        testResult = result
                    }
                },
            ) {
                Text("Test connection")
            }
            // Directly under the button, always -- the same spot whether it
            // is the in-progress line or the final result.
            testResult?.let { Text(it, modifier = Modifier.fillMaxWidth()) }

            HorizontalDivider()

            // Re-opens the first-run walkthrough on demand, e.g. to redo the
            // mail setup steps or revisit the auto-import explanation --
            // without resetting anything. Nothing here is cleared just by
            // opening it: an existing mailbox, folder, or interval only
            // changes if the walkthrough is actually completed with new
            // values.
            Text("Setup walkthrough", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onRunSetupAgain) {
                Text("Run setup again")
            }
            Text(
                "Goes through mail setup and auto-import again. Nothing is " +
                    "cleared unless you choose to change it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
