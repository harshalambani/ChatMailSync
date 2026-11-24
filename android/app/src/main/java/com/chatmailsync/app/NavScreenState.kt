package com.chatmailsync.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.work.WorkManager

/*
 * KT-13 step 4 (D241 = P1): what the per-tab route builders (NavHomeTab.kt, NavChatsTab.kt, NavSettingsTab.kt,
 * NavOtherRoutes.kt) need from ChatMailApp, in two plain classes built in MainActivity.kt right before the Scaffold.
 * Neither is remembered or saved: they are rebuilt every composition, as the lambdas the NavHost block used to
 * capture were, so no stale value can reach a screen. State stays in the step 3 holders; the one saveable value
 * (linkSnoozed) is still a rememberSaveable in MainActivity.kt and reaches the screens only through `link`.
 */

/** The state the route bodies read. The builders run inside `with(s)`, so `mail.imapHost` reads as it always did. */
internal class NavScreenState(
    val context: Context,
    val startup: StartupState,
    val mail: MailAccountState,
    val selfSender: SelfSenderState,
    val backup: BackupState,
    val watch: WatchState,
    val inbox: InboxState,
    val syncCfg: SyncSettingsState,
    val link: LinkState,
    val themeMode: String,
    val onThemeModeChange: (String) -> Unit,
    val workManager: WorkManager,
    val anySyncRunning: Boolean,
    val waitingNames: Set<String>,
)

/** The local functions and activity-result launchers of ChatMailApp that route bodies call, passed in as they are. */
internal class NavActions(
    val connectWithStages: (String, String, Int, String, String, StageListener, (Boolean, String) -> Unit) -> Unit,
    val runDryRunSync: () -> Unit,
    val setWatchInterval: (Long) -> Unit,
    val setAutoWatch: (Boolean) -> Unit,
    val refreshInbox: () -> Unit,
    val refreshSelfSender: () -> Unit,
    val refreshSelfSenderBanner: () -> Unit,
    val removeInboxFile: (String) -> Unit,
    val removeUnreadableFile: (String) -> Unit,
    private val startRealSyncImpl: (String?) -> Unit,
    val dismissSelfSenderBanner: () -> Unit,
    val clearWatchedFolder: () -> Unit,
    val setSyncedFilePolicy: (String) -> Unit,
    val testImapConnection: ((String) -> Unit) -> Unit,
    val importMany: (List<Uri>) -> Unit,
    val onImapProviderChange: (String) -> Unit,
    val saveImapSettings: (String, String, Int, String, String, (Boolean, String) -> Unit) -> Unit,
    val forgetImapPassword: () -> Unit,
    val listChatSenders: () -> List<String>,
    val setSelfSender: (String) -> Unit,
    val pickFile: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
    val saveBackup: ManagedActivityResultLauncher<String, Uri?>,
    val restoreBackup: ManagedActivityResultLauncher<Array<String>, Uri?>,
    val folderPicker: ManagedActivityResultLauncher<Uri?, Uri?>,
) {
    /** Same signature and default as the local function in ChatMailApp, so `startRealSync(chatFilter = id)` reads as before. */
    fun startRealSync(chatFilter: String? = null) = startRealSyncImpl(chatFilter)
}
