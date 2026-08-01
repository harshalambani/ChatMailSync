@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wamailsync.app

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

private val THEME_LABELS = mapOf(
    "system" to "Match system",
    "light" to "Light",
    "dark" to "Dark",
)

// WorkManager's PeriodicWorkRequest has a hard 15-minute floor (Android
// platform-enforced, not a WorkManager default) — no shorter interval is
// achievable regardless of what's offered here.
private val WATCH_INTERVAL_LABELS = listOf(
    15L to "Every 15 min",
    30L to "Every 30 min",
    60L to "Every hour",
    180L to "Every 3 hours",
    360L to "Every 6 hours",
    720L to "Every 12 hours",
    1440L to "Once a day",
)

private val SYNCED_FILE_POLICY_LABELS = mapOf(
    "leave" to "Leave in place",
    "move" to "Move to a \"synced\" subfolder",
    "delete" to "Delete after import",
)

@Composable
fun SettingsScreen(
    mailAccountSummary: String,
    onOpenMailAccount: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenSyncLog: () -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
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
) {
    var themeMenuOpen by remember { mutableStateOf(false) }
    var intervalMenuOpen by remember { mutableStateOf(false) }
    var policyMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { WaMailTopBar(title = "Settings") },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Mail backend/account setup moved to its own screen
            // (MailAccountScreen) — it was the single longest section here
            // and the one users revisit least often once configured, so this
            // screen now just shows a status summary and a way in, instead
            // of making everyone scroll past the full IMAP/OAuth form to
            // reach Theme and Watched folder.
            OutlinedButton(
                onClick = onOpenMailAccount,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Mail account", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        mailAccountSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Box {
                OutlinedButton(onClick = { themeMenuOpen = true }) {
                    Text(THEME_LABELS[themeMode] ?: themeMode)
                }
                DropdownMenu(expanded = themeMenuOpen, onDismissRequest = { themeMenuOpen = false }) {
                    THEME_LABELS.forEach { (mode, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { onThemeModeChange(mode); themeMenuOpen = false },
                        )
                    }
                }
            }

            HorizontalDivider()

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
                            "\"Sync now\".",
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
                    Text(if (syncInProgress) "Current sync is on" else "Sync now")
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

            Text("About / Help", style = MaterialTheme.typography.titleMedium)
            Text("WA Mail Sync — Android (dev build)")
            TextButton(onClick = onOpenHelp) { Text("Help & FAQ") }
            TextButton(onClick = onOpenSyncLog) { Text("Sync log") }
        }
    }
}
