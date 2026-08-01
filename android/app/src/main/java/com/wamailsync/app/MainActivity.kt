package com.wamailsync.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.chaquo.python.Python
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

// Matches src/config.py's GMAIL_SCOPES, plus userinfo.email so the app can
// show "Connected as <email>" — AuthorizationResult itself carries only the
// access token, not account identity (see Phase A3 plan). Not private:
// WatchFolderWorker (headless, no Activity) needs these too, to build the
// "oauth2:<scopes>" string GoogleAuthUtil.getToken() expects.
val GMAIL_SCOPES = listOf(
    Scope("https://www.googleapis.com/auth/gmail.insert"),
    Scope("https://www.googleapis.com/auth/gmail.labels"),
    Scope("https://www.googleapis.com/auth/userinfo.email"),
)

/** Play Services caches OAuth tokens per-account independently of this
 * app's own storage. If Gmail rejects a token with 401 (revoked, expired,
 * or otherwise invalidated on Google's side), silently re-authorizing just
 * hands back the same stale cached token until that cache entry is
 * explicitly dropped via GoogleAuthUtil.clearToken(). Blocks synchronously
 * (Tasks.await) — call only from a background thread. */
private fun refreshStaleToken(activity: android.app.Activity, staleToken: String): String? {
    return try {
        GoogleAuthUtil.clearToken(activity, staleToken)
        val request = AuthorizationRequest.builder().setRequestedScopes(GMAIL_SCOPES).build()
        Tasks.await(Identity.getAuthorizationClient(activity).authorize(request)).accessToken
    } catch (_: Exception) {
        null
    }
}

class MainActivity : ComponentActivity() {

    private var onImported: ((Uri) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var themeMode by remember { mutableStateOf(AppPrefs.getThemeMode(this)) }
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            WaMailTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WaMailApp(
                        registerImportCallback = { onImported = it },
                        themeMode = themeMode,
                        onThemeModeChange = {
                            themeMode = it
                            AppPrefs.setThemeMode(this, it)
                        },
                    )
                }
            }
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        onImported?.invoke(uri)
    }
}

private data class BottomDest(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val bottomDests = listOf(
    BottomDest("home", "Home", Icons.Filled.Home),
    BottomDest("chats", "Chats", Icons.Filled.List),
    BottomDest("settings", "Settings", Icons.Filled.Settings),
)

/** Always-present footer row above the bottom nav — this is a sync app, so
 * "is anything syncing right now" deserves dedicated, permanent real estate
 * rather than being buried in whichever tab happens to be open. Shows live
 * progress while a sync (manual or watched-folder/scheduled) is running, and
 * the last known outcome otherwise; tapping jumps to the most relevant
 * detail screen for whatever it's currently showing. */
@Composable
private fun SyncStatusBar(text: String, fraction: Float?, running: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                tint = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (running) {
            if (fraction != null && fraction in 0f..1f) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }
}

@Composable
fun WaMailApp(
    registerImportCallback: ((Uri) -> Unit) -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // ---- Connection state (Phase A3) --------------------------------
    var connectedEmail by remember { mutableStateOf<String?>(null) }
    var accessToken by remember { mutableStateOf<String?>(null) }

    fun fetchEmail(token: String) {
        Thread {
            val email = try {
                val conn = URL("https://www.googleapis.com/oauth2/v3/userinfo")
                    .openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer $token")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body).optString("email").ifBlank { null }
            } catch (_: Exception) {
                null
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                connectedEmail = email ?: "connected (email lookup failed)"
                if (email != null) AppPrefs.setConnectedAccountEmail(context, email)
            }
        }.start()
    }

    var connectError by remember { mutableStateOf<String?>(null) }

    val authLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { activityResult ->
        try {
            val result = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(activityResult.data)
            accessToken = result.accessToken
            result.accessToken?.let { fetchEmail(it) }
        } catch (e: ApiException) {
            connectError = "Authorization failed: ${e.message}"
        }
    }

    fun connectGmail(silent: Boolean) {
        val request = AuthorizationRequest.builder().setRequestedScopes(GMAIL_SCOPES).build()
        Identity.getAuthorizationClient(context as android.app.Activity)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    if (!silent) {
                        val pendingIntent = result.pendingIntent
                        authLauncher.launch(IntentSenderRequest.Builder(pendingIntent!!.intentSender).build())
                    }
                } else {
                    accessToken = result.accessToken
                    result.accessToken?.let { fetchEmail(it) }
                }
            }
            .addOnFailureListener { e ->
                if (!silent) connectError = "Authorization failed: ${e.message}"
            }
    }

    // Disconnect must revoke the grant in Play Services itself, not just
    // clear our own state — Play Services caches "this app is authorized
    // for this account" independently of anything the app stores, so a
    // local-only disconnect left the next Connect silently re-authorizing
    // the same account with no picker/consent screen (see 2026-07 account-
    // switch bug). revokeAccess() clears both the grant and its cached
    // tokens so the next authorize() call comes back with hasResolution().
    fun disconnectGmail() {
        val email = connectedEmail
        if (email != null) {
            val account = android.accounts.Account(email, "com.google")
            val request = RevokeAccessRequest.builder()
                .setAccount(account)
                .setScopes(GMAIL_SCOPES)
                .build()
            Identity.getAuthorizationClient(context as android.app.Activity).revokeAccess(request)
        }
        accessToken = null
        connectedEmail = null
        AppPrefs.setConnectedAccountEmail(context, null)
    }

    // "Token survives app restart": silently re-check on screen load.
    LaunchedEffect(Unit) { connectGmail(silent = true) }

    // ---- Mail backend: IMAP app password parity with Windows -----------
    // mailBackend/imapProvider/imapHost/imapPort/imapEmail mirror AppPrefs
    // (persisted there), but only *after* a successful Save & connect — see
    // saveImapSettings below, which follows gui_worker.connect_imap's
    // "validate, build transport, force a real login via labels_list(),
    // persist only on success" contract. imapPasswordSaved never reflects
    // the password itself, only whether SecretStore currently holds one.
    var mailBackend by remember { mutableStateOf(AppPrefs.resolveMailBackend(context)) }
    var imapProvider by remember { mutableStateOf(AppPrefs.getImapProvider(context)) }
    var imapHost by remember { mutableStateOf(AppPrefs.getImapHost(context)) }
    var imapPort by remember { mutableStateOf(AppPrefs.getImapPort(context)) }
    var imapEmail by remember { mutableStateOf(AppPrefs.getImapEmail(context)) }
    var imapPasswordSaved by remember { mutableStateOf(AppPrefs.hasImapPassword(context)) }
    var imapProviders by remember { mutableStateOf(listOf<ImapProviderInfo>()) }

    // Reads config.IMAP_PROVIDERS via the Python side once per composition —
    // same preset table (host/port per provider) the Windows GUI uses, so
    // Android never duplicates that data in Kotlin.
    LaunchedEffect(Unit) {
        val result = Python.getInstance().getModule("src.android_api").callAttr("imap_providers")
        imapProviders = result.asList().map { entry ->
            ImapProviderInfo(
                key = entry.callAttr("get", "key").toString(),
                label = entry.callAttr("get", "label").toString(),
                host = entry.callAttr("get", "host").toString(),
                port = entry.callAttr("get", "port").toString().toIntOrNull() ?: 993,
            )
        }
    }

    fun onMailBackendChange(backend: String) {
        mailBackend = backend
        AppPrefs.setMailBackend(context, backend)
    }

    fun onImapProviderChange(provider: String) {
        imapProvider = provider
        if (provider != "custom") {
            imapProviders.firstOrNull { it.key == provider }?.let {
                imapHost = it.host
                imapPort = it.port
            }
        }
    }

    // Never echoes the secret itself back into a UI string, even on
    // failure — imaplib/ssl exception text doesn't normally embed the
    // password, but this is a zero-cost belt-and-braces check against the
    // "must never reach ... an exception message" constraint.
    fun redactSecret(text: String, secret: String?): String =
        if (!secret.isNullOrEmpty() && text.contains(secret)) text.replace(secret, "********") else text

    fun saveImapSettings(
        provider: String,
        host: String,
        port: Int,
        email: String,
        password: String,
        onResult: (Boolean, String) -> Unit,
    ) {
        val effectiveHost = if (provider == "custom") host else
            (imapProviders.firstOrNull { it.key == provider }?.host?.takeIf { it.isNotBlank() } ?: host)
        if (provider == "custom" && effectiveHost.isBlank()) {
            onResult(false, "Enter a host for a custom IMAP server.")
            return
        }
        if (email.isBlank()) {
            onResult(false, "Enter the email address to connect with.")
            return
        }
        // Blank password field means "keep the currently saved password" —
        // the field is deliberately never pre-filled with the real value
        // (see SettingsScreen), so this is the only way to re-save
        // provider/host/email without re-entering an unchanged password.
        val existingPassword = SecretStore.getSecret(context, AppPrefs.getImapPasswordSecretKey())
        val effectivePassword = password.ifBlank { existingPassword ?: "" }
        if (effectivePassword.isBlank()) {
            onResult(false, "Enter the app password to connect with.")
            return
        }
        Thread {
            var transport: com.chaquo.python.PyObject? = null
            val errorText = try {
                transport = Python.getInstance().getModule("src.mail_client")
                    .callAttr("build_imap_transport", effectiveHost, port, email, effectivePassword)
                // Forces a real login (mirrors gui_worker.connect_imap) so a
                // wrong host/port/password/app-password is caught here, not
                // on the next real sync.
                transport?.callAttr("labels_list")
                null
            } catch (e: Exception) {
                redactSecret("Could not connect: ${e.message ?: "unknown error"}", effectivePassword)
            } finally {
                try {
                    transport?.callAttr("close")
                } catch (_: Exception) {
                    // Best-effort logout only.
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (errorText == null) {
                    AppPrefs.setImapProvider(context, provider)
                    AppPrefs.setImapHost(context, effectiveHost)
                    AppPrefs.setImapPort(context, port)
                    AppPrefs.setImapEmail(context, email)
                    SecretStore.putSecret(context, AppPrefs.getImapPasswordSecretKey(), effectivePassword)
                    imapProvider = provider
                    imapHost = effectiveHost
                    imapPort = port
                    imapEmail = email
                    imapPasswordSaved = true
                    onResult(true, "Connected — settings saved.")
                } else {
                    onResult(false, errorText)
                }
            }
        }.start()
    }

    fun forgetImapPassword() {
        AppPrefs.clearImapSettings(context)
        imapPasswordSaved = false
        imapProvider = "gmail"
        imapHost = ""
        imapPort = 993
        imapEmail = ""
    }

    // ---- Inbox + import (Phase A2) -----------------------------------
    var inboxFiles by remember { mutableStateOf(listOf<Pair<String, Long>>()) }
    var lastResult by remember { mutableStateOf("Nothing run yet.") }

    fun refreshInbox() {
        val result = Python.getInstance().getModule("src.android_api").callAttr("list_inbox")
        inboxFiles = result.asList().map { entry ->
            val name = entry.callAttr("get", "name").toString()
            val size = entry.callAttr("get", "size_bytes").toString().toLongOrNull() ?: 0L
            name to size
        }
    }

    fun removeInboxFile(name: String) {
        Python.getInstance().getModule("src.android_api").callAttr("remove_from_inbox", name)
        refreshInbox()
    }

    fun importAndPreview(uri: Uri) {
        val outcome = ImportManager.importUri(context, uri)
        if (outcome == null) {
            lastResult = "Import failed: could not read the shared/selected file."
            return
        }
        refreshInbox()
        if (outcome.alreadyQueued) {
            lastResult = "${outcome.file.name} is already queued — remove it first (X) to re-import."
            return
        }
        val preview = Python.getInstance()
            .getModule("src.android_api")
            .callAttr("preview", outcome.file.absolutePath)
        lastResult = "Imported ${outcome.file.name}\n\n$preview"
    }

    registerImportCallback { uri -> importAndPreview(uri) }

    // OpenMultipleDocuments (not OpenDocument): the single-select contract
    // was the "only 1 file at a time" bug reported from Home testing.
    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> uris.forEach { importAndPreview(it) } }

    LaunchedEffect(Unit) { refreshInbox() }

    // ---- Watched folder (Home feedback: auto-detect new export files) --
    var watchedFolderUri by remember { mutableStateOf(AppPrefs.getWatchedFolderUri(context)) }
    var autoWatchEnabled by remember { mutableStateOf(AppPrefs.isAutoWatchEnabled(context)) }
    var watchIntervalMinutes by remember { mutableStateOf(AppPrefs.getWatchIntervalMinutes(context)) }
    var syncedFilePolicy by remember { mutableStateOf(AppPrefs.getSyncedFilePolicy(context)) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Write permission is needed (not just read) so the "move to
            // synced/" file policy below can create a subfolder and
            // relocate files in the watched tree, not just copy out of it.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            AppPrefs.setWatchedFolderUri(context, uri.toString())
            watchedFolderUri = uri.toString()
        }
    }

    fun setAutoWatch(enabled: Boolean) {
        autoWatchEnabled = enabled
        AppPrefs.setAutoWatchEnabled(context, enabled)
        if (enabled) WatchFolderWorker.enqueue(context, watchIntervalMinutes) else WatchFolderWorker.cancel(context)
    }

    fun setWatchInterval(minutes: Long) {
        watchIntervalMinutes = minutes
        AppPrefs.setWatchIntervalMinutes(context, minutes)
        if (autoWatchEnabled) WatchFolderWorker.enqueue(context, minutes)
    }

    fun setSyncedFilePolicy(policy: String) {
        syncedFilePolicy = policy
        AppPrefs.setSyncedFilePolicy(context, policy)
    }

    fun clearWatchedFolder() {
        setAutoWatch(false)
        AppPrefs.setWatchedFolderUri(context, null)
        watchedFolderUri = null
    }

    // ---- Sync defaults (Phase A5 Home sync controls) -------------------
    // Persisted (AppPrefs), not remember-only — previously reset to "day"/
    // false on every process death, and WatchFolderWorker's auto-sync
    // couldn't see the user's choice at all since it runs in a separate
    // process-less Worker with no access to this Compose state.
    var chunkSize by remember { mutableStateOf(AppPrefs.getChunkSize(context)) }
    var dryRunDefault by remember { mutableStateOf(AppPrefs.isDryRunDefault(context)) }

    // ---- Real sync via SyncWorker (Phase A4) ---------------------------
    val workManager = remember { WorkManager.getInstance(context) }
    var lastSyncWasDryRun by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* Sync still runs as a foreground service either way; a denied
          notification permission only means the user won't see progress. */ }

    fun startRealSync(chatFilter: String? = null) {
        if (mailBackend == AppPrefs.MAIL_BACKEND_IMAP) {
            // No OAuth dance at all for IMAP — SyncWorker reads host/email
            // from AppPrefs and the password from SecretStore itself, same
            // as the watched-folder auto-sync path.
            if (!imapPasswordSaved) {
                lastResult = "Save your IMAP app password in Settings > Mail account first."
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(
                    Data.Builder()
                        .putBoolean(SyncWorker.KEY_DRY_RUN, false)
                        .putString(SyncWorker.KEY_CHUNK_SIZE, chunkSize)
                        .putString(SyncWorker.KEY_TRIGGER, "manual")
                        .putString(SyncWorker.KEY_CHAT_FILTER, chatFilter)
                        .putString(SyncWorker.KEY_MAIL_BACKEND, AppPrefs.MAIL_BACKEND_IMAP)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            lastSyncWasDryRun = false
            workManager.enqueueUniqueWork(
                SyncWorker.UNIQUE_WORK_NAME_MANUAL_SYNC,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            navController.navigate("syncProgress")
            return
        }
        if (connectedEmail == null) {
            lastResult = "Connect your mailbox first."
            return
        }
        // Re-authorize right before enqueuing, rather than trusting whatever
        // `accessToken` was captured at — Google access tokens expire in
        // roughly an hour, but this Activity/composition can stay alive far
        // longer than that (screen just locked, not force-stopped), and the
        // one-shot LaunchedEffect(Unit) silent check only ever runs once per
        // process lifetime. Using a stale token here produced a 401 on the
        // first Gmail API call that actually needed one (creating a label
        // for a brand-new chat) even though the user had "just" synced.
        val authRequest = AuthorizationRequest.builder().setRequestedScopes(GMAIL_SCOPES).build()
        Identity.getAuthorizationClient(context as android.app.Activity)
            .authorize(authRequest)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    lastResult = "Your Gmail connection needs to be renewed — tap Reconnect, then try again."
                    return@addOnSuccessListener
                }
                val token = result.accessToken
                if (token == null) {
                    lastResult = "Connect your mailbox first."
                    return@addOnSuccessListener
                }
                accessToken = token

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                val request = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(
                        Data.Builder()
                            .putString(SyncWorker.KEY_ACCESS_TOKEN, token)
                            .putBoolean(SyncWorker.KEY_DRY_RUN, false)
                            .putString(SyncWorker.KEY_CHUNK_SIZE, chunkSize)
                            .putString(SyncWorker.KEY_TRIGGER, "manual")
                            .putString(SyncWorker.KEY_CHAT_FILTER, chatFilter)
                            .putString(SyncWorker.KEY_MAIL_BACKEND, AppPrefs.MAIL_BACKEND_GMAIL_OAUTH)
                            .build()
                    )
                    // Sync always needs the network; if WorkManager ever defers
                    // this (e.g. system under memory/battery pressure right as
                    // it's enqueued) this stops it burning a wakeup with no
                    // connectivity instead of starting and failing immediately.
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .build()
                lastSyncWasDryRun = false
                workManager.enqueueUniqueWork(
                    SyncWorker.UNIQUE_WORK_NAME_MANUAL_SYNC,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
                navController.navigate("syncProgress")
            }
            .addOnFailureListener { e ->
                lastResult = "Could not refresh Gmail connection: ${e.message}"
            }
    }

    // Previously ran android_api.sync() directly on the Compose click
    // handler — blocking the UI thread for however long a large export
    // took, with no progress indication and no way to cancel. Routed
    // through SyncWorker instead (dry_run=true, no access token needed),
    // same as a real sync.
    fun runDryRunSync() {
        lastSyncWasDryRun = true
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(
                Data.Builder()
                    .putBoolean(SyncWorker.KEY_DRY_RUN, true)
                    .putString(SyncWorker.KEY_CHUNK_SIZE, chunkSize)
                    .putString(SyncWorker.KEY_TRIGGER, "manual")
                    .putString(SyncWorker.KEY_MAIL_BACKEND, mailBackend)
                    .build()
            )
            .build()
        workManager.enqueueUniqueWork(
            SyncWorker.UNIQUE_WORK_NAME_MANUAL_SYNC,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        navController.navigate("syncProgress")
    }

    // Unique work name (not an in-memory UUID) so this survives the process
    // being killed mid-sync — a fresh composition can re-find the same run.
    val syncWorkInfo = workManager
        .getWorkInfosForUniqueWorkFlow(SyncWorker.UNIQUE_WORK_NAME_MANUAL_SYNC)
        .collectAsState(initial = emptyList())
        .value
        .firstOrNull()
    LaunchedEffect(syncWorkInfo?.state) {
        when (syncWorkInfo?.state) {
            WorkInfo.State.SUCCEEDED -> {
                val label = if (lastSyncWasDryRun) "Dry-run sync result" else "Sync result"
                lastResult = "$label:\n\n${syncWorkInfo.outputData.getString(SyncWorker.KEY_RESULT)}"
                refreshInbox()
            }
            WorkInfo.State.FAILED -> {
                lastResult = "Sync failed:\n\n${syncWorkInfo.outputData.getString(SyncWorker.KEY_ERROR)}"
            }
            else -> {}
        }
    }

    // Mirrors the syncWorkInfo pattern above, applied to the "Sync now"
    // (watched-folder check + auto-sync) one-off work — Settings previously
    // had zero visual confirmation this ran at all, since enqueueOnce() only
    // fired a system notification on the (import > 0) path.
    val checkNowWorkInfos = workManager
        .getWorkInfosForUniqueWorkFlow(WatchFolderWorker.UNIQUE_WORK_NAME_ONCE)
        .collectAsState(initial = emptyList())
        .value
    val checkNowWorkInfo = checkNowWorkInfos.firstOrNull()
    val checkNowStatus = when (checkNowWorkInfo?.state) {
        WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> "Checking…"
        WorkInfo.State.SUCCEEDED ->
            checkNowWorkInfo.outputData.getString(WatchFolderWorker.KEY_RESULT_TEXT) ?: "Done"
        WorkInfo.State.FAILED -> "Check failed"
        else -> null
    }

    // Live progress for the chained SyncWorker that WatchFolderWorker enqueues
    // after an import — same unique work name whether it was triggered by
    // Settings' "Sync now" or the periodic background watcher, so this shows
    // up here whenever the app is open during either one, not just the
    // manual trigger. Reuses SyncWorker's existing setProgress() output
    // (KEY_PROGRESS_TEXT/KEY_PROGRESS_FRACTION) — the same contract
    // Home/SyncProgressScreen already read for a manual real-sync.
    val autoSyncWorkInfo = workManager
        .getWorkInfosForUniqueWorkFlow(WatchFolderWorker.UNIQUE_WORK_NAME_AUTO_SYNC)
        .collectAsState(initial = emptyList())
        .value
        .firstOrNull()
    val autoSyncProgressText = autoSyncWorkInfo?.progress?.getString(SyncWorker.KEY_PROGRESS_TEXT)
    val autoSyncProgressFraction = autoSyncWorkInfo?.progress
        ?.getFloat(SyncWorker.KEY_PROGRESS_FRACTION, -1f)
    val autoSyncResultText = when (autoSyncWorkInfo?.state) {
        WorkInfo.State.SUCCEEDED ->
            "Sync result:\n\n${autoSyncWorkInfo.outputData.getString(SyncWorker.KEY_RESULT)}"
        WorkInfo.State.FAILED ->
            "Sync failed:\n\n${autoSyncWorkInfo.outputData.getString(SyncWorker.KEY_ERROR)}"
        else -> null
    }
    // Without this, a watched-folder/scheduled auto-sync's outcome was
    // silently dropped — Home's "Last result" card only ever updated from
    // the manual-sync LaunchedEffect above, so a completed auto-sync left
    // the user staring at whatever was there before with no confirmation it
    // ever finished (or whether it succeeded).
    LaunchedEffect(autoSyncWorkInfo?.state) {
        if (autoSyncResultText != null) {
            lastResult = autoSyncResultText
            refreshInbox()
        }
    }
    val autoSyncRunning = autoSyncWorkInfo?.state == WorkInfo.State.RUNNING ||
        autoSyncWorkInfo?.state == WorkInfo.State.ENQUEUED
    val manualSyncRunning = syncWorkInfo?.state == WorkInfo.State.RUNNING ||
        syncWorkInfo?.state == WorkInfo.State.ENQUEUED
    val checkNowRunning = checkNowWorkInfo?.state == WorkInfo.State.RUNNING ||
        checkNowWorkInfo?.state == WorkInfo.State.ENQUEUED

    // One sync at a time, full stop — a manual Home sync, a watched-folder
    // check/auto-sync, and the periodic scheduled watcher all ultimately
    // push through the same shared Python SyncManager/state DB, so letting
    // two run concurrently risks interleaved writes to sync_runs. Both
    // Home's and Settings' sync buttons disable off this same flag, whichever
    // of the three is the one actually in flight.
    val anySyncRunning = manualSyncRunning || autoSyncRunning || checkNowRunning

    // Dedicated, always-present status row (this is a sync app — the real
    // estate is worth it) instead of burying "is a sync running right now"
    // inside whichever tab happens to be open. Priority: a manual Home sync
    // in flight, then a watched-folder/scheduled auto-sync, then that
    // auto-sync's own terminal result (so the footer doesn't get stuck
    // showing the import phase's stale "syncing to Gmail…" forever once the
    // chained sync actually finishes), then the watched-folder's own
    // import-phase status, else an idle placeholder.
    val syncStatusRunning = anySyncRunning
    val syncStatusText = when {
        manualSyncRunning ->
            syncWorkInfo?.progress?.getString(SyncWorker.KEY_PROGRESS_TEXT) ?: "Syncing…"
        autoSyncRunning -> autoSyncProgressText ?: "Syncing (watched folder)…"
        autoSyncResultText != null ->
            if (autoSyncWorkInfo?.state == WorkInfo.State.FAILED) "Watched-folder sync failed — tap for details"
            else "Watched-folder sync complete"
        checkNowStatus != null -> checkNowStatus
        else -> "No sync in progress"
    }
    val syncStatusFraction = when {
        manualSyncRunning ->
            syncWorkInfo?.progress?.getFloat(SyncWorker.KEY_PROGRESS_FRACTION, -1f)
        autoSyncRunning -> autoSyncProgressFraction
        else -> null
    }
    val onSyncStatusClick: () -> Unit = {
        when {
            manualSyncRunning -> navController.navigate("syncProgress")
            autoSyncRunning -> navController.navigate("settings")
            else -> navController.navigate("syncLog")
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in bottomDests.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                Column {
                    SyncStatusBar(
                        text = syncStatusText,
                        fraction = syncStatusFraction,
                        running = syncStatusRunning,
                        onClick = onSyncStatusClick,
                    )
                    NavigationBar {
                        bottomDests.forEach { dest ->
                            NavigationBarItem(
                                selected = currentRoute == dest.route,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") {
                // Home's inbox list is only otherwise refreshed right after an
                // import or a sync completes — if the app was relaunched or
                // navigated back to from another tab after a sync finished
                // (e.g. the process was killed mid-sync, losing the in-memory
                // WorkManager id), the list would keep showing already-synced
                // files. Re-check every time this screen is (re)entered.
                LaunchedEffect(Unit) { refreshInbox() }
                // Backend-neutral pair for HomeScreen's connection chip/Sync-now
                // gating — OAuth's connectedEmail/ready-token pair and IMAP's
                // saved-email/password-saved pair collapse to the same shape
                // rather than plumbing five OAuth-specific params through.
                val homeAccountLabel = if (mailBackend == AppPrefs.MAIL_BACKEND_IMAP) {
                    imapEmail.ifBlank { null }
                } else {
                    connectedEmail
                }
                val homeBackendReady = if (mailBackend == AppPrefs.MAIL_BACKEND_IMAP) {
                    imapPasswordSaved && imapHost.isNotBlank()
                } else {
                    connectedEmail != null
                }
                val homeConnectActionLabel = if (mailBackend == AppPrefs.MAIL_BACKEND_IMAP) {
                    if (homeBackendReady) "Change" else "Set up"
                } else {
                    if (homeBackendReady) "Reconnect" else "Connect"
                }
                HomeScreen(
                    accountLabel = homeAccountLabel,
                    backendReady = homeBackendReady,
                    connectActionLabel = homeConnectActionLabel,
                    connectError = connectError,
                    onConnect = {
                        if (mailBackend == AppPrefs.MAIL_BACKEND_IMAP) {
                            navController.navigate("settings")
                        } else {
                            connectGmail(silent = false)
                        }
                    },
                    inboxFiles = inboxFiles,
                    onImportPick = { pickFile.launch(arrayOf("*/*")) },
                    onPreview = { name ->
                        val path = WaMailApplication.inboxDir(context).resolve(name).absolutePath
                        Python.getInstance().getModule("src.android_api")
                            .callAttr("preview", path).toString()
                    },
                    onRemoveFile = { name -> removeInboxFile(name) },
                    chunkSize = chunkSize,
                    onChunkSizeChange = { chunkSize = it; AppPrefs.setChunkSize(context, it) },
                    dryRunDefault = dryRunDefault,
                    onDryRunDefaultChange = { dryRunDefault = it; AppPrefs.setDryRunDefault(context, it) },
                    onSyncNow = { if (dryRunDefault) runDryRunSync() else startRealSync() },
                    lastResult = lastResult,
                    syncInProgress = anySyncRunning,
                )
            }
            composable("chats") {
                ChatsListScreen(
                    onOpenChat = { chatId -> navController.navigate("chat/$chatId") },
                )
            }
            composable("chat/{chatId}") { entry ->
                val chatId = entry.arguments?.getString("chatId") ?: ""
                ChatDetailScreen(
                    chatId = chatId,
                    onBack = { navController.popBackStack() },
                    onDeleted = { navController.popBackStack() },
                    onSyncThisChat = { startRealSync(chatFilter = chatId) },
                    syncInProgress = anySyncRunning,
                )
            }
            composable("settings") {
                // Backend-neutral one-line status for the "Mail account" nav
                // row — SettingsScreen no longer receives the backend params
                // it would need to compute this itself now that the account
                // UI lives on its own screen.
                val mailAccountSummary = if (mailBackend == AppPrefs.MAIL_BACKEND_IMAP) {
                    if (imapPasswordSaved) imapEmail else "Not connected"
                } else {
                    connectedEmail ?: "Not connected"
                }
                SettingsScreen(
                    mailAccountSummary = mailAccountSummary,
                    onOpenMailAccount = { navController.navigate("mailAccount") },
                    onOpenHelp = { navController.navigate("help") },
                    onOpenSyncLog = { navController.navigate("syncLog") },
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    watchedFolderUri = watchedFolderUri,
                    onChooseFolder = { folderPicker.launch(null) },
                    onClearFolder = { clearWatchedFolder() },
                    autoWatchEnabled = autoWatchEnabled,
                    onAutoWatchChange = { setAutoWatch(it) },
                    watchIntervalMinutes = watchIntervalMinutes,
                    onWatchIntervalChange = { setWatchInterval(it) },
                    onCheckNow = { WatchFolderWorker.enqueueOnce(context) },
                    syncInProgress = anySyncRunning,
                    syncedFilePolicy = syncedFilePolicy,
                    onSyncedFilePolicyChange = { setSyncedFilePolicy(it) },
                )
            }
            composable("mailAccount") {
                MailAccountScreen(
                    onBack = { navController.popBackStack() },
                    connectedEmail = connectedEmail,
                    onDisconnect = ::disconnectGmail,
                    onReconnect = { connectGmail(silent = false) },
                    accessTokenAvailable = accessToken != null,
                    onTestConnection = { onResult ->
                        if (mailBackend == AppPrefs.MAIL_BACKEND_IMAP) {
                            if (!imapPasswordSaved) {
                                onResult("Save an IMAP app password first.")
                            } else {
                                Thread {
                                    var transport: com.chaquo.python.PyObject? = null
                                    val password = SecretStore.getSecret(context, AppPrefs.getImapPasswordSecretKey())
                                    val text = try {
                                        transport = Python.getInstance().getModule("src.mail_client")
                                            .callAttr(
                                                "build_imap_transport",
                                                AppPrefs.getImapHost(context),
                                                AppPrefs.getImapPort(context),
                                                AppPrefs.getImapEmail(context),
                                                password,
                                            )
                                        transport?.callAttr("labels_list").toString()
                                    } catch (e: Exception) {
                                        redactSecret("Error calling labels_list(): ${e.message}", password)
                                    } finally {
                                        try {
                                            transport?.callAttr("close")
                                        } catch (_: Exception) {
                                            // Best-effort logout only.
                                        }
                                    }
                                    android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(text) }
                                }.start()
                            }
                        } else {
                            val token = accessToken
                            if (token == null) {
                                onResult("Connect your mailbox first.")
                            } else {
                                Thread {
                                    fun labelsList(t: String) = Python.getInstance()
                                        .getModule("src.mail_client")
                                        .callAttr("set_token", t)
                                        .callAttr("labels_list").toString()
                                    var refreshedToken: String? = null
                                    val text = try {
                                        labelsList(token)
                                    } catch (e: Exception) {
                                        if (e.message?.contains("401") == true) {
                                            val fresh = refreshStaleToken(context as android.app.Activity, token)
                                            if (fresh != null) {
                                                refreshedToken = fresh
                                                try {
                                                    labelsList(fresh)
                                                } catch (e2: Exception) {
                                                    "Error calling labels_list() after refreshing token: ${e2.message}"
                                                }
                                            } else {
                                                "Error calling labels_list(): ${e.message} (token refresh also failed)"
                                            }
                                        } else {
                                            "Error calling labels_list(): ${e.message}"
                                        }
                                    }
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        refreshedToken?.let { accessToken = it }
                                        onResult(text)
                                    }
                                }.start()
                            }
                        }
                    },
                    mailBackend = mailBackend,
                    onMailBackendChange = ::onMailBackendChange,
                    imapProviders = imapProviders,
                    imapProvider = imapProvider,
                    onImapProviderChange = ::onImapProviderChange,
                    imapHost = imapHost,
                    onImapHostChange = { imapHost = it },
                    imapPort = imapPort,
                    onImapPortChange = { imapPort = it },
                    imapEmail = imapEmail,
                    onImapEmailChange = { imapEmail = it },
                    imapPasswordSaved = imapPasswordSaved,
                    onSaveImapSettings = ::saveImapSettings,
                    onForgetImapPassword = ::forgetImapPassword,
                )
            }
            composable("help") {
                HelpScreen(onBack = { navController.popBackStack() })
            }
            composable("syncLog") {
                SyncLogScreen(onBack = { navController.popBackStack() })
            }
            composable("syncProgress") {
                SyncProgressScreen(
                    workManager = workManager,
                    onDone = { navController.popBackStack("home", inclusive = false) },
                )
            }
        }
    }
}
