package com.chatmailsync.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

/** KT-13 step 4 (D240 = A, D241 = P1). The routes that light no tab (see tabForRoute): first run, the queue, Backup & restore, the sync log and its detail, Me, the mailbox wizard, Privacy and Licenses.
 * Route bodies moved out of MainActivity.kt unchanged apart from `a.` in front of the actions, launchers
 * and callable references that live in NavActions. State comes through `s`, whose members read as before. */
internal fun NavGraphBuilder.otherRoutes(
    navController: NavHostController,
    s: NavScreenState,
    a: NavActions,
) = with(s) {
    composable("first_run") {
        // Read once, at entry: a fresh install has no previous
        // back-stack entry (first_run is the graph's start
        // destination), while "Run setup again" from Advanced
        // settings (H7) pushes first_run on top of it, so this
        // single read is what tells the two launches apart.
        val cameFrom = navController.previousBackStackEntry?.destination?.route
        val finishTarget = firstRunFinishTarget(cameFrom)
        fun finishFirstRun() {
            if (!startup.firstRunDone) {
                AppPrefs.setFirstRunDone(context, true)
                startup.firstRunDone = true
            }
            // Clears first_run off the back stack rather than merely
            // pushing the target on top of it, so the hardware/
            // gesture back button does not return here.
            navController.navigate(finishTarget) {
                popUpTo("first_run") { inclusive = true }
                launchSingleTop = true
            }
        }
        FirstRunScreen(
            onSetUpLater = { finishFirstRun() },
            // UX-01: "Run setup again" shows a labelled back to where
            // it came from; a fresh install (no previous entry) none.
            backLabel = welcomeBackLabel(cameFrom),
            onBack = { navController.popBackStack() },
            imapProviders = mail.imapProviders,
            stagePlan = mail.stagePlan,
            initialProvider = mail.imapProvider,
            initialEmail = mail.imapEmail,
            onConnect = a.connectWithStages,
            queuedChatCount = inbox.inboxFiles.size,
            onDoTestRun = { a.runDryRunSync() },
            watchedFolderUri = watch.watchedFolderUri,
            onChooseFolder = { a.folderPicker.launch(null) },
            watchIntervalMinutes = watch.watchIntervalMinutes,
            onWatchIntervalChange = { a.setWatchInterval(it) },
            onTurnOn = {
                a.setAutoWatch(true)
                finishFirstRun()
            },
            onNotNow = { finishFirstRun() },
            hasExistingMailbox = mail.imapPasswordSaved,
            // Batch 7 follow-up: restore-on-first-run. Reuses the
            // exact restoreBackup launcher and migrationBusy/
            // migrationStatus state Backup & restore itself uses --
            // no second copy of the restore flow -- so a success here
            // also runs reloadRestoredSettingsState below, the same
            // as it would from Settings.
            offerRestore = shouldOfferRestoreOnFirstRun(cameFrom, startup.firstRunDone, mail.imapPasswordSaved),
            migrationBusy = backup.migrationBusy,
            migrationStatus = backup.migrationStatus,
            migrationSuccess = backup.migrationSuccess,
            migrationRestoredLines = backup.migrationRestoredLines,
            migrationNotRestoredLines = backup.migrationNotRestoredLines,
            onRestoreFromBackup = {
                // Clears any earlier attempt's message before a new
                // pick, the same as re-opening the picker would from
                // Backup & restore.
                backup.migrationStatus = null
                backup.migrationSuccess = null
                backup.migrationRestoredLines = emptyList()
                backup.migrationNotRestoredLines = emptyList()
                a.restoreBackup.launch(arrayOf("*/*"))
            },
        )
    }
    composable("queue") {
        // Same rule as home: this screen is reached from a list that
        // may have gone stale while the app was away.
        LaunchedEffect(Unit) { a.refreshInbox() }
        QueueScreen(
            files = inbox.inboxFiles,
            onPreview = { name ->
                val path = ChatMailApplication.inboxDir(context).resolve(name).absolutePath
                ChatMailApplication.core(context).previewText(path, AppPrefs.getCutoffDate(context))
                    .toString()
            },
            onRemove = { name -> a.removeInboxFile(name) },
            unreadableFiles = inbox.unreadableFiles,
            onRemoveUnreadable = { name -> a.removeUnreadableFile(name) },
            onImportPick = { navController.navigate("importPicker") },
            onBack = { navController.popBackStack() },
        )
    }
    composable("backupRestore") {
        val from = navController.previousBackStackEntry?.destination?.route
        BackupRestoreScreen(
            onBack = { navController.popBackStack() },
            backLabel = backLabelForRoute(from),
            onSaveBackup = {
                backup.migrationStatus = null
                backup.migrationSuccess = null
                backup.migrationRestoredLines = emptyList()
                backup.migrationNotRestoredLines = emptyList()
                a.saveBackup.launch(Migration.suggestedFileName())
            },
            onRestoreBackup = {
                backup.migrationStatus = null
                backup.migrationSuccess = null
                backup.migrationRestoredLines = emptyList()
                backup.migrationNotRestoredLines = emptyList()
                // Every type, not our own: a bundle that has been round
                // -tripped through Drive or Gmail can come back typed as
                // something else entirely, and a filter that hides the
                // file the user came to pick is worse than no filter.
                a.restoreBackup.launch(arrayOf("*/*"))
            },
            migrationBusy = backup.migrationBusy,
            migrationStatus = backup.migrationStatus,
            migrationSuccess = backup.migrationSuccess,
            migrationRestoredLines = backup.migrationRestoredLines,
            migrationNotRestoredLines = backup.migrationNotRestoredLines,
        )
    }
    composable("syncLog") {
        // Three ways in - Settings, the status card on Home, and the
        // sync bar, which rides every screen - so the back label is
        // read off the stack rather than fixed. Anything unmapped
        // falls back to a plain "Back", which is no worse than the
        // bare arrow this replaced.
        val from = navController.previousBackStackEntry?.destination?.route
        SyncLogScreen(
            onBack = { navController.popBackStack() },
            backLabel = backLabelForRoute(from),
            onOpenRun = { runId -> navController.navigate("syncLog/$runId") },
        )
    }
    composable("syncLog/{runId}") { entry ->
        // Only the id travels on the route. The detail screen re-reads
        // the same 90-day log the list used and picks the run out of
        // it, so the route survives process death and there is only
        // ever one query behind both levels.
        val runId = entry.arguments?.getString("runId")?.toLongOrNull() ?: 0L
        SyncRunDetailScreen(
            runId = runId,
            onBack = { navController.popBackStack() },
        )
    }
    composable("me") {
        // Three ways in - Home, Chats and a chat's own detail screen
        // (its strip) - so the back label is read off the stack, the
        // same way the sync log does it.
        val from = navController.previousBackStackEntry?.destination?.route
        LaunchedEffect(Unit) { a.refreshSelfSender() }
        var meSenders by remember { mutableStateOf(listOf<String>()) }
        LaunchedEffect(Unit) { meSenders = a.listChatSenders() }
        MeScreen(
            display = selfSenderDisplay(selfSender.selfSenderSource, selfSender.selfSenderName),
            detail = selfSender.selfSenderDetail,
            senders = meSenders,
            override = selfSender.selfSenderOverride,
            onPick = { a.setSelfSender(it) },
            onSave = { a.setSelfSender(it) },
            onClear = { a.setSelfSender("") },
            onBack = { navController.popBackStack() },
            backLabel = backLabelForRoute(from),
        )
    }
    composable("mailWizard") {
        MailSetupWizardScreen(
            onExit = { navController.popBackStack() },
            // Finishing drops the user back where they came from --
            // Home for a first setup, the Mail account screen for a
            // re-run -- rather than adding another screen to back out
            // of after the job is done.
            onDone = { navController.popBackStack() },
            imapProviders = mail.imapProviders,
            stagePlan = mail.stagePlan,
            initialProvider = mail.imapProvider,
            initialEmail = mail.imapEmail,
            onConnect = a.connectWithStages,
            hasExistingMailbox = mail.imapPasswordSaved,
            onKeepCurrentMailbox = { navController.popBackStack() },
        )
    }
    composable("privacy") {
        // Two ways in - Settings and Help - so the back label is read
        // off the stack, the same way the sync log does it.
        val from = navController.previousBackStackEntry?.destination?.route
        PrivacyScreen(
            onBack = { navController.popBackStack() },
            backLabel = backLabelForRoute(from),
        )
    }
    composable("licenses") {
        // One way in, from Settings' Help & About section -- same
        // backLabelForRoute lookup as every other leaf screen, kept
        // for consistency even though it always resolves to "Settings"
        // today.
        val from = navController.previousBackStackEntry?.destination?.route
        OpenSourceLicensesScreen(
            onBack = { navController.popBackStack() },
            backLabel = backLabelForRoute(from),
        )
    }
}
