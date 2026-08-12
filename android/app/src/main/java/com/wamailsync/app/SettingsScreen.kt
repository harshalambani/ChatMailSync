@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wamailsync.app

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Taps on the version line needed to reveal the demoted Gmail sign-in option.
// Seven, with no counter and no hint, matching gui.py's
// _SettingsPanel._VERSION_CLICKS_TO_UNLOCK: nobody arrives here by accident,
// and anyone who has been told about it can count. There is deliberately no
// re-lock -- clearing app data is the reset, which is proportionate for a
// maintainer-facing switch.
private const val VERSION_TAPS_TO_UNLOCK = 7

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
    val context = LocalContext.current
    var themeMenuOpen by remember { mutableStateOf(false) }
    var intervalMenuOpen by remember { mutableStateOf(false) }
    // Not remembered across leaving the screen: a half-finished tap run should
    // not survive a trip elsewhere and complete itself later.
    var versionTaps by remember { mutableStateOf(0) }
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
            // Read from BuildConfig, which gradle generates from versionName /
            // versionCode, so this cannot drift from the APK. It used to be the
            // hardcoded string "WA Mail Sync — Android (dev build)", which a
            // release-signed 1.0.1 went on displaying -- worse than showing
            // nothing, because it was confidently wrong.
            //
            // versionCode is shown alongside the name because it is the number
            // `adb shell dumpsys package` reports and the one the store orders
            // by, so it is what actually answers "am I on the current build?".
            //
            // Seven taps here toggle the Gmail sign-in option on the mail
            // account screen -- the twin of the click gesture on gui.py's
            // version label, in the same place for the same reason. It is
            // undiscoverable on purpose: the option is for the maintainer and
            // for recovering an existing OAuth user, not a feature to find.
            //
            // A toggle rather than a one-way reveal because there is no undo
            // otherwise: desktop can hand-edit settings.json, but the only way
            // to clear this flag on a phone was `pm clear`, which also destroys
            // the Keystore mail credential, the sync state and the watched
            // folder grant. Seven more taps is a cheaper undo than losing all
            // of that.
            //
            // Clearing the flag cannot hide the option from someone actually
            // using OAuth: oauthIsVisible still returns true from a saved
            // gmail_oauth backend or a connected account, and latchOauthIfInUse
            // will set the flag again on next launch. The Toast says so rather
            // than claiming a hide that did not happen.
            Text(
                "WA Mail Sync ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
                    if (BuildConfig.DEBUG) " — debug build" else "",
                modifier = Modifier.clickable {
                    versionTaps += 1
                    if (versionTaps >= VERSION_TAPS_TO_UNLOCK) {
                        versionTaps = 0
                        val unlocking = !AppPrefs.isOauthUnlocked(context)
                        AppPrefs.setOauthUnlocked(context, unlocking)
                        // Kept to two short lines on purpose: a Toast is
                        // clipped, not scrolled, and the first draft lost its
                        // final clause to an ellipsis on a 1080px device. The
                        // reason it is hidden is spelled out in full on the
                        // Mail account screen, next to the choice itself,
                        // which is where it can actually be read -- this only
                        // has to say where to go.
                        val message = when {
                            unlocking ->
                                "Google sign-in is now offered on the " +
                                    "Mail account screen."
                            AppPrefs.isOauthVisible(context) ->
                                "Google sign-in stays shown: it is in use " +
                                    "on this phone."
                            else ->
                                "Google sign-in is hidden again."
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                },
            )
            TextButton(onClick = onOpenHelp) { Text("Help & FAQ") }
            TextButton(onClick = onOpenSyncLog) { Text("Sync log") }
        }
    }
}
