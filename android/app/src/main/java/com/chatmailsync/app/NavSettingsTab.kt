package com.chatmailsync.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

/** KT-13 step 4 (D240 = A, D241 = P1). The Settings tab's routes: Settings and the sub-screens that light the Settings tab (see tabForRoute).
 * Route bodies moved out of MainActivity.kt unchanged apart from `a.` in front of the actions, launchers
 * and callable references that live in NavActions. State comes through `s`, whose members read as before. */
internal fun NavGraphBuilder.settingsTabRoutes(
    navController: NavHostController,
    s: NavScreenState,
    a: NavActions,
) = with(s) {
    composable("settings") {
        // Re-read on entry, not once at startup: a sync that happened
        // while this screen sat on the back stack may have worked the
        // name out, and the screen exists to say so.
        LaunchedEffect(Unit) { a.refreshSelfSender() }
        // Backend-neutral one-line status for the "Mail account" nav
        // row — SettingsScreen no longer receives the backend params
        // it would need to compute this itself now that the account
        // UI lives on its own screen.
        val mailAccountSummary =
            if (mail.imapPasswordSaved) mail.imapEmail else "Not connected"
        // Re-read on entry and whenever the shared migration state
        // moves -- same "walking Settings -> save -> Home clears the
        // line" reasoning Home's own lastBackupAt carries, now that
        // the row here is a pill rather than the buttons themselves.
        val lastBackupAt = remember(backup.migrationStatus) {
            AppPrefs.getLastBackupAt(context)
        }
        SettingsScreen(
            mailAccountSummary = mailAccountSummary,
            onOpenMailAccount = { navController.navigate("mailAccount") },
            onOpenHelp = { navController.navigate("helpAbout") },
            onOpenAdvanced = { navController.navigate("advancedSettings") },
            onOpenBackupRestore = { navController.navigate("backupRestore") },
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            lastBackupAt = lastBackupAt,
            selfSenderSource = selfSender.selfSenderSource,
            selfSenderName = selfSender.selfSenderName,
            onOpenMe = { navController.navigate("me") },
        )
    }
    composable("mailAccount") {
        // Reachable from the connection pill on every masthead, not
        // just Settings -- so unlike most rows here, "Settings" is
        // not a safe default.
        val from = navController.previousBackStackEntry?.destination?.route
        MailAccountScreen(
            onBack = { navController.popBackStack() },
            backLabel = backLabelForRoute(from),
            // check_connection (the dict) rather than
            // check_connection_text (the string it is flattened to):
            // the banner dot needs the pass/fail as a fact, and
            // parsing it back out of display prose would break the
            // moment that prose is reworded. See testImapConnection
            // for the five-stage check itself -- shared with
            // Advanced settings' Mail server section so there is one
            // copy of this logic, not two that can drift apart.
            onTestConnection = a.testImapConnection,
            imapProviders = mail.imapProviders,
            imapProvider = mail.imapProvider,
            onImapProviderChange = a.onImapProviderChange,
            imapHost = mail.imapHost,
            onImapHostChange = { mail.imapHost = it },
            imapPort = mail.imapPort,
            onImapPortChange = { mail.imapPort = it },
            imapEmail = mail.imapEmail,
            onImapEmailChange = { mail.imapEmail = it },
            imapPasswordSaved = mail.imapPasswordSaved,
            onSaveImapSettings = a.saveImapSettings,
            onForgetImapPassword = a.forgetImapPassword,
            onRunWizard = { navController.navigate("mailWizard") },
        )
    }
    composable("help") {
        val from = navController.previousBackStackEntry?.destination?.route
        HelpScreen(
            onBack = { navController.popBackStack() },
            onOpenPrivacy = { navController.navigate("privacy") },
            backLabel = backLabelForRoute(from),
        )
    }
    composable("helpAbout") {
        val from = navController.previousBackStackEntry?.destination?.route
        HelpAboutScreen(
            onBack = { navController.popBackStack() },
            backLabel = backLabelForRoute(from),
            onOpenHelp = { navController.navigate("help") },
            onOpenPrivacy = { navController.navigate("privacy") },
            onOpenLicenses = { navController.navigate("licenses") },
        )
    }
    composable("advancedSettings") {
        val from = navController.previousBackStackEntry?.destination?.route
        AdvancedSettingsScreen(
            onBack = { navController.popBackStack() },
            backLabel = backLabelForRoute(from),
            onOpenSyncLog = { navController.navigate("syncLog") },
            // Opens the walkthrough exactly as first-run leaves it —
            // no preference write here, only a navigate. first_run
            // itself reads where it came from (see firstRunFinishTarget)
            // to send "Turn on"/"Set up later"/"Not now" back here
            // instead of to Home.
            onRunSetupAgain = { navController.navigate("first_run") },
            watchedFolderUri = watch.watchedFolderUri,
            onChooseFolder = { a.folderPicker.launch(null) },
            onClearFolder = { a.clearWatchedFolder() },
            autoWatchEnabled = watch.autoWatchEnabled,
            onAutoWatchChange = { a.setAutoWatch(it) },
            watchIntervalMinutes = watch.watchIntervalMinutes,
            onWatchIntervalChange = { a.setWatchInterval(it) },
            onCheckNow = { WatchFolderWorker.enqueueOnce(context) },
            syncInProgress = anySyncRunning,
            syncedFilePolicy = watch.syncedFilePolicy,
            onSyncedFilePolicyChange = { a.setSyncedFilePolicy(it) },
            cutoffDate = syncCfg.cutoffDate,
            onCutoffDateChange = {
                syncCfg.cutoffDate = it
                AppPrefs.setCutoffDate(context, it)
            },
            dryRunDefault = syncCfg.dryRunDefault,
            onDryRunDefaultChange = {
                syncCfg.dryRunDefault = it
                AppPrefs.setDryRunDefault(context, it)
            },
            chunkSize = syncCfg.chunkSize,
            onChunkSizeChange = { syncCfg.chunkSize = it; AppPrefs.setChunkSize(context, it) },
            onTestConnection = a.testImapConnection,
        )
    }
}
