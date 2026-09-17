@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Backup & restore, on its own screen rather than a bespoke body inline in
 * Settings' row list (batch 7). Everything here is exactly what that inline
 * body held -- explanation, the two buttons, the dated status line, the
 * in-place outcome, the password disclaimer -- moved wholesale; save/restore
 * behaviour itself is untouched, still Migration.exportTo/importFrom via the
 * launchers MainActivity owns (this screen only triggers them).
 *
 * [onBack]/[backLabel] follow the same pattern as every other pushed screen
 * (see backLabelForRoute in MainActivity.kt): the caller reads
 * `navController.previousBackStackEntry` and hands in the label for wherever
 * that actually is, rather than this screen assuming it was always reached
 * from Settings -- it is also reachable from Home's stale-backup banner.
 */
@Composable
fun BackupRestoreScreen(
    onBack: () -> Unit,
    backLabel: String,
    onSaveBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    migrationBusy: Boolean,
    migrationStatus: String?,
    // Batch 7b: "and some confirmation - that what all got restored" -- a
    // successful restore's one-line [migrationStatus] gets a short list of
    // exactly what came back (Migration.restoreSummary) directly under it,
    // still inline, never a dialog/pop-up/toast. Empty for every non-restore
    // state and for a failed/already-imported restore alike, so nothing
    // extra draws for those without this screen needing to know why.
    migrationSuccess: Boolean? = null,
    migrationRestoredLines: List<String> = emptyList(),
    migrationNotRestoredLines: List<String> = emptyList(),
) {
    val context = LocalContext.current
    Scaffold(
        // Zero, deliberately -- see SettingsScreen's own Scaffold for why:
        // MainActivity's Scaffold already pays for the status-bar inset.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatMailTopBar(
                title = "Backup & restore",
                backLabel = backLabel,
                onBack = onBack,
            )
        },
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .fadingEdges(scrollState, MaterialTheme.colorScheme.background)
                .verticalScrollbar(scrollState)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Worth being explicit about what this is for, because "backup"
            // in an archiving app invites the wrong reading: the mailbox is
            // the archive, and it is already safe on a mail server. What is
            // only on this phone is the record of which messages have
            // already been sent. Lose that and nothing is lost -- everything
            // is sent again, into a mailbox that has no way to tell the
            // copies apart.
            Text(
                "Saves what this phone knows about what it has already sent. Keep one, " +
                    "and a reset, a reinstall or another device carries on from here " +
                    "instead of mailing everything a second time. Your chats are already " +
                    "safe in your mailbox — this is not a copy of them.",
                style = MaterialTheme.typography.bodyMedium,
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
            // Re-read whenever the migration state moves, which is what a
            // save finishing looks like from here -- a backup nobody can
            // date is a backup nobody trusts, and "I think I did one" is
            // exactly the belief that costs a mailbox its second copy of
            // everything.
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
            // In place, under the buttons -- not a dialog. Everything this
            // can say is an outcome to read, and none of it needs a
            // decision, so a box demanding to be dismissed would only add a
            // tap.
            migrationStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            // Only ever non-empty for a successful restore (see
            // Migration.RestoreOutcome) -- an already-imported or failed
            // attempt leaves both lists empty, so nothing draws here beyond
            // the one-line message above, per batch 7b's "no list, just the
            // existing message."
            if (!migrationBusy && restoreOutcomeIsSuccess(migrationSuccess)) {
                if (migrationRestoredLines.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        migrationRestoredLines.forEach { line ->
                            Text(
                                "• $line",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (migrationNotRestoredLines.isNotEmpty()) {
                    Text(
                        "Not restored:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        migrationNotRestoredLines.forEach { line ->
                            Text(
                                "• $line",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Text(
                "Your mail password is never included in a backup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
