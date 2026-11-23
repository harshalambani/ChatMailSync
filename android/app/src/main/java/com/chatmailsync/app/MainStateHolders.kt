package com.chatmailsync.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/*
 * KT-13 step 3 (D169, D234 = A): the state ChatMailApp keeps, grouped by the concern that owns it, in plain
 * classes created with `remember` in MainActivity.kt. No ViewModel, no new library, no new navigation code.
 *
 * Rules every holder keeps, so that grouping changes no behaviour:
 *  - each property is the same `mutableStateOf` it was as a local `remember`, with the same initial value
 *    computed once at the same moment, and none of them is saved (the one saveable value, the snoozed
 *    "same chat?" pairs, stays a `rememberSaveable` in MainActivity.kt and is only handed to LinkState);
 *  - effects and keyed remembers still key on the VALUES (`inbox.inboxFiles`), never on a holder object;
 *  - the app password is not here and never will be: only a Boolean saying one is stored.
 */

/** The mail backend and the IMAP account fields shown by first run, Home, Settings and the mailbox wizard. */
internal class MailAccountState(context: Context) {
    var mailBackend by mutableStateOf(AppPrefs.resolveMailBackend(context))
    var imapProvider by mutableStateOf(AppPrefs.getImapProvider(context))
    var imapHost by mutableStateOf(AppPrefs.getImapHost(context))
    var imapPort by mutableStateOf(AppPrefs.getImapPort(context))
    var imapEmail by mutableStateOf(AppPrefs.getImapEmail(context))
    var imapPasswordSaved by mutableStateOf(AppPrefs.hasImapPassword(context))
    var imapProviders by mutableStateOf(listOf<ImapProviderInfo>())
    var stagePlan by mutableStateOf(listOf<WizardStage>())
}

/** Who "me" is in the exports: the per-file detail, the manual override, where the name came from, and the Home banner. */
internal class SelfSenderState {
    var selfSenderDetail by mutableStateOf("")
    var selfSenderOverride by mutableStateOf("")
    var selfSenderSource by mutableStateOf<String?>(null)
    var selfSenderName by mutableStateOf<String?>(null)
    var pendingSelfSenderBannerName by mutableStateOf<String?>(null)
}

/** Backup and restore: the busy flag, the outcome shown to the user, the two result lists, and the restore generation. */
internal class BackupState {
    var migrationBusy by mutableStateOf(false)
    var migrationStatus by mutableStateOf<String?>(null)
    var migrationSuccess by mutableStateOf<Boolean?>(null)
    var migrationRestoredLines by mutableStateOf<List<String>>(emptyList())
    var migrationNotRestoredLines by mutableStateOf<List<String>>(emptyList())
    var restoreGeneration by mutableStateOf(0)
}

/** The watched folder, auto-watch and its interval, the synced-file policy, the background-health issues and the "Delete after import" confirmation that policy raises. */
internal class WatchState(context: Context) {
    var confirmDeleteAfterImport by mutableStateOf(false)
    var watchedFolderUri by mutableStateOf(AppPrefs.getWatchedFolderUri(context))
    var autoWatchEnabled by mutableStateOf(AppPrefs.isAutoWatchEnabled(context))
    var watchIntervalMinutes by mutableStateOf(AppPrefs.getWatchIntervalMinutes(context))
    var syncedFilePolicy by mutableStateOf(AppPrefs.getSyncedFilePolicy(context))
    var backgroundIssues by mutableStateOf(emptyList<BackgroundIssue>())
}

/** What the app decides at start: whether to explain the removed Google sign-in, and whether first run is done. */
internal class StartupState(context: Context) {
    var showOauthRemovedNotice by mutableStateOf(
        AppPrefs.isLegacyOauthUser(context) &&
            !AppPrefs.wasOauthRemovedNoticeShown(context)
    )
    var firstRunDone by mutableStateOf(AppPrefs.isFirstRunDone(context))
}

/** The inbox listing and the last result line. Import and sync both write lastResult; Home reads it. */
internal class InboxState {
    var inboxFiles by mutableStateOf(listOf<Pair<String, Long>>())
    var unreadableFiles by mutableStateOf(listOf<Pair<String, Long>>())
    var lastResult by mutableStateOf("Nothing run yet.")
}

/** The sync options Home and Advanced settings share, and whether the last run was a dry run. */
internal class SyncSettingsState(context: Context) {
    var chunkSize by mutableStateOf(AppPrefs.getChunkSize(context))
    var cutoffDate by mutableStateOf(AppPrefs.getCutoffDate(context))
    var dryRunDefault by mutableStateOf(AppPrefs.isDryRunDefault(context))
    var lastSyncWasDryRun by mutableStateOf(false)
}
