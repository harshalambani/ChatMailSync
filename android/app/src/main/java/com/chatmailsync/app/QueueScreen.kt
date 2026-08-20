@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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

/**
 * The whole sync queue, on its own screen.
 *
 * Home shows the first few queued files and stops. It has to: the queue sits
 * above "Sync now" in a scrolling page, so a queue long enough to be worth
 * managing is exactly a queue long enough to push the button that acts on it
 * off the bottom of the screen. Thirty exports is around three screen-heights
 * of rows.
 *
 * Nesting a scrolling box inside Home's own scroll was the other option and is
 * a worse one twice over: an unbounded lazy list inside a vertical scroll is a
 * hard Compose error, and a fixed-height one is a scroll trap -- a drag that
 * starts four pixels outside the box moves a different thing than one that
 * starts inside it, and two scrollbars end up on screen disagreeing about how
 * much more there is.
 *
 * So the list moves here, where it is the only thing on the screen and can
 * scroll as far as it likes. That also buys the room for the thing the Home
 * card could never hold: removing twenty-two of thirty queued chats one X at a
 * time is not queue management, so this screen selects in bulk.
 */
@Composable
fun QueueScreen(
    files: List<Pair<String, Long>>,
    onPreview: (String) -> String,
    onRemove: (String) -> Unit,
    onImportPick: () -> Unit,
    onBack: () -> Unit,
) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    var previewText by remember { mutableStateOf<String?>(null) }

    // Names, not indices: a removal renumbers everything after it, and a
    // selection held by position would silently shift onto its neighbour.
    val names = files.map { it.first }
    val liveSelection = selected.intersect(names.toSet())
    val totalBytes = files.sumOf { it.second }

    Scaffold(
        // Zero for the same reason every other screen Scaffold is zero:
        // MainActivity has already paid for the system bars.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatMailTopBar(
                title = "Sync queue",
                backLabel = "Home",
                onBack = onBack,
            )
        },
    ) { padding ->
        val listState = rememberLazyListState()
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // The size is here rather than only per row because it is
                    // the number that answers "is this going to take a while?".
                    "${plural(files.size, "file")} - ${humanSize(totalBytes)}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (files.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            selected = if (liveSelection.size == files.size) emptySet() else names.toSet()
                        },
                    ) {
                        Text(if (liveSelection.size == files.size) "Clear" else "Select all")
                    }
                }
            }

            // In the page, not over it: a preview that covers the list it was
            // opened from makes you dismiss it to find out which row you were
            // looking at. Capped, because a chat preview can run long and the
            // list is still the point of this screen.
            previewText?.let { text ->
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Preview",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            IconButton(onClick = { previewText = null }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close the preview")
                            }
                        }
                        val previewScroll = rememberScrollState()
                        Text(
                            text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .verticalScrollbar(previewScroll)
                                .verticalScroll(previewScroll),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            HorizontalDivider()

            if (files.isEmpty()) {
                Text(
                    "The queue is empty. Imported exports appear here until they are synced.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .fadingEdges(listState, MaterialTheme.colorScheme.background)
                    .verticalScrollbar(listState),
            ) {
                items(files, key = { it.first }) { (name, size) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = name in liveSelection,
                            onCheckedChange = { on ->
                                selected = if (on) selected + name else selected - name
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                displayNameFor(name),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Text(
                                humanSize(size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { previewText = onPreview(name) }) {
                            Text("Preview")
                        }
                    }
                }
            }

            HorizontalDivider()

            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (liveSelection.isNotEmpty()) {
                    Button(
                        onClick = {
                            // Snapshot first: onRemove reloads the list under
                            // us, and iterating the live set while it changes
                            // would drop every other file.
                            val doomed = liveSelection.toList()
                            selected = emptySet()
                            previewText = null
                            doomed.forEach(onRemove)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Text("Remove ${liveSelection.size} from queue")
                    }
                    Text(
                        // Says what removal does and does not do. Nothing has
                        // been sent yet, and the export in the folder it came
                        // from is untouched -- without that, "remove" reads as
                        // if it might be deleting the user's own file.
                        "Removes them from this queue only. The exported files where they came " +
                            "from are not touched, and nothing already synced is affected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onImportPick, modifier = Modifier.fillMaxWidth()) {
                    Text("Add more exports…")
                }
            }
        }
    }
}
