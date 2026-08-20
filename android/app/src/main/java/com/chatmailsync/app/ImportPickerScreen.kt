@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// WhatsApp writes exports as .txt, or .zip when media is included. Anything
// else in the folder belongs to somebody else, and a Downloads folder full of
// PDFs would bury the four files this screen exists to show. The filter is
// escapable rather than absolute -- "Show everything in this folder" -- because
// a filter that silently hides the file you came for is worse than the noise.
private val EXPORT_EXTENSIONS = setOf("txt", "zip")

private val FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy")

/** One candidate file in the granted folder, already named the way a person reads it. */
data class ExportCandidate(
    val uri: Uri,
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val alreadyQueued: Boolean,
)

/**
 * What we can say about the granted folder right now.
 *
 * [Unreachable] is a state, not an error path bolted on later: a folder can be
 * renamed, moved or deleted, and the grant itself disappears when app data is
 * cleared. In every one of those cases `listFiles()` simply returns nothing.
 * Rendering that as an empty list tells the user they have no exports, which is
 * both wrong and unactionable -- so the empty case and the broken case have to
 * be told apart before either is drawn.
 */
sealed interface FolderListing {
    object Loading : FolderListing
    object NoFolder : FolderListing
    object Unreachable : FolderListing
    data class Ready(val items: List<ExportCandidate>, val hiddenByFilter: Int) : FolderListing
}

// internal, not private: the sync-queue screen lists the same files with the
// same sizes, and two copies of this rounding would eventually disagree by a
// tenth of a megabyte on the same row.
internal fun humanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes bytes"
}

private fun humanDate(millis: Long): String =
    if (millis <= 0L) {
        ""
    } else {
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(FILE_DATE_FORMAT)
    }

/**
 * Read the granted tree.
 *
 * Deliberately the same two calls `WatchFolderWorker` uses -- `fromTreeUri`
 * then `listFiles()` -- so the list this screen shows and the set the watcher
 * would import cannot drift apart. `listFiles()` sees immediate children only,
 * which is also what keeps a "move to synced/" policy from showing its own
 * destination back to you.
 */
private suspend fun listExports(
    context: Context,
    folderUriString: String?,
    queuedNames: Set<String>,
    showAllFiles: Boolean,
): FolderListing = withContext(Dispatchers.IO) {
    if (folderUriString.isNullOrBlank()) return@withContext FolderListing.NoFolder
    val folder = try {
        DocumentFile.fromTreeUri(context, Uri.parse(folderUriString))
    } catch (_: Exception) {
        null
    }
    if (folder == null || !folder.exists() || !folder.canRead()) {
        return@withContext FolderListing.Unreachable
    }

    val children = try {
        folder.listFiles()
    } catch (_: Exception) {
        return@withContext FolderListing.Unreachable
    }

    var hidden = 0
    val items = mutableListOf<ExportCandidate>()
    for (doc in children) {
        if (!doc.isFile) continue
        val name = doc.name ?: continue
        val looksLikeExport = name.substringAfterLast('.', "").lowercase() in EXPORT_EXTENSIONS
        if (!looksLikeExport && !showAllFiles) {
            hidden++
            continue
        }
        items.add(
            ExportCandidate(
                uri = doc.uri,
                fileName = name,
                displayName = displayNameFor(name),
                sizeBytes = doc.length(),
                lastModified = doc.lastModified(),
                alreadyQueued = name in queuedNames,
            ),
        )
    }
    // Newest first: the file someone came here to import is almost always the
    // one WhatsApp wrote a minute ago.
    items.sortByDescending { it.lastModified }
    FolderListing.Ready(items, hidden)
}

/**
 * Our own list of the exports sitting in the watched folder.
 *
 * Exists because the system picker cannot show what these files are. Every
 * WhatsApp export is named "WhatsApp Chat with <name>.txt", so a picker that
 * elides by width shows a column of identical "WhatsApp Chat with Bija..."
 * rows -- it truncates away exactly the part that tells one from another, and
 * grid view is worse rather than better. We already hold a persisted grant on
 * that folder for the auto-import watcher, so listing it ourselves costs no new
 * permission and, in the steady state, fewer taps than the system picker.
 *
 * The system picker is still reachable one clearly-secondary button down, for
 * files that live outside the granted folder. The two are never offered at the
 * same weight, and never as two steps of one flow.
 */
@Composable
fun ImportPickerScreen(
    onBack: () -> Unit,
    watchedFolderUri: String?,
    queuedNames: Set<String>,
    onChooseFolder: () -> Unit,
    onPickFromAnywhere: () -> Unit,
    onImport: (List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    var showAllFiles by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf<FolderListing>(FolderListing.Loading) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    // Bumped by the Refresh action. The listing is a snapshot of a folder
    // that other apps write into -- WhatsApp drops a new export in while
    // this screen is open and nothing tells us -- and DocumentFile gives no
    // change notification we could observe instead. So the reload is an
    // explicit key rather than a poll: cheap, predictable, and it never
    // moves the list under a finger that is mid-tap.
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(watchedFolderUri, showAllFiles, queuedNames, refreshTick) {
        listing = FolderListing.Loading
        listing = listExports(context, watchedFolderUri, queuedNames, showAllFiles)
    }

    Scaffold(
        // Zero, deliberately: MainActivity's Scaffold has already padded
        // this NavHost for the status bar and the bottom bars, and insets
        // are not consumed by being turned into padding -- so a screen
        // Scaffold left on the default reserves the same strips a second
        // time. That silently cost about a row and a half of list height
        // on every screen, which is how two exports ended up below the
        // fold on the import picker.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatMailTopBar(
                title = "Import exports",
                backLabel = "Home",
                onBack = onBack,
                actions = {
                    // In the bar rather than beside the two buttons below,
                    // because that strip is deliberately one row tall and a
                    // third control there either wraps it or shortens a
                    // label that is already doing work. Top-right is also
                    // where a refresh lives on every other Android app.
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh the list")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.weight(1f, fill = true).fillMaxWidth()) {
                when (val state = listing) {
                    is FolderListing.Loading -> LoadingBlock()
                    is FolderListing.NoFolder -> NoFolderBlock(onChooseFolder)
                    is FolderListing.Unreachable -> UnreachableBlock(onChooseFolder)
                    is FolderListing.Ready -> ReadyBlock(
                        state = state,
                        selected = selected,
                        showAllFiles = showAllFiles,
                        onToggle = { name ->
                            selected = if (name in selected) selected - name else selected + name
                        },
                        onShowAllFiles = { showAllFiles = true },
                    )
                }
            }

            HorizontalDivider()
            // Tight, because every dp this strip takes is a dp the list does
            // not have: the two secondary actions sit side by side on one row
            // rather than stacked full-width, which is a whole export row's
            // worth of height back.
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val ready = listing as? FolderListing.Ready
                if (ready != null) {
                    Button(
                        onClick = {
                            val chosen = ready.items
                                .filter { it.fileName in selected }
                                .map { it.uri }
                            if (chosen.isNotEmpty()) onImport(chosen)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selected.isNotEmpty(),
                    ) {
                        Text(
                            if (selected.isEmpty()) {
                                "Import"
                            } else {
                                "Import ${plural(selected.size, "file")}"
                            },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (watchedFolderUri != null) {
                        TextButton(onClick = onChooseFolder) {
                            Text("Change folder")
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }
                    // Always available, always visibly secondary: the granted
                    // folder is where exports normally land, but a file shared
                    // in from somewhere else needs a way through that is not
                    // "grant us a second folder".
                    TextButton(onClick = onPickFromAnywhere) {
                        Text("Pick a file from anywhere…")
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun NoFolderBlock(onChooseFolder: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Choose your exports folder", style = MaterialTheme.typography.titleMedium)
        Text(
            "Point the app at the folder WhatsApp saves exports into — usually Downloads. " +
                "You do this once, and after that your chats are listed here by name.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onChooseFolder, modifier = Modifier.fillMaxWidth()) {
            Text("Choose folder")
        }
    }
}

@Composable
private fun UnreachableBlock(onChooseFolder: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Can't reach that folder any more", style = MaterialTheme.typography.titleMedium)
        Text(
            "It may have been renamed, moved or deleted — or the app's permission to read it " +
                "was cleared. Nothing you have already synced is affected.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onChooseFolder, modifier = Modifier.fillMaxWidth()) {
            Text("Choose folder again")
        }
    }
}

@Composable
private fun ReadyBlock(
    state: FolderListing.Ready,
    selected: Set<String>,
    showAllFiles: Boolean,
    onToggle: (String) -> Unit,
    onShowAllFiles: () -> Unit,
) {
    if (state.items.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("No exports in this folder", style = MaterialTheme.typography.titleMedium)
            Text(
                "In WhatsApp, open a chat and tap ⋮ → More → Export chat, then save it here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.hiddenByFilter > 0 && !showAllFiles) {
                Text(
                    "${plural(state.hiddenByFilter, "file")} here " +
                        (if (state.hiddenByFilter == 1) "is" else "are") +
                        " hidden — not a .txt or .zip.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onShowAllFiles) {
                    Text("Show everything in this folder")
                }
            }
        }
        return
    }

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        // surface, not background: these rows paint their own surface, and
        // a scrim in the wrong ground reads as a bruise across the last row.
        modifier = Modifier
            .fillMaxSize()
            .fadingEdges(listState, MaterialTheme.colorScheme.surface)
            .verticalScrollbar(listState),
    ) {
        items(state.items, key = { it.fileName }) { item ->
            CandidateRow(
                item = item,
                checked = item.fileName in selected,
                onToggle = { onToggle(item.fileName) },
            )
            HorizontalDivider()
        }
        if (state.hiddenByFilter > 0 && !showAllFiles) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        "${plural(state.hiddenByFilter, "other file")} hidden — not a .txt or .zip.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onShowAllFiles) {
                        Text("Show everything in this folder")
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun CandidateRow(item: ExportCandidate, checked: Boolean, onToggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !item.alreadyQueued, onClick = onToggle)
                // A queued row is not a choice, and Material's disabled
                // checkbox alone did not say so -- on the light theme it still
                // read as an ordinary empty box waiting to be ticked. Dimming
                // the whole row, checkbox and text together, is the difference
                // between "you missed this one" and "this one is already in
                // hand".
                .alpha(if (item.alreadyQueued) 0.45f else 1f)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = !item.alreadyQueued,
            )
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                // The chat name, with WhatsApp's identical prefix stripped --
                // the whole reason this screen exists instead of the system
                // picker.
                Text(
                    item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                val date = humanDate(item.lastModified)
                Text(
                    listOfNotNull(
                        humanSize(item.sizeBytes),
                        date.takeIf { it.isNotEmpty() },
                        "already waiting to sync".takeIf { item.alreadyQueued },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
