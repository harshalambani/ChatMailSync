@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Clamp to [1, 4] for the outer first-run steps.
 *
 * Kept as a pure function, not inlined into the step-change lambdas, so a
 * back-past-1 or next-past-4 mistake is a unit test away from being caught
 * rather than something only a manual click-through would notice. */
internal fun firstRunStepBack(step: Int): Int = (step - 1).coerceAtLeast(1)

internal fun firstRunStepForward(step: Int): Int = (step + 1).coerceAtMost(4)

/** Step 4's "Turn on" is disabled until a folder is actually chosen -- a
 * blank/null watched-folder URI has nothing for WatchFolderWorker to read,
 * so offering the button before then would just fail silently later. */
internal fun canEnableAutoImportFromFirstRun(watchedFolderUri: String?): Boolean =
    !watchedFolderUri.isNullOrBlank()

/** The outer four step titles, next to the "Step N of 4" label on steps 2-4.
 * Step 2 hands the screen to MailSetupWizardScreen, which carries its own
 * "Step n of 4" for its four internal sub-steps -- a second, larger-scale
 * "Step 2 of 4" is not drawn on top of it, so this list is only actually read
 * for steps 1, 3 and 4. It stays a list of 4 (not 3) so nothing has to
 * remember the gap, and so a test can assert against it by step number.
 *
 * Kept free of the words first-run may never ask about (D7): no cut-off, no
 * chunk size, no after-import policy, no "Me" field. See
 * FirstRunStepTitlesTest. */
internal val FIRST_RUN_STEP_TITLES = listOf(
    "Welcome",
    "Connect your mailbox",
    "Share your first chat",
    "Keep it automatic?",
)

/**
 * The four-step guided path a brand-new install lands on (D7): welcome,
 * connect a mailbox, share one chat, and decide whether to automate the
 * rest. Everything here reuses an existing screen or an existing pref/worker
 * rather than inventing a second copy:
 *  - step 2 is the existing MailSetupWizardScreen, unmodified;
 *  - step 3's "chat arrived?" status is the same inboxFiles queue Home
 *    shows, and "Do a test run" is the same dry-run path Home's Sync-now
 *    offers;
 *  - step 4's folder/interval controls are the same AppPrefs-backed state
 *    Settings' "Watched folder" section reads and writes.
 *
 * Nothing here is a dialog or a bottom sheet -- every step draws in the main
 * window, matching the rest of the app.
 */
@Composable
fun FirstRunScreen(
    onSetUpLater: () -> Unit,
    // Step 2 - identical params to MailSetupWizardScreen itself.
    imapProviders: List<ImapProviderInfo>,
    stagePlan: List<WizardStage>,
    initialProvider: String,
    initialEmail: String,
    onConnect: (String, String, Int, String, String, StageListener, (Boolean, String) -> Unit) -> Unit,
    // Step 3 - the existing inbox queue and dry-run trigger.
    queuedChatCount: Int,
    onDoTestRun: () -> Unit,
    // Step 4 - the existing watched-folder controls.
    watchedFolderUri: String?,
    onChooseFolder: () -> Unit,
    watchIntervalMinutes: Long,
    onWatchIntervalChange: (Long) -> Unit,
    onTurnOn: () -> Unit,
    onNotNow: () -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(1) }

    when (step) {
        1 -> FirstRunWelcomeStep(
            onGetStarted = { step = firstRunStepForward(step) },
            onSetUpLater = onSetUpLater,
        )
        2 -> MailSetupWizardScreen(
            onExit = { step = firstRunStepBack(step) },
            onDone = { step = firstRunStepForward(step) },
            imapProviders = imapProviders,
            stagePlan = stagePlan,
            initialProvider = initialProvider,
            initialEmail = initialEmail,
            onConnect = onConnect,
        )
        3 -> FirstRunShareChatStep(
            onBack = { step = firstRunStepBack(step) },
            queuedChatCount = queuedChatCount,
            onDoTestRun = onDoTestRun,
            onContinue = { step = firstRunStepForward(step) },
            onSkip = { step = firstRunStepForward(step) },
        )
        4 -> FirstRunAutomaticStep(
            onBack = { step = firstRunStepBack(step) },
            watchedFolderUri = watchedFolderUri,
            onChooseFolder = onChooseFolder,
            watchIntervalMinutes = watchIntervalMinutes,
            onWatchIntervalChange = onWatchIntervalChange,
            onTurnOn = onTurnOn,
            onNotNow = onNotNow,
        )
    }
}

@Composable
private fun FirstRunWelcomeStep(onGetStarted: () -> Unit, onSetUpLater: () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { ChatMailTopBar(title = "Chat Mail Sync", showConnection = false) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Chat Mail Sync archives your WhatsApp chats into your own mailbox. " +
                    "Nothing leaves your phone except straight to that mailbox, with no " +
                    "server or company in between.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onGetStarted, modifier = Modifier.fillMaxWidth()) {
                Text("Get started")
            }
            TextButton(onClick = onSetUpLater, modifier = Modifier.fillMaxWidth()) {
                Text("Set up later")
            }
        }
    }
}

@Composable
private fun FirstRunShareChatStep(
    onBack: () -> Unit,
    queuedChatCount: Int,
    onDoTestRun: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatMailTopBar(
                title = FIRST_RUN_STEP_TITLES[2],
                subtitle = "Step 3 of 4",
                showConnection = false,
                backLabel = "Previous step",
                onBack = onBack,
            )
        },
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "In WhatsApp, open a chat and tap the menu. Choose More, then Export chat, " +
                    "and pick Chat Mail Sync from the list that comes up.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (queuedChatCount == 0) {
                    "Waiting for a chat to arrive."
                } else {
                    "${plural(queuedChatCount, "chat")} waiting."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (queuedChatCount == 0) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            )
            if (queuedChatCount > 0) {
                OutlinedButton(onClick = onDoTestRun, modifier = Modifier.fillMaxWidth()) {
                    Text("Do a test run")
                }
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue")
                }
            }
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("Skip for now")
            }
        }
    }
}

@Composable
private fun FirstRunAutomaticStep(
    onBack: () -> Unit,
    watchedFolderUri: String?,
    onChooseFolder: () -> Unit,
    watchIntervalMinutes: Long,
    onWatchIntervalChange: (Long) -> Unit,
    onTurnOn: () -> Unit,
    onNotNow: () -> Unit,
) {
    var intervalMenuOpen by remember { mutableStateOf(false) }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatMailTopBar(
                title = FIRST_RUN_STEP_TITLES[3],
                subtitle = "Step 4 of 4",
                showConnection = false,
                backLabel = "Previous step",
                onBack = onBack,
            )
        },
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Pick a folder and Chat Mail Sync can pick up new exports on its own, on " +
                    "the schedule below, instead of you opening the app each time.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                watchedFolderUri?.let { android.net.Uri.parse(it).lastPathSegment ?: it }
                    ?: "No folder chosen",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onChooseFolder, modifier = Modifier.fillMaxWidth()) {
                Text(if (watchedFolderUri == null) "Choose folder" else "Change folder")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Check every", style = MaterialTheme.typography.bodyMedium)
                Box {
                    OutlinedButton(onClick = { intervalMenuOpen = true }) {
                        Text(
                            WATCH_INTERVAL_LABELS.firstOrNull { it.first == watchIntervalMinutes }?.second
                                ?: "Every $watchIntervalMinutes min",
                        )
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
            }
            Button(
                onClick = onTurnOn,
                enabled = canEnableAutoImportFromFirstRun(watchedFolderUri),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Turn on")
            }
            TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth()) {
                Text("Not now")
            }
        }
    }
}
