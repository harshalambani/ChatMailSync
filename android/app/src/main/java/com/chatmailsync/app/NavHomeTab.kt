package com.chatmailsync.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

/** KT-13 step 4 (D240 = A, D241 = P1). The Home tab's routes: the Home screen itself, the full-screen sync progress view and the import picker.
 * Route bodies moved out of MainActivity.kt unchanged apart from `a.` in front of the actions, launchers
 * and callable references that live in NavActions. State comes through `s`, whose members read as before. */
internal fun NavGraphBuilder.homeTabRoutes(
    navController: NavHostController,
    s: NavScreenState,
    a: NavActions,
) = with(s) {
    composable("home") {
        // Home's inbox list is only otherwise refreshed right after an
        // import or a sync completes — if the app was relaunched or
        // navigated back to from another tab after a sync finished
        // (e.g. the process was killed mid-sync, losing the in-memory
        // WorkManager id), the list would keep showing already-synced
        // files. Re-check every time this screen is (re)entered.
        LaunchedEffect(Unit) { a.refreshInbox() }
        // Same "re-read on entry" reasoning as Settings: this is now
        // also where the masthead Me row lives, and a sync that ran
        // while Home sat on the back stack may have just learned a
        // name.
        LaunchedEffect(Unit) { a.refreshSelfSender() }
        // The one-time banner lives only on Home, so this is the one
        // place it needs a "re-read on entry" alongside ON_RESUME and
        // the post-sync effects above.
        LaunchedEffect(Unit) { a.refreshSelfSenderBanner() }
        // The pair HomeScreen's connection chip and Sync-now gating
        // are driven by.
        val homeAccountLabel = mail.imapEmail.ifBlank { null }
        val homeBackendReady = mail.imapPasswordSaved && mail.imapHost.isNotBlank()
        val homeConnectActionLabel = if (homeBackendReady) "Change" else "Set up"
        // Re-read on entry (remember is recreated when this
        // destination is re-composed after navigation) and whenever a
        // save reports back, so walking Settings -> save -> Home
        // clears the line rather than leaving it lying.
        val lastBackupAt = remember(backup.migrationStatus) {
            AppPrefs.getLastBackupAt(context)
        }
        val homeMeDisplay = selfSenderDisplay(selfSender.selfSenderSource, selfSender.selfSenderName)
        HomeScreen(
            accountLabel = homeAccountLabel,
            backendReady = homeBackendReady,
            connectActionLabel = homeConnectActionLabel,
            onConnect = {
                // First setup goes through the wizard (D1); once there
                // is a working account this button says "Change", and
                // changing one field is faster on the single-page
                // form, which is also where the wizard can be
                // re-launched from.
                if (homeBackendReady) {
                    navController.navigate("settings")
                } else {
                    navController.navigate("mailWizard")
                }
            },
            inboxFiles = inbox.inboxFiles,
            waitingNames = waitingNames,
            onReopenQuestion = { name -> link.linkSnoozed = reopenFor(link.linkSnoozed, link.linkQuestions, name) },
            // Goes to our own picker, not the system one. The system
            // picker is still reachable from inside it, one clearly
            // secondary button down, for files outside the granted
            // folder.
            onImportPick = { navController.navigate("importPicker") },
            onPreview = { name ->
                val path = ChatMailApplication.inboxDir(context).resolve(name).absolutePath
                ChatMailApplication.core(context).previewText(path, AppPrefs.getCutoffDate(context))
                    .toString()
            },
            onRemoveFile = { name -> a.removeInboxFile(name) },
            unreadableFiles = inbox.unreadableFiles,
            onRemoveUnreadable = { name -> a.removeUnreadableFile(name) },
            dryRunDefault = syncCfg.dryRunDefault,
            onDryRunDefaultChange = { syncCfg.dryRunDefault = it; AppPrefs.setDryRunDefault(context, it) },
            onSyncNow = { if (syncCfg.dryRunDefault) a.runDryRunSync() else a.startRealSync() },
            lastResult = inbox.lastResult,
            syncInProgress = anySyncRunning,
            backgroundIssues = watch.backgroundIssues,
            onBackgroundIssueAction = { issue ->
                // Null only below API 23, where there is no battery
                // exemption to grant and the issue is never raised in
                // the first place -- so nothing to fall back to.
                backgroundIssueIntent(context, issue)?.let { context.startActivity(it) }
            },
            onOpenSyncLog = { navController.navigate("syncLog") },
            onOpenQueue = { navController.navigate("queue") },
            onOpenBackup = { navController.navigate("backupRestore") },
            meLabel = homeMeDisplay.label,
            meColor = homeMeDisplay.color,
            meDescription = selfSenderContentDescription(selfSender.selfSenderSource, selfSender.selfSenderName),
            onMeClick = { navController.navigate("me") },
            lastBackupAt = lastBackupAt,
            cutoffDate = syncCfg.cutoffDate,
            onOpenSettings = { navController.navigate("settings") },
            pendingSelfSenderBannerName = selfSender.pendingSelfSenderBannerName,
            onSelfSenderBannerOk = { a.dismissSelfSenderBanner() },
            onSelfSenderBannerNotMe = {
                a.dismissSelfSenderBanner()
                navController.navigate("me")
            },
        )
    }
    composable("syncProgress") {
        // "Do a test run" on first-run step 3 pushes this same screen
        // on top of first_run rather than home — first_run is the
        // nav graph's start destination in that case, so "home" is
        // not on the back stack at all yet and popping to it here
        // would silently do nothing. Read which one is actually
        // underneath, same pattern as the settings/help "from" reads
        // elsewhere on this screen, and pop back to that instead.
        val from = navController.previousBackStackEntry?.destination?.route
        val popTarget = if (from == "first_run") "first_run" else "home"
        SyncProgressScreen(
            workManager = workManager,
            onDone = { navController.popBackStack(popTarget, inclusive = false) },
            // Same destination, deliberately different call: onDone
            // prunes the finished work first, and pruning a run that
            // is still going would leave the collapsed bar with
            // nothing to observe.
            onMinimize = { navController.popBackStack(popTarget, inclusive = false) },
        )
    }
    composable("importPicker") {
        // Re-read on entry for the same reason Home does: the folder
        // grant can be changed from Settings, and a file can be shared
        // in from WhatsApp while this screen sits on the back stack.
        LaunchedEffect(Unit) { a.refreshInbox() }
        ImportPickerScreen(
            onBack = { navController.popBackStack() },
            watchedFolderUri = watch.watchedFolderUri,
            // Names, not uris: what is already queued is a file in our
            // inbox directory, and ImportManager keys on the leaf name
            // too, so this marks exactly what a second import would
            // refuse.
            queuedNames = inbox.inboxFiles.map { it.first }.toSet(),
            onChooseFolder = { a.folderPicker.launch(null) },
            onPickFromAnywhere = { a.pickFile.launch(arrayOf("*/*")) },
            onImport = { uris ->
                a.importMany(uris)
                // Back to Home, where the queue and Sync now are. The
                // picker's job is done the moment the files are in.
                navController.popBackStack()
            },
        )
    }
}
