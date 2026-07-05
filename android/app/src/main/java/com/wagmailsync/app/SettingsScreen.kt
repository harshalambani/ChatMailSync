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

@Composable
fun SettingsScreen(
    connectedEmail: String?,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onOpenHelp: () -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    watchedFolderUri: String?,
    onChooseFolder: () -> Unit,
    autoWatchEnabled: Boolean,
    onAutoWatchChange: (Boolean) -> Unit,
    accessTokenAvailable: Boolean,
    onTestConnection: ((String) -> Unit) -> Unit,
) {
    var testResult by remember { mutableStateOf<String?>(null) }
    var themeMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { WagmailTopBar(title = "Settings") },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
            Text(
                watchedFolderUri?.let { Uri.parse(it).lastPathSegment ?: it }
                    ?: "No folder chosen",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onChooseFolder) {
                Text(if (watchedFolderUri == null) "Choose folder" else "Change folder")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-import from this folder")
                    Text(
                        "Checks every ~15 min in the background (Android's minimum interval). " +
                            "Uses a small amount of battery — leave off if you'd rather import manually.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = autoWatchEnabled,
                    onCheckedChange = onAutoWatchChange,
                    enabled = watchedFolderUri != null,
                )
            }

            HorizontalDivider()

            Text("About / Help", style = MaterialTheme.typography.titleMedium)
            Text("WhatsApp Chat Sync to Gmail — Android (dev build)")
            TextButton(onClick = onOpenHelp) { Text("Help & FAQ") }

            HorizontalDivider()

            Text("Developer tools", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = {
                    onTestConnection { result -> testResult = result }
                },
                enabled = accessTokenAvailable,
            ) {
                Text("Test Gmail connection (labels.list)")
            }
            testResult?.let { Text(it, modifier = Modifier.fillMaxWidth()) }
        }
    }
}
