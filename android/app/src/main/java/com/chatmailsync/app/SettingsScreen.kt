@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

val BASIC_SETTINGS_ROWS = listOf(
    SettingsRowSpec("mail_account", "Mail account"),
    SettingsRowSpec("me", "Me"),
    SettingsRowSpec("theme", "Theme"),
    SettingsRowSpec("backup_restore", "Backup & restore"),
    SettingsRowSpec("help_about", "Help & About"),
    SettingsRowSpec("advanced", "Advanced"),
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

            // Above every other setting here, because it is the only one the
            // app answers on its own. An export does not mark your own
            // messages -- it writes your profile name exactly as it writes
            // everybody else's -- so the app works out which name is yours,
            // and that decides which side of the conversation every bubble is
            // drawn on. Getting it wrong does not fail loudly; it produces a
            // perfectly readable archive of the wrong shape. So the answer is
            // stated here, its colour carrying which of the three states it
            // is in, one tap away from the detail and the ways to change it.
            val meDisplay = selfSenderDisplay(selfSenderSource, selfSenderName)
            OutlinedButton(
                onClick = onOpenMe,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription =
                            selfSenderContentDescription(selfSenderSource, selfSenderName)
                    },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Your messages", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            meDisplay.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = meDisplay.color,
                        )
                    }
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null)
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

            Text("Help & About", style = MaterialTheme.typography.titleMedium)
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
            // The policy is carried in the app now, not linked out to. It was
            // a browser link, which is where Indus Appstore put the app on
            // hold: a policy that needs a second app and a live connection
            // before it can be read is not really inside the app at all. This
            // goes to a screen that renders offline, with the hosted copy
            // offered from there as a secondary.
            TextButton(onClick = onOpenPrivacy) { Text("Privacy policy") }

            HorizontalDivider()

            // Everything below this row still exists -- nothing was removed,
            // only moved one tap deeper -- but none of it is an everyday
            // decision the way Mail account, Me and Theme are, so it no
            // longer competes with them for space on the first screen. See
            // AdvancedSettingsScreen.
            OutlinedButton(
                onClick = onOpenAdvanced,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Advanced", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Automatic import, cut-off date, test run and more",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}
