package com.chatmailsync.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// UX-01: shared furniture for the Settings, Advanced and Help screens, which
// are now lists of rows (icon, title, one-line subtitle) rather than a stack
// of bordered buttons and inline controls. Everything that decides WHAT is
// shown (the sections, which colour a subtitle gets, what a dialog commits)
// is plain data or a pure function so a JUnit test can pin it without Compose.

/**
 * The colour key for a row's subtitle. Resolved to a theme role at draw time,
 * never to a hex literal, so both colour schemes stay right.
 *
 *  - [VALUE]: the row's CURRENT VALUE (a mail address, "Match system", "Every
 *    hour"). colorScheme.primary, medium weight.
 *  - [CONTENTS]: lists WHAT IS INSIDE a sub-screen (the Advanced and Help
 *    rows). colorScheme.tertiary.
 *  - [PLAIN]: a description. colorScheme.onSurfaceVariant.
 */
enum class SubtitleKind { VALUE, CONTENTS, PLAIN }

/** A titled group of rows on the Advanced screen. */
data class SettingsSectionSpec(val id: String, val header: String, val rows: List<SettingsRowSpec>)

/** The Advanced screen, in the order it is drawn. [ADVANCED_SETTINGS_ROWS] is
 * derived from this, so the test fixture cannot drift from the screen. */
val ADVANCED_SECTIONS = listOf(
    SettingsSectionSpec(
        "automatic_import",
        "Automatic import",
        listOf(
            SettingsRowSpec("watched_folder", "Watched folder"),
            SettingsRowSpec("auto_import", "Auto-import"),
            SettingsRowSpec("watch_interval", "Check interval"),
            SettingsRowSpec("after_import", "After import"),
            SettingsRowSpec("check_now", "Check and sync"),
        ),
    ),
    SettingsSectionSpec(
        "what_gets_sent",
        "What gets sent",
        listOf(
            SettingsRowSpec("cutoff_date", "Cutoff date"),
            SettingsRowSpec("chunk_size", "Chunk size"),
        ),
    ),
    SettingsSectionSpec(
        "testing_tools",
        "Testing & tools",
        listOf(
            SettingsRowSpec("test_run", "Test run"),
            SettingsRowSpec("test_connection", "Test connection"),
            SettingsRowSpec("sync_log", "Sync log"),
            SettingsRowSpec("run_setup_again", "Run setup again"),
        ),
    ),
)

/** Which colour each Settings row's subtitle takes (see [SubtitleKind]). */
val BASIC_SUBTITLE_KINDS: Map<String, SubtitleKind> = mapOf(
    "mail_account" to SubtitleKind.VALUE,
    "me" to SubtitleKind.VALUE,
    "theme" to SubtitleKind.VALUE,
    "backup_restore" to SubtitleKind.PLAIN,
    "advanced" to SubtitleKind.CONTENTS,
    "help_about" to SubtitleKind.CONTENTS,
)

/** The subtitles that list what is inside a sub-screen. */
internal const val ADVANCED_ROW_SUBTITLE = "Automatic import, cut-off date, testing & tools"
internal const val HELP_ROW_SUBTITLE = "Help & FAQ, privacy policy, licences, app version"

/** What the cutoff row shows when no cutoff is set. */
internal const val NO_CUTOFF_SUBTITLE = "None: everything is sent"

/** The Help sub-screen's rows, in order. */
val HELP_ROWS = listOf(
    SettingsRowSpec("help_faq", "Help & FAQ"),
    SettingsRowSpec("privacy_policy", "Privacy policy"),
    SettingsRowSpec("licences", "Open-source licences"),
    SettingsRowSpec("app_version", "App version"),
)

/** The routes a user can reach from the Settings screen, directly or through
 * the Help sub-screen. Every one must resolve to a real back label. */
val SETTINGS_REACHABLE_ROUTES = listOf(
    "mailAccount",
    "me",
    "backupRestore",
    "advancedSettings",
    "helpAbout",
    "help",
    "privacy",
    "licenses",
    "syncLog",
)

/**
 * What a dialog stores. A dialog holds a pending pick; only OK commits it,
 * Cancel (or a tap outside) hands back [current] untouched. Pulled out so
 * "Cancel leaves the stored value unchanged" is a tested fact, not a habit.
 */
internal fun <T> committedChoice(current: T, pending: T, confirmed: Boolean): T =
    if (confirmed) pending else current

/**
 * The cutoff dialog's result. Only a confirmed, readable entry changes the
 * value; blank is readable and means "no cutoff". Cancel, or an entry the app
 * cannot compare, leaves [current] as it was.
 */
internal fun committedCutoff(current: String, typed: String, confirmed: Boolean): String =
    if (confirmed && CutoffDate.isReadable(typed)) typed.trim() else current

/** The cutoff row's subtitle. */
internal fun cutoffSubtitle(cutoffDate: String): String =
    if (cutoffDate.isBlank()) NO_CUTOFF_SUBTITLE else cutoffDate.trim()

/** The label after "Back" for the welcome step: a fresh install shows none. */
internal fun welcomeBackLabel(cameFrom: String?): String? =
    if (cameFrom == null) null else backLabelForRoute(cameFrom)

@Composable
internal fun subtitleColor(kind: SubtitleKind) = when (kind) {
    SubtitleKind.VALUE -> MaterialTheme.colorScheme.primary
    SubtitleKind.CONTENTS -> MaterialTheme.colorScheme.tertiary
    SubtitleKind.PLAIN -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** A section header: small, uppercase, letter-spaced, onSurface (not primary). */
@Composable
internal fun SettingsSectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        letterSpacing = 1.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/**
 * One list row: icon, title, a one-line subtitle, and either a chevron (opens
 * something), a [trailing] control (a switch), or nothing.
 */
@Composable
internal fun SettingsListRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    kind: SubtitleKind,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    chevron: Boolean = onClick != null,
    contentDescriptionOverride: String? = null,
    titleAccessory: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.5f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .let { if (onClick != null && enabled) it.clickable(role = Role.Button, onClick = onClick) else it }
            .let {
                if (contentDescriptionOverride != null) {
                    it.semantics { contentDescription = contentDescriptionOverride }
                } else {
                    it
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                titleAccessory?.let { Box(modifier = Modifier.padding(start = 8.dp)) { it() } }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor(kind).copy(alpha = alpha),
                fontWeight = if (kind == SubtitleKind.VALUE) FontWeight.Medium else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
        if (chevron) {
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
    }
}

/**
 * A radio-button dialog over a fixed list of options. Picking a radio only
 * changes the pending choice; OK commits via [committedChoice], Cancel (and a
 * tap outside) commits nothing.
 */
@Composable
internal fun <T> OptionDialog(
    title: String,
    options: List<Pair<T, String>>,
    current: T,
    onCommit: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    var pending by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                options.forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = pending == value,
                                onClick = { pending = value },
                                role = Role.RadioButton,
                            ),
                    ) {
                        RadioButton(selected = pending == value, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = committedChoice(current, pending, confirmed = true)
                if (result != current) onCommit(result)
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The cutoff date box, in a dialog. OK is off while the text is unreadable;
 * Cancel leaves the stored date as it was. Blank + OK clears the cutoff. */
@Composable
internal fun CutoffDialog(
    current: String,
    onCommit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf(current) }
    val readable = CutoffDate.isReadable(typed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cutoff date") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // A floor, never a window: "do not send me anything from before
                // this". There is no matching "to" field on purpose.
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    isError = !readable,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!readable) {
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
            }
        },
        confirmButton = {
            TextButton(
                enabled = readable,
                onClick = {
                    val result = committedCutoff(current, typed, confirmed = true)
                    if (result != current) onCommit(result)
                    onDismiss()
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
