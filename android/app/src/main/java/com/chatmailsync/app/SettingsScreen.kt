@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp


// Kept as a constant so every reference to the privacy page points at the
// same URL.
internal const val PRIVACY_POLICY_URL = "https://chatmailsync.ambani.tech/privacy.html"

// internal, not private: Migration's restoreSummary (Batch 7b) reuses this
// same label map for its restore-confirmation "Theme: ..." line rather than
// keeping a second copy that could drift from what this screen shows.
internal val THEME_LABELS = mapOf(
    "system" to "Match system",
    "light" to "Light",
    "dark" to "Dark",
)

// WorkManager's PeriodicWorkRequest has a hard 15-minute floor (Android
// platform-enforced, not a WorkManager default) — no shorter interval is
// achievable regardless of what's offered here.
//
// internal, not private: FirstRunScreen's step 4 and AdvancedSettingsScreen
// both offer the same interval picker and read this same table rather than
// keeping a second copy that could drift from it.
internal val WATCH_INTERVAL_LABELS = listOf(
    15L to "Every 15 min",
    30L to "Every 30 min",
    60L to "Every hour",
    180L to "Every 3 hours",
    360L to "Every 6 hours",
    720L to "Every 12 hours",
    1440L to "Once a day",
)

/** One row of the Basic or Advanced settings list. Kept as plain data (not
 * built inline in the composable) so the row set itself -- what is on each
 * screen, and that nothing is on both or missing from either -- can be
 * asserted by a plain JUnit test without standing up Compose. */
data class SettingsRowSpec(val id: String, val title: String)

// Advanced sits above Help & About (moved here from last place) -- it is
// where the everyday-vs-everything-else split (D8) actually lives, so it
// belongs beside the other navigational rows rather than after the mostly
// static Help & About block. SettingsScreen renders its navigational rows
// from this list's order (see the `forEach` below), so a reorder here is a
// reorder on screen, not just in the test fixture.
val BASIC_SETTINGS_ROWS = listOf(
    SettingsRowSpec("mail_account", "Mail account"),
    SettingsRowSpec("me", "Me"),
    SettingsRowSpec("theme", "Theme"),
    SettingsRowSpec("backup_restore", "Backup & restore"),
    SettingsRowSpec("advanced", "Advanced"),
    SettingsRowSpec("help_about", "Help"),
)

/** Every row on the Advanced screen, in drawn order. Derived from
 * [ADVANCED_SECTIONS] so the list a test reads is the list the screen draws. */
val ADVANCED_SETTINGS_ROWS: List<SettingsRowSpec> = ADVANCED_SECTIONS.flatMap { it.rows }

/**
 * The Backup & restore row's status pill: a coloured dot and a few words, in
 * the same visual language as ChatMailTopBar's ConnectionPill (a rounded
 * filled shape, the words carrying the meaning and the colour only backing
 * them up -- never the only signal, since about one man in twelve cannot
 * tell a used green from a used red).
 *
 * Unlike ConnectionPill this sits on an ordinary surface background, not
 * the navy masthead, so its colours are the theme's own container roles
 * (tertiary/error) rather than the fixed dark-scheme literals that pill
 * hardcodes for the banner -- those stay legible on the one background they
 * were chosen for and nowhere else.
 */
@Composable
private fun BackupStatusPill(info: BackupPillInfo) {
    val (container, content) = when (info.tone) {
        BackupPillTone.GOOD -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
        BackupPillTone.WARN -> AmberPillColors()
        BackupPillTone.BAD -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(content),
        )
        Text(
            info.label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * No amber role exists on this theme (see ChatMailTheme.kt's own "EVERY role
 * is assigned deliberately" note -- amber was never one of them), so this is
 * a small local pair, not a borrowed one. Values chosen the same way the
 * theme's own container/on-container pairs are: readable text-on-fill in
 * both schemes, not a system default that would drift from the rest of the
 * palette.
 */
@Composable
private fun AmberPillColors(): Pair<Color, Color> =
    if (isSystemInDarkTheme()) Color(0xFF5C4300) to Color(0xFFF7E4B8)
    else Color(0xFFF7E4B8) to Color(0xFF4A3200)



@Composable
fun SettingsScreen(
    mailAccountSummary: String,
    onOpenMailAccount: () -> Unit,
    // UX-01: one "Help" row. Help & FAQ, the privacy policy, the licences and
    // the app version live on the Help sub-screen it opens.
    onOpenHelp: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    // Read by MainActivity from AppPrefs.getLastBackupAt and re-derived
    // whenever the shared migration state moves, so the pill here reflects
    // a save or restore done on BackupRestoreScreen without this screen
    // reaching into AppPrefs itself -- see the "settings" composable in
    // MainActivity.kt.
    lastBackupAt: Long,
    selfSenderSource: String? = null,
    selfSenderName: String? = null,
    onOpenMe: () -> Unit = {},
) {
    var themeDialogOpen by remember { mutableStateOf(false) }
    // Computed here, not inside the row loop below, so the masthead's own Me
    // row and the Me list row both read the same value.
    val meDisplay = selfSenderDisplay(selfSenderSource, selfSenderName)

    if (themeDialogOpen) {
        OptionDialog(
            title = "Theme",
            options = THEME_LABELS.toList(),
            current = themeMode,
            onCommit = onThemeModeChange,
            onDismiss = { themeDialogOpen = false },
        )
    }

    Scaffold(
        // Zero, deliberately: MainActivity's Scaffold has already padded
        // this NavHost for the status bar and the bottom bars, and insets
        // are not consumed by being turned into padding -- so a screen
        // Scaffold left on the default reserves the same strips a second
        // time.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // Unchanged from before UX-01: masthead badge, title, pill and
            // the Me row in the band.
            ChatMailTopBar(
                title = "Settings",
                showMe = true,
                meLabel = meDisplay.label,
                meColor = meDisplay.color,
                meDescription = selfSenderContentDescription(selfSenderSource, selfSenderName),
                onMeClick = onOpenMe,
            )
        },
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .fadingEdges(scrollState, MaterialTheme.colorScheme.background)
                .verticalScrollbar(scrollState)
                .verticalScroll(scrollState)
                .padding(vertical = 8.dp),
        ) {
            // Rendered in BASIC_SETTINGS_ROWS' own order (SettingsRowsTest), so
            // a reorder of that list is a reorder on screen.
            BASIC_SETTINGS_ROWS.forEachIndexed { index, row ->
                val kind = BASIC_SUBTITLE_KINDS.getValue(row.id)
                when (row.id) {
                    "mail_account" -> SettingsListRow(
                        icon = Icons.Filled.Email,
                        title = "Mail account",
                        subtitle = mailAccountSummary,
                        kind = kind,
                        onClick = onOpenMailAccount,
                    )

                    "me" -> SettingsListRow(
                        icon = Icons.Filled.Person,
                        title = "Your messages",
                        subtitle = meDisplay.label,
                        kind = kind,
                        onClick = onOpenMe,
                        contentDescriptionOverride =
                            selfSenderContentDescription(selfSenderSource, selfSenderName),
                    )

                    "theme" -> SettingsListRow(
                        icon = Icons.Filled.Settings,
                        title = "Theme",
                        subtitle = THEME_LABELS[themeMode] ?: themeMode,
                        kind = kind,
                        onClick = { themeDialogOpen = true },
                    )

                    "backup_restore" -> SettingsListRow(
                        icon = Icons.Filled.Share,
                        title = "Backup & restore",
                        subtitle = "Save your sync history, or restore it on a new phone",
                        kind = kind,
                        onClick = onOpenBackupRestore,
                        titleAccessory = { BackupStatusPill(Migration.backupPillState(lastBackupAt)) },
                    )

                    "advanced" -> SettingsListRow(
                        icon = Icons.Filled.Build,
                        title = "Advanced",
                        subtitle = ADVANCED_ROW_SUBTITLE,
                        kind = kind,
                        onClick = onOpenAdvanced,
                    )

                    "help_about" -> SettingsListRow(
                        icon = Icons.Filled.Info,
                        title = "Help",
                        subtitle = HELP_ROW_SUBTITLE,
                        kind = kind,
                        onClick = onOpenHelp,
                    )
                }
                if (index != BASIC_SETTINGS_ROWS.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                }
            }
        }
    }
}
