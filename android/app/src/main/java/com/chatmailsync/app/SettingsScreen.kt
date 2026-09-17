@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp


// Kept as a constant so every reference to the privacy page points at the
// same URL.
internal const val PRIVACY_POLICY_URL = "https://chatmailsync.ambani.tech/privacy.html"

private val THEME_LABELS = mapOf(
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
    SettingsRowSpec("help_about", "Help & About"),
)

val ADVANCED_SETTINGS_ROWS = listOf(
    SettingsRowSpec("watched_folder", "Watched folder"),
    SettingsRowSpec("auto_import", "Auto-import"),
    SettingsRowSpec("watch_interval", "Check interval"),
    SettingsRowSpec("after_import", "After import"),
    SettingsRowSpec("cutoff_date", "Cutoff date"),
    SettingsRowSpec("test_run", "Test run"),
    SettingsRowSpec("sync_log", "Sync log"),
    SettingsRowSpec("chunk_size", "Chunk size"),
    SettingsRowSpec("test_connection", "Test connection"),
)

/**
 * A full-row navigational entry: title, subtitle, and a trailing chevron --
 * the shape "Mail account", "Your messages" (Me) and "Advanced" already
 * shared informally, and Mail account was quietly missing the chevron the
 * other two had (item 5). Pulled out once so all three -- and any row added
 * here later -- get the same emphasis: a full-strength onSurface title and
 * chevron rather than a dimmer inherited tint, and an explicit outline so
 * the row reads as a bounded tappable card at normal emphasis (item 6). The
 * subtitle stays onSurfaceVariant, which is still readable at this weight.
 */
@Composable
private fun SettingsNavRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    contentDescriptionOverride: String? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .let {
                if (contentDescriptionOverride != null) {
                    it.semantics { contentDescription = contentDescriptionOverride }
                } else {
                    it
                }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun SettingsScreen(
    mailAccountSummary: String,
    onOpenMailAccount: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenAdvanced: () -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    onSaveBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    migrationBusy: Boolean,
    migrationStatus: String?,
    selfSenderSource: String? = null,
    selfSenderName: String? = null,
    onOpenMe: () -> Unit = {},
) {
    val context = LocalContext.current
    var themeMenuOpen by remember { mutableStateOf(false) }
    // Computed here, not inside the row loop below, so the masthead's own Me
    // row (added for item 2 -- see the topBar block) and the Mail account/Me
    // nav rows in the list both read the same value.
    val meDisplay = selfSenderDisplay(selfSenderSource, selfSenderName)

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
            // item 2: Home and Chats already show the masthead's Me row
            // (showMe = true), which is what made their band 112dp tall
            // against Settings' plain 88dp band -- the one real structural
            // difference between the three, since all three already share
            // this one ChatMailTopBar component for height, title style,
            // padding and the connection pill's placement. Settings is the
            // only one of the three with nowhere else on screen to reach
            // Me from other than its own nav row, but the masthead row is
            // one tap closer and matches the other two tabs, so it gets it
            // too rather than being the odd one out. Nothing existing is
            // dropped: Settings had no top-bar actions to keep, and the Me
            // nav row lower on the screen stays exactly where it was.
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
                .padding(14.dp)
                .fadingEdges(scrollState, MaterialTheme.colorScheme.background)
                .verticalScrollbar(scrollState)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Rendered in BASIC_SETTINGS_ROWS' own order (item 1 / SettingsRowsTest),
            // so a reorder of that list is a reorder on screen, not just in a
            // test fixture that could quietly drift from what actually renders.
            // Theme, Backup & restore and Help & About stay their own bespoke
            // bodies (a dropdown, two buttons, a couple of links) rather than
            // being forced into the nav-row shape -- but their position on
            // screen still comes from this same list.
            BASIC_SETTINGS_ROWS.forEachIndexed { index, row ->
                when (row.id) {
                    "mail_account" -> SettingsNavRow(
                        title = "Mail account",
                        subtitle = mailAccountSummary,
                        onClick = onOpenMailAccount,
                    )

                    "me" -> SettingsNavRow(
                        title = "Your messages",
                        subtitle = meDisplay.label,
                        onClick = onOpenMe,
                        contentDescriptionOverride =
                            selfSenderContentDescription(selfSenderSource, selfSenderName),
                        subtitleColor = meDisplay.color,
                    )

                    "theme" -> {
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
                    }

                    "backup_restore" -> {
                        // Worth being explicit about what this is for, because
                        // "backup" in an archiving app invites the wrong
                        // reading: the mailbox is the archive, and it is
                        // already safe on a mail server. What is only on this
                        // phone is the record of which messages have already
                        // been sent. Lose that and nothing is lost --
                        // everything is sent again, into a mailbox that has no
                        // way to tell the copies apart.
                        //
                        // Headed "Move to a new phone" until v1.17.0, which
                        // hid it from everyone who was not moving: the same
                        // file is what gets you back after a reset, a
                        // reinstall or Clear data, and those happen to people
                        // who never buy a phone.
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
                        // Re-read whenever the migration state moves, which is
                        // what a save finishing looks like from here -- a
                        // backup nobody can date is a backup nobody trusts,
                        // and "I think I did one" is exactly the belief that
                        // costs a mailbox its second copy of everything.
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
                        // In place, under the buttons -- not a dialog.
                        // Everything this can say is an outcome to read, and
                        // none of it needs a decision, so a box demanding to
                        // be dismissed would only add a tap.
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
                    }

                    "advanced" -> SettingsNavRow(
                        title = "Advanced",
                        subtitle = "Automatic import, cut-off date, test run and more",
                        onClick = onOpenAdvanced,
                    )

                    "help_about" -> {
                        Text("Help & About", style = MaterialTheme.typography.titleMedium)
                        // Read from BuildConfig, which gradle generates from
                        // versionName / versionCode, so this cannot drift from
                        // the APK. It used to be the hardcoded string "Chat
                        // Mail Sync — Android (dev build)", which a
                        // release-signed 1.0.1 went on displaying -- worse
                        // than showing nothing, because it was confidently
                        // wrong.
                        //
                        // versionCode is shown alongside the name because it
                        // is the number `adb shell dumpsys package` reports
                        // and the one the store orders by, so it is what
                        // actually answers "am I on the current build?".
                        Text(
                            "Chat Mail Sync ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
                                if (BuildConfig.DEBUG) " — debug build" else "",
                        )
                        TextButton(onClick = onOpenHelp) { Text("Help & FAQ") }
                        // The policy is carried in the app now, not linked out
                        // to. It was a browser link, which is where Indus
                        // Appstore put the app on hold: a policy that needs a
                        // second app and a live connection before it can be
                        // read is not really inside the app at all. This goes
                        // to a screen that renders offline, with the hosted
                        // copy offered from there as a secondary.
                        TextButton(onClick = onOpenPrivacy) { Text("Privacy policy") }
                    }
                }
                if (index != BASIC_SETTINGS_ROWS.lastIndex) HorizontalDivider()
            }
        }
    }
}
