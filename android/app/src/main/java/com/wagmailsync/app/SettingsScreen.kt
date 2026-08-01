@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wagmailsync.app

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
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Mirrors android_api.imap_providers(), itself a mirror of
 * src/config.py's IMAP_PROVIDERS — [host] is "" for "custom" (no preset). */
data class ImapProviderInfo(val key: String, val label: String, val host: String, val port: Int)

private val THEME_LABELS = mapOf(
    "system" to "Match system",
    "light" to "Light",
    "dark" to "Dark",
)

// Matches gui.py's _BACKEND_LABELS exactly, so the two apps describe the
// same choice with the same words.
private val BACKEND_LABELS = mapOf(
    AppPrefs.MAIL_BACKEND_IMAP to "Email app password (IMAP)",
    AppPrefs.MAIL_BACKEND_GMAIL_OAUTH to "Google sign-in (OAuth)",
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
    connectedEmail: String?,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
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
    accessTokenAvailable: Boolean,
    onTestConnection: ((String) -> Unit) -> Unit,
    mailBackend: String,
    onMailBackendChange: (String) -> Unit,
    imapProviders: List<ImapProviderInfo>,
    imapProvider: String,
    onImapProviderChange: (String) -> Unit,
    imapHost: String,
    onImapHostChange: (String) -> Unit,
    imapPort: Int,
    onImapPortChange: (Int) -> Unit,
    imapEmail: String,
    onImapEmailChange: (String) -> Unit,
    imapPasswordSaved: Boolean,
    onSaveImapSettings: (String, String, Int, String, String, (Boolean, String) -> Unit) -> Unit,
    onForgetImapPassword: () -> Unit,
) {
    var testResult by remember { mutableStateOf<String?>(null) }
    var themeMenuOpen by remember { mutableStateOf(false) }
    var intervalMenuOpen by remember { mutableStateOf(false) }
    var policyMenuOpen by remember { mutableStateOf(false) }
    var backendMenuOpen by remember { mutableStateOf(false) }
    var providerMenuOpen by remember { mutableStateOf(false) }
    // Password is deliberately never pre-filled from a saved value — Compose
    // state here is plain (unencrypted) memory, and re-displaying a saved
    // password back into a text field is exactly the kind of surfacing the
    // security spec for this feature rules out. An empty field with
    // imapPasswordSaved shown as a separate status line is the same UX
    // gui.py's Settings window uses ("Leave blank to keep the currently
    // saved password. The password is never shown or logged.").
    var imapPasswordInput by remember { mutableStateOf("") }
    var imapSaveBusy by remember { mutableStateOf(false) }
    var imapSaveStatus by remember { mutableStateOf<String?>(null) }
    val backendUsable = if (mailBackend == AppPrefs.MAIL_BACKEND_IMAP) imapPasswordSaved else accessTokenAvailable

    Scaffold(
        topBar = { WagmailTopBar(title = "Settings") },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Mail backend", style = MaterialTheme.typography.titleMedium)
            Box {
                OutlinedButton(onClick = { backendMenuOpen = true }) {
                    Text(BACKEND_LABELS[mailBackend] ?: mailBackend)
                }
                DropdownMenu(expanded = backendMenuOpen, onDismissRequest = { backendMenuOpen = false }) {
                    BACKEND_LABELS.forEach { (backend, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { onMailBackendChange(backend); backendMenuOpen = false },
                        )
                    }
                }
            }

            if (mailBackend == AppPrefs.MAIL_BACKEND_IMAP) {
                Box {
                    OutlinedButton(onClick = { providerMenuOpen = true }) {
                        Text(imapProviders.firstOrNull { it.key == imapProvider }?.label ?: imapProvider)
                    }
                    DropdownMenu(expanded = providerMenuOpen, onDismissRequest = { providerMenuOpen = false }) {
                        imapProviders.forEach { info ->
                            DropdownMenuItem(
                                text = { Text(info.label) },
                                onClick = { onImapProviderChange(info.key); providerMenuOpen = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = imapHost,
                    onValueChange = onImapHostChange,
                    label = { Text("Host") },
                    enabled = imapProvider == "custom",
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = imapPort.toString(),
                    onValueChange = { it.toIntOrNull()?.let(onImapPortChange) },
                    label = { Text("Port") },
                    enabled = imapProvider == "custom",
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = imapEmail,
                    onValueChange = onImapEmailChange,
                    label = { Text("Email address") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = imapPasswordInput,
                    onValueChange = { imapPasswordInput = it },
                    label = { Text("App password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (imapPasswordSaved) "An app password is saved for $imapEmail."
                    else "No app password saved yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Leave the password field blank to keep the currently saved password. " +
                        "The password is never shown or logged.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        enabled = !imapSaveBusy,
                        onClick = {
                            imapSaveBusy = true
                            imapSaveStatus = null
                            onSaveImapSettings(imapProvider, imapHost, imapPort, imapEmail, imapPasswordInput) { success, message ->
                                imapSaveBusy = false
                                imapSaveStatus = message
                                if (success) imapPasswordInput = ""
                            }
                        },
                    ) { Text(if (imapSaveBusy) "Connecting…" else "Save & connect") }
                    if (imapPasswordSaved) {
                        TextButton(
                            onClick = onForgetImapPassword,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("Forget saved password") }
                    }
                }
                imapSaveStatus?.let { Text(it, modifier = Modifier.fillMaxWidth()) }
            } else {
                Text("Account", style = MaterialTheme.typography.titleMedium)
                Text(connectedEmail ?: "Not connected", style = MaterialTheme.typography.bodyLarge)
                if (connectedEmail == null) {
                    Button(onClick = onReconnect) { Text("Connect") }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = onReconnect) { Text("Reconnect") }
                        TextButton(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("Disconnect") }
                    }
                }
                // Business reason IMAP exists at all: this app hasn't been
                // through Google's paid annual CASA verification, so it stays
                // in "Testing" mode — see HelpScreen for the full explanation.
                Text(
                    "Google sign-in is limited to 100 test users and expires roughly every 7 " +
                        "days while this app is in Google's \"Testing\" publishing mode. See " +
                        "Help & FAQ for details, or switch to \"Email app password (IMAP)\" above " +
                        "to avoid both limits.",
                    style = MaterialTheme.typography.bodySmall,
                )
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
            Text("WhatsApp Chat Sync to Gmail — Android (dev build)")
            TextButton(onClick = onOpenHelp) { Text("Help & FAQ") }
            TextButton(onClick = onOpenSyncLog) { Text("Sync log") }

            HorizontalDivider()

            Text("Developer tools", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = {
                    onTestConnection { result -> testResult = result }
                },
                enabled = backendUsable,
            ) {
                Text("Test connection (labels.list)")
            }
            testResult?.let { Text(it, modifier = Modifier.fillMaxWidth()) }
        }
    }
}
