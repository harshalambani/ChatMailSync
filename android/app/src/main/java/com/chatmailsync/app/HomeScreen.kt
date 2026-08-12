@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

// Mirrors extract_chat_info's prefix-stripping (src/parser.py) so the queued
// name shown here matches the display name used everywhere else, without the
// repeated "WhatsApp Chat with " boilerplate eating the row's limited width.
private const val WA_PREFIX = "whatsapp chat with "

private fun displayNameFor(filename: String): String {
    val stem = filename.substringBeforeLast('.')
    return if (stem.lowercase().startsWith(WA_PREFIX)) stem.substring(WA_PREFIX.length) else stem
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
) {
    var previewText by remember { mutableStateOf<String?>(null) }
    var chunkMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { ChatMailTopBar(title = "Chat Mail Sync", subtitle = "Private mail archive") },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Connection chip — compact single row, not a full card, since
            // it's a status line rather than a distinct feature area.
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
                        Text(
                            "Share a WhatsApp \"Export chat\" file to this app, or import one below.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    inboxFiles.forEach { (name, size) ->
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
                    OutlinedButton(onClick = onImportPick) {
                        // No count to agree with here - the picker is
                        // multi-select, so the plural is simply correct.
                        Text("Import WhatsApp exports…")
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

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Test run", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Shows what would happen — writes nothing to your mailbox",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = dryRunDefault, onCheckedChange = onDryRunDefaultChange)
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
