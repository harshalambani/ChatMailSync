package com.wagmailsync.app

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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
// access token, not account identity (see Phase A3 plan).
private val GMAIL_SCOPES = listOf(
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
            WagmailTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WagmailApp(
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

@Composable
fun WagmailApp(
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
    }

    // "Token survives app restart": silently re-check on screen load.
    LaunchedEffect(Unit) { connectGmail(silent = true) }

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
    var chunkSize by remember { mutableStateOf("day") }
    var dryRunDefault by remember { mutableStateOf(false) }

    // ---- Real sync via SyncWorker (Phase A4) ---------------------------
    val workManager = remember { WorkManager.getInstance(context) }
    var syncWorkId by remember { mutableStateOf<UUID?>(null) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* Sync still runs as a foreground service either way; a denied
          notification permission only means the user won't see progress. */ }

    fun startRealSync() {
        if (connectedEmail == null) {
            lastResult = "Connect Gmail first."
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
                    lastResult = "Connect Gmail first."
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
                syncWorkId = request.id
                workManager.enqueue(request)
                navController.navigate("syncProgress")
            }
            .addOnFailureListener { e ->
                lastResult = "Could not refresh Gmail connection: ${e.message}"
            }
    }

    fun runDryRunSync() {
        val stats = Python.getInstance()
            .getModule("src.android_api")
            .callAttr("sync", null, chunkSize, true, null, null)
        lastResult = "Dry-run sync result:\n\n${formatSyncStats(stats)}"
        refreshInbox()
    }

    val syncWorkInfo = syncWorkId?.let { id ->
        workManager.getWorkInfoByIdFlow(id).collectAsState(initial = null).value
    }
    LaunchedEffect(syncWorkInfo?.state) {
        when (syncWorkInfo?.state) {
            WorkInfo.State.SUCCEEDED -> {
                lastResult = "Sync result:\n\n${syncWorkInfo.outputData.getString(SyncWorker.KEY_RESULT)}"
                refreshInbox()
            }
            WorkInfo.State.FAILED -> {
                lastResult = "Sync failed:\n\n${syncWorkInfo.outputData.getString(SyncWorker.KEY_ERROR)}"
            }
            else -> {}
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in bottomDests.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
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
                HomeScreen(
                    connectedEmail = connectedEmail,
                    connectError = connectError,
                    onConnect = { connectGmail(silent = false) },
                    inboxFiles = inboxFiles,
                    onImportPick = { pickFile.launch(arrayOf("*/*")) },
                    onPreview = { name ->
                        val path = WagmailApplication.inboxDir(context).resolve(name).absolutePath
                        Python.getInstance().getModule("src.android_api")
                            .callAttr("preview", path).toString()
                    },
                    onRemoveFile = { name -> removeInboxFile(name) },
                    chunkSize = chunkSize,
                    onChunkSizeChange = { chunkSize = it },
                    dryRunDefault = dryRunDefault,
                    onDryRunDefaultChange = { dryRunDefault = it },
                    onSyncNow = { if (dryRunDefault) runDryRunSync() else startRealSync() },
                    lastResult = lastResult,
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
                )
            }
            composable("settings") {
                SettingsScreen(
                    connectedEmail = connectedEmail,
                    onDisconnect = ::disconnectGmail,
                    onReconnect = { connectGmail(silent = false) },
                    onOpenHelp = { navController.navigate("help") },
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
                    syncedFilePolicy = syncedFilePolicy,
                    onSyncedFilePolicyChange = { setSyncedFilePolicy(it) },
                    accessTokenAvailable = accessToken != null,
                    onTestConnection = { onResult ->
                        val token = accessToken
                        if (token == null) {
                            onResult("Connect Gmail first.")
                        } else {
                            Thread {
                                fun labelsList(t: String) = Python.getInstance()
                                    .getModule("src.gmail_client")
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
                    },
                )
            }
            composable("help") {
                HelpScreen(onBack = { navController.popBackStack() })
            }
            composable("syncProgress") {
                SyncProgressScreen(
                    workManager = workManager,
                    workId = syncWorkId,
                    onDone = { navController.popBackStack("home", inclusive = false) },
                )
            }
        }
    }
}
