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
import androidx.compose.ui.platform.LocalContext
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
    dryRunDefault: Boolean,
    onDryRunDefaultChange: (Boolean) -> Unit,
    onSaveBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    migrationBusy: Boolean,
    migrationStatus: String?,
) {
    val context = LocalContext.current
    var themeMenuOpen by remember { mutableStateOf(false) }
    var intervalMenuOpen by remember { mutableStateOf(false) }
    var policyMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        // Zero, deliberately: MainActivity's Scaffold has already padded
        // this NavHost for the status bar and the bottom bars, and insets
        // are not consumed by being turned into padding -- so a screen
        // Scaffold left on the default reserves the same strips a second
        // time. That silently cost about a row and a half of list height
        // on every screen, which is how two exports ended up below the
        // fold on the import picker.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { ChatMailTopBar(title = "Settings") },
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
            // Mail backend/account setup moved to its own screen
            // (MailAccountScreen) — it was the single longest section here
            // and the one users revisit least often once configured, so this
            // screen now just shows a status summary and a way in, instead
            // of making everyone scroll past the full IMAP form to reach
            // Theme and Watched folder.
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
                    // interval menu; the section heading above supplies "watched
                    // folder", which Windows has to carry in the label itself.
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

            // Moved off Home. It is a persisted setting, not a per-run choice,
            // and on Home it sat as a full title-plus-subtitle row directly
            // above the primary button while silently redefining what that
            // button does -- the least-used control on the screen given the
            // most prominent place, with the worst failure mode (leave it on,
            // and nothing ever reaches the mailbox). Home still says loudly
            // that it is on, and still offers it once before the first run.
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

            // Worth being explicit about what this is for, because "backup" in
            // an archiving app invites the wrong reading: the mailbox is the
            // archive, and it is already safe on a mail server. What is only on
            // this phone is the record of which messages have already been
            // sent. Lose that and nothing is lost -- everything is sent again,
            // into a mailbox that has no way to tell the copies apart.
            //
            // Headed "Move to a new phone" until v1.17.0, which hid it from
            // everyone who was not moving: the same file is what gets you back
            // after a reset, a reinstall or Clear data, and those happen to
            // people who never buy a phone.
            Text("Backup & restore", style = MaterialTheme.typography.titleMedium)
            Text(
                "Saves what this phone knows about what it has already sent. Keep one, " +
                    "and a reset, a reinstall or another device carries on from here " +
                    "instead of mailing everything a second time. Your chats are already " +
                    "safe in your mailbox — this is not a copy of them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onSaveBackup, enabled = !migrationBusy) {
                    Text("Save a backup")
                }
                OutlinedButton(onClick = onRestoreBackup, enabled = !migrationBusy) {
                    Text("Restore from a backup")
                }
            }
            // Re-read whenever the migration state moves, which is what a save
            // finishing looks like from here -- a backup nobody can date is a
            // backup nobody trusts, and "I think I did one" is exactly the
            // belief that costs a mailbox its second copy of everything.
            val lastBackupAt = remember(migrationBusy, migrationStatus) {
                AppPrefs.getLastBackupAt(context)
            }
            Text(
                Migration.describeLastBackup(lastBackupAt),
                style = MaterialTheme.typography.bodySmall,
                color = if (Migration.backupIsStale(lastBackupAt))
                    MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // In place, under the buttons -- not a dialog. Everything this can
            // say is an outcome to read, and none of it needs a decision, so a
            // box demanding to be dismissed would only add a tap.
            migrationStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                "Your mail password is never included in a backup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text("About / Help", style = MaterialTheme.typography.titleMedium)
            // Read from BuildConfig, which gradle generates from versionName /
            // versionCode, so this cannot drift from the APK. It used to be the
            // hardcoded string "Chat Mail Sync — Android (dev build)", which a
            // release-signed 1.0.1 went on displaying -- worse than showing
            // nothing, because it was confidently wrong.
            //
            // versionCode is shown alongside the name because it is the number
            // `adb shell dumpsys package` reports and the one the store orders
            // by, so it is what actually answers "am I on the current build?".
            //
            Text(
                "Chat Mail Sync ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
                    if (BuildConfig.DEBUG) " — debug build" else "",
            )
            TextButton(onClick = onOpenHelp) { Text("Help & FAQ") }
            TextButton(onClick = onOpenSyncLog) { Text("Sync log") }
        }
    }
}
