package com.chatmailsync.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavDestination.Companion.hierarchy
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
import java.util.UUID

class MainActivity : ComponentActivity() {

    private var onImported: ((Uri) -> Unit)? = null

    // A share that arrives on a cold start has nowhere to go yet. setContent
    // does not compose in onCreate -- the composition is created when the
    // view attaches to the window, which is after onCreate returns -- so the
    // callback the UI registers does not exist when handleIncomingIntent runs
    // and the Uri was simply dropped. Sharing an export from WhatsApp opened
    // the app and did nothing whatsoever. Park it until someone can take it.
    private var pendingImport: Uri? = null

    /** Hands over a share that arrived before the UI was listening, once. */
    fun takePendingImport(): Uri? = pendingImport.also { pendingImport = null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var themeMode by remember { mutableStateOf(AppPrefs.getThemeMode(this)) }
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            ChatMailTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatMailApp(
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
        val handler = onImported
        if (handler == null) pendingImport = uri else handler(uri)
    }
}

private data class BottomDest(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val bottomDests = listOf(
    BottomDest("home", "Home", Icons.Filled.Home),
    BottomDest("chats", "Chats", Icons.Filled.List),
    BottomDest("settings", "Settings", Icons.Filled.Settings),
)

/** Which tab a screen belongs under. The tabs are shown on every destination,
 * not just the three top-level ones -- a sub-screen used to hide them, so from
 * Mail account (where you land after entering credentials) there was no Home
 * button at all and the labelled back arrow was the only way out. Sub-screens
 * still light up the tab they were opened from, so the bar says where you are
 * rather than going blank.
 *
 * The exception is the sync log, which has three doors and so belongs to no
 * one tab -- see below. */
internal fun tabForRoute(route: String?): String? = when {
    route == null -> null
    // Its own full-window flow, not a tab -- same reasoning as the sync log
    // just below.
    route == "first_run" -> null
    route == "home" || route == "syncProgress" || route == "importPicker" -> "home"
    route == "chats" || route.startsWith("chat/") -> "chats"
    route == "settings" || route == "mailAccount" || route == "help" || route == "advancedSettings" -> "settings"
    // The sync log is reachable from Settings, from the status card on Home,
    // and from the always-visible sync bar on every screen there is. Lighting
    // Settings told two thirds of its visitors they were somewhere they had
    // never been. No tab lit is honest; the wrong tab lit is not.
    route.startsWith("syncLog") -> null
    else -> null
}

/**
 * Whether to scan the watched folder once at startup.
 *
 * Exactly the condition the periodic work is enqueued under, and that is the
 * point: launch is not a licence to scan a folder someone has switched the
 * watcher off for. "Check now" remains the way to force one.
 */
internal fun shouldScanAtLaunch(autoWatchOn: Boolean, watchedFolderUri: String?): Boolean =
    autoWatchOn && !watchedFolderUri.isNullOrBlank()

/**
 * Whether the four-step first-run setup (D7) is what a launch should open
 * on, instead of Home.
 *
 * Both a fresh install (neither flag) and an upgrader who already has a
 * working mailbox from before this release existed (mailbox configured, flag
 * never set) must land on Home -- the second clause is what keeps the
 * latter out of a "welcome" screen for an app they have used for months.
 * MainActivity sets the flag for that case on launch (see hasMailAccount's
 * use below) so it only has to be inferred once.
 */
internal fun shouldShowFirstRun(firstRunDone: Boolean, mailboxConfigured: Boolean): Boolean =
    !firstRunDone && !mailboxConfigured

/**
 * The word beside a labelled back arrow, derived from the actual previous
 * back-stack entry's route rather than hard-coded per screen.
 *
 * Several screens (Me, Mail account -- reachable from the connection pill on
 * every masthead, not just Settings) can be opened from more than one place,
 * and a fixed "Home" or "Settings" string was right for only some of those
 * paths and silently wrong for the rest. Back itself already uses
 * `popBackStack()` everywhere (so it always lands where you actually came
 * from); this is only the label that names that destination.
 *
 * Deliberately narrow: unknown and "first_run" both fall through to plain
 * "Back" rather than guessing, and null (no previous entry -- e.g. this
 * route is the graph's start destination) does too.
 */
internal fun backLabelForRoute(route: String?): String = when {
    route == "home" -> "Home"
    route == "settings" -> "Settings"
    route == "advancedSettings" -> "Advanced"
    route == "chats" || route?.startsWith("chat/") == true -> "Chats"
    route == "help" -> "Help"
    route == "privacy" -> "Privacy"
    route == "mailAccount" -> "Mail account"
    route == "me" -> "Me"
    route == "syncLog" || route?.startsWith("syncLog/") == true -> "Sync log"
    route == "queue" -> "Queue"
    route == "importPicker" -> "Import"
    route == "mailWizard" -> "Mail setup"
    else -> "Back"
}

/**
 * Where the four-step first-run flow (D7) returns to when it finishes or is
 * skipped, given the route it was entered from.
 *
 * Two distinct launches share this same "first_run" route: a fresh install,
 * where the flow is the nav graph's start destination and so has no previous
 * back-stack entry (`cameFrom == null`), and "Run setup again" from Advanced
 * settings (H7), which pushes "first_run" on top of "advancedSettings" and
 * so has one. The fresh-install case must always land on Home; the manual
 * case must always return to Advanced -- neither may leak into the other's
 * outcome, which is exactly what a shared boolean flag or a single default
 * target would risk. Kept pure and read once, at the moment "first_run" is
 * entered, rather than re-derived after the flow has popped itself off the
 * stack.
 */
internal fun firstRunFinishTarget(cameFrom: String?): String =
    if (cameFrom == "advancedSettings") "advancedSettings" else "home"

/** Where the bottom-bar tab handler (and the incoming-share handler) pop to
 * when resetting/returning to Home. This is deliberately NOT
 * `navController.graph.findStartDestination()`: for a fresh install the
 * graph's start destination is "first_run", and that stays true for the rest
 * of the process even after the flow finishes and pops itself off the back
 * stack (a NavGraph's start destination is fixed at graph-build time, not
 * recomputed as the back stack changes). Popping to a route that is no
 * longer on the stack is a silent no-op, so a literal "home" is required
 * here, not the graph's start destination. See FirstRunNavTest. */
internal fun tabPopTargetRoute(): String = "home"

/** The collapsed sync bar — this is a sync app, so "is anything syncing right
 * now" deserves dedicated, permanent real estate rather than being buried in
 * whichever tab happens to be open. Shows live progress while a sync (manual
 * or watched-folder/scheduled) is running, and the last known outcome
 * otherwise; tapping jumps to the most relevant detail screen for whatever
 * it's currently showing.
 *
 * A running sync keeps this bar on screen everywhere, not just on the three
 * tabs (see showSyncBar below) — leaving the progress screen to look
 * something up used to mean losing the run entirely until you found your way
 * back, which is half of what "the progress bar functionality is not the same
 * as the android app" was about. The bar is the way back: it says what is
 * happening, how far along, and reopens the full view on tap.
 *
 * The text and the fraction are rendered by src/progress.py and carried here
 * verbatim, so this bar, the full progress screen and the notification all
 * say the same words. */
@Composable
private fun SyncStatusBar(
    text: String,
    fraction: Float?,
    percent: Int?,
    running: Boolean,
    detached: Boolean,
    onClick: () -> Unit,
) {
    Column {
        // Off the tabs the bar floats directly on top of a scrolling screen,
        // so it needs its own edge; above the nav bar that edge would just be
        // a second line next to the nav bar's own.
        if (detached) HorizontalDivider()
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Only when there is a real number behind it: a run that is
                // all dedup-skips never earns a percentage, and showing "0%"
                // for the whole of it would be a worse lie than showing none.
                if (running && percent != null && percent >= 0) {
                    Text(
                        "$percent%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
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
}

@Composable
fun ChatMailApp(
    registerImportCallback: ((Uri) -> Unit) -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // ---- Legacy Google sign-in notice (v2.0.0) ----------------------
    // Google sign-in was removed in v2.0.0. Someone who had it gets one
    // explanation, once -- see AppPrefs.isLegacyOauthUser.
    var showOauthRemovedNotice by remember {
        mutableStateOf(
            AppPrefs.isLegacyOauthUser(context) &&
                !AppPrefs.wasOauthRemovedNoticeShown(context)
        )
    }
    if (showOauthRemovedNotice) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Google sign-in has been removed") },
            text = {
                Text(
                    "This app used to offer signing in with Google. That option " +
                        "is gone from version 2.0.0 onwards: its Google sign-in " +
                        "was never verified by Google, so consent expired every " +
                        "7 days and only 100 listed accounts could use it at " +
                        "all.\n\nNothing already archived is affected - your " +
                        "chats stay in your mailbox exactly as they are.\n\nTo " +
                        "keep syncing, open Settings > Mail account and connect " +
                        "with an app password instead."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppPrefs.setOauthRemovedNoticeShown(context, true)
                    AppPrefs.setConnectedAccountEmail(context, null)
                    showOauthRemovedNotice = false
                }) { Text("OK") }
            },
        )
    }

    // ---- Mail backend: IMAP app password -------------------------------
    // mailBackend/imapProvider/imapHost/imapPort/imapEmail mirror AppPrefs
    // (persisted there), but only *after* a successful Save & connect — see
    // saveImapSettings below, which follows a "validate, build transport,
    // force a real login via labels_list(), persist only on success"
    // contract. imapPasswordSaved never reflects the password itself, only
    // whether SecretStore currently holds one.
    var mailBackend by remember { mutableStateOf(AppPrefs.resolveMailBackend(context)) }
    var imapProvider by remember { mutableStateOf(AppPrefs.getImapProvider(context)) }
    var imapHost by remember { mutableStateOf(AppPrefs.getImapHost(context)) }
    var imapPort by remember { mutableStateOf(AppPrefs.getImapPort(context)) }
    var imapEmail by remember { mutableStateOf(AppPrefs.getImapEmail(context)) }
    var imapPasswordSaved by remember { mutableStateOf(AppPrefs.hasImapPassword(context)) }
    var imapProviders by remember { mutableStateOf(listOf<ImapProviderInfo>()) }
    // The five connection stages, in order, so the wizard's last step can draw
    // the whole list greyed out before the check starts rather than growing it
    // a line at a time. Read from the Python core rather than hardcoded here,
    // so Kotlin never drifts from the source of truth.
    var stagePlan by remember { mutableStateOf(listOf<WizardStage>()) }

    // Reads config.IMAP_PROVIDERS via the Python side once per composition —
    // the shared preset table (host/port per provider), so Android never
    // duplicates that data in Kotlin.
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
        // A settings file written by a release that offered a provider this
        // one has retired still names it, and the picker would otherwise show
        // the raw key with the host field locked. Ask the Python side what
        // replaced it (config.RETIRED_IMAP_PROVIDERS), and save the answer so
        // the two sides stop disagreeing from here on. A key that was never
        // ours comes back empty and is left alone -- guessing at it would put
        // some other provider's host in front of the user without saying so.
        // The saved host is never touched either: only the user knows what it
        // should be.
        val landing = Python.getInstance().getModule("src.config")
            .callAttr("retired_provider_landing", imapProvider).toString()
        if (landing.isNotEmpty() && landing != imapProvider) {
            imapProvider = landing
            AppPrefs.setImapProvider(context, landing)
        }
        stagePlan = Python.getInstance().getModule("src.mail_client")
            .callAttr("connection_stage_plan").asList().map { entry ->
                WizardStage(
                    name = entry.callAttr("get", "name").toString(),
                    label = entry.callAttr("get", "label").toString(),
                )
            }
    }

    // Whether there is an account to speak of at all: with IMAP the only
    // backend, that is exactly "a password is in the keystore".
    val hasMailAccount = imapPasswordSaved

    // The banner's status dot. Recomputed whenever the account situation
    // changes; the pass/fail half of it is written by the two places that
    // actually try the mailbox (Save & connect, Test connection).
    LaunchedEffect(hasMailAccount) { ConnectionState.refresh(context, hasMailAccount) }

    // First-run setup (D7). Read once, before startDestination below is
    // computed, so a mid-flow process death reopens on first_run rather than
    // skipping back to Home — the flag is only ever written once the flow is
    // actually finished (Set up later / Turn on / Not now).
    var firstRunDone by remember { mutableStateOf(AppPrefs.isFirstRunDone(context)) }
    // An upgrader who already has a working mailbox from before this release
    // existed must never see the welcome screen — back-fill the flag for
    // them on launch so a later reinstall-free re-check (e.g. after
    // forgetting the password) doesn't retroactively show it either.
    LaunchedEffect(Unit) {
        if (!firstRunDone && hasMailAccount) {
            AppPrefs.setFirstRunDone(context, true)
            firstRunDone = true
        }
    }
    // Computed once, from the values read at launch, not the possibly-just-
    // corrected firstRunDone above — for an upgrader hasMailAccount is
    // already true at this point, so shouldShowFirstRun is false either way
    // and the LaunchedEffect above and this val never disagree.
    val firstRunStartDestination = remember {
        if (shouldShowFirstRun(AppPrefs.isFirstRunDone(context), hasMailAccount)) "first_run" else "home"
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
        // Normalized here, at the point the freshly typed password is used --
        // not as the user types -- so a copy-pasted Gmail app password
        // ("abcd efgh ijkl mnop") works whether or not the spaces came along.
        val normalizedInput = normalizeAppPassword(provider, password)
        val effectivePassword = normalizedInput.ifBlank { existingPassword ?: "" }
        if (effectivePassword.isBlank()) {
            onResult(false, "Enter the app password to connect with.")
            return
        }
        Thread {
            var transport: com.chaquo.python.PyObject? = null
            val errorText = try {
                transport = Python.getInstance().getModule("src.mail_client")
                    .callAttr("build_imap_transport", effectiveHost, port, email, effectivePassword)
                // Forces a real login so a wrong host/port/password/
                // app-password is caught here, not on the next real sync.
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
                // A real login was attempted either way by this point -- the
                // argument checks above return before the thread starts -- so
                // this outcome is worth recording whichever way it went.
                ConnectionState.record(context, errorText == null)
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

    /**
     * The wizard's version of saveImapSettings: the same "only persist after a
     * real login" contract, but run through check_connection so the five stages
     * can be reported to the caller as they finish.
     *
     * Separate from saveImapSettings rather than a flag on it, because the two
     * differ in more than progress: this one always has a freshly typed
     * password (the wizard has no "leave blank to keep the saved one" rule),
     * and it reports failure in check_connection's words -- which name the
     * stage that broke -- instead of a bare exception message.
     */
    fun connectWithStages(
        provider: String,
        host: String,
        port: Int,
        email: String,
        password: String,
        listener: StageListener,
        onResult: (Boolean, String) -> Unit,
    ) {
        val effectiveHost = if (provider == "custom") host else
            (imapProviders.firstOrNull { it.key == provider }?.host?.takeIf { it.isNotBlank() } ?: host)
        if (effectiveHost.isBlank()) {
            onResult(false, "Enter a host for a custom IMAP server.")
            return
        }
        // Normalized here, at the point the freshly typed password is used --
        // not as the user types -- so a copy-pasted Gmail app password
        // ("abcd efgh ijkl mnop") works whether or not the spaces came along.
        val normalizedPassword = normalizeAppPassword(provider, password)
        if (email.isBlank() || normalizedPassword.isBlank()) {
            onResult(false, "Enter the email address and app password to connect with.")
            return
        }
        Thread {
            var connected = false
            // check_connection reports a connection problem as a return value,
            // not an exception, so this catch is only for a bridge-level fault.
            val text = try {
                val mailClient = Python.getInstance().getModule("src.mail_client")
                val outcome = mailClient.callAttr(
                    "check_connection",
                    effectiveHost,
                    port,
                    email,
                    normalizedPassword,
                    listener,
                )
                connected = outcome.callAttr("get", "ok").toBoolean()
                mailClient.callAttr("format_connection_result", outcome).toString()
            } catch (e: Exception) {
                redactSecret("Could not connect: ${e.message ?: "unknown error"}", normalizedPassword)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ConnectionState.record(context, connected)
                if (connected) {
                    // The wizard only ever sets up IMAP (D2), so finishing it
                    // is also the moment the account becomes an IMAP one --
                    // otherwise someone who ran it while still on the old Gmail
                    // path would end up with a saved password nothing uses.
                    AppPrefs.setMailBackend(context, AppPrefs.MAIL_BACKEND_IMAP)
                    mailBackend = AppPrefs.MAIL_BACKEND_IMAP
                    AppPrefs.setImapProvider(context, provider)
                    AppPrefs.setImapHost(context, effectiveHost)
                    AppPrefs.setImapPort(context, port)
                    AppPrefs.setImapEmail(context, email)
                    SecretStore.putSecret(context, AppPrefs.getImapPasswordSecretKey(), normalizedPassword)
                    imapProvider = provider
                    imapHost = effectiveHost
                    imapPort = port
                    imapEmail = email
                    imapPasswordSaved = true
                }
                onResult(connected, text)
            }
        }.start()
    }

    fun forgetImapPassword() {
        AppPrefs.clearImapSettings(context)
        // The verdict was about the credentials just thrown away.
        ConnectionState.forget(context)
        imapPasswordSaved = false
        imapProvider = "gmail"
        imapHost = ""
        imapPort = 993
        imapEmail = ""
    }

    /**
     * Shared by both places "Test connection" is offered (Mail account, and
     * Advanced settings' Mail server section) so there is exactly one copy of
     * the five-stage check and its result formatting to keep in sync.
     */
    fun testImapConnection(onResult: (String) -> Unit) {
        if (!imapPasswordSaved) {
            onResult("Save an IMAP app password first.")
            return
        }
        Thread {
            val password = SecretStore.getSecret(context, AppPrefs.getImapPasswordSecretKey())
            var connected = false
            val text = try {
                val mailClient = Python.getInstance().getModule("src.mail_client")
                val outcome = mailClient.callAttr(
                    "check_connection",
                    AppPrefs.getImapHost(context),
                    AppPrefs.getImapPort(context),
                    AppPrefs.getImapEmail(context),
                    password,
                )
                connected = outcome.callAttr("get", "ok").toBoolean()
                mailClient.callAttr("format_connection_result", outcome).toString()
            } catch (e: Exception) {
                redactSecret("Could not connect: ${e.message}", password)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ConnectionState.record(context, connected)
                onResult(text)
            }
        }.start()
    }

    // ---- Which name in an export is yours -----------------------------
    // The app works this out from a one-to-one export and then uses it to
    // decide which side of the conversation every bubble is drawn on. That is
    // too consequential to leave unsaid, so Settings states it. Held in the
    // shared state database, not in preferences, because every part of the
    // app that touches an export needs to reach the same answer -- a
    // disagreement would archive one export two different ways.
    var selfSenderDetail by remember { mutableStateOf("") }
    var selfSenderOverride by remember { mutableStateOf("") }
    // The masthead Me row's own inputs: the raw source ("learned" /
    // "unknown" / "override") and the resolved name, fed through
    // selfSenderDisplay() rather than the detail text above, which is worded
    // for the Me screen, not a 48dp row.
    var selfSenderSource by remember { mutableStateOf<String?>(null) }
    var selfSenderName by remember { mutableStateOf<String?>(null) }

    fun applySelfSender(described: com.chaquo.python.PyObject) {
        selfSenderDetail = described.callAttr("get", "detail").toString()
        // The stored override, deliberately not the resolved name: filling the
        // box with a name the app worked out would turn it into an override
        // the moment anything else on the screen was touched.
        val override = described.callAttr("get", "override")
        selfSenderOverride = if (override == null) "" else override.toString()
        val source = described.callAttr("get", "source")
        selfSenderSource = source?.toString()
        val name = described.callAttr("get", "name")
        selfSenderName = name?.toString()
    }

    fun refreshSelfSender() {
        applySelfSender(
            Python.getInstance().getModule("src.android_api").callAttr("get_self_sender")
        )
    }

    // The one-time "the app worked out who you are" Home card. A separate
    // piece of state from selfSenderName/selfSenderSource above: those are
    // the masthead's ongoing answer to "who is me", re-read on every screen
    // entry, while this is a single announcement that must survive exactly
    // until the user dismisses it and never come back on its own.
    var pendingSelfSenderBannerName by remember { mutableStateOf<String?>(null) }

    fun refreshSelfSenderBanner() {
        val name = Python.getInstance().getModule("src.android_api")
            .callAttr("get_pending_self_sender_banner")
        pendingSelfSenderBannerName = name?.toString()
    }

    fun dismissSelfSenderBanner() {
        Python.getInstance().getModule("src.android_api").callAttr("clear_self_sender_banner")
        pendingSelfSenderBannerName = null
    }

    fun setSelfSender(name: String) {
        applySelfSender(
            Python.getInstance().getModule("src.android_api")
                .callAttr("set_self_sender", name)
        )
    }

    // Every sender name ever seen across every export, most active first --
    // the Me screen's pick list. android_api.list_chat_senders() returns one
    // row per (chat_id, sender), because that is how the counts are stored;
    // collapsed here to one row per name, summed across chats, since the
    // picker is choosing a name to be, not a chat to be it in.
    fun listChatSenders(): List<String> {
        val rows = Python.getInstance().getModule("src.android_api")
            .callAttr("list_chat_senders").asList()
        val counts = LinkedHashMap<String, Long>()
        for (row in rows) {
            val sender = row.callAttr("get", "sender").toString()
            val count = row.callAttr("get", "msg_count").toString().toLongOrNull() ?: 0L
            counts[sender] = (counts[sender] ?: 0L) + count
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key }
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
        // preview() returns a dict; interpolating it printed the repr --
        // "{'ok': True, 'display_name': ..., 'media_count': 15, ...}" -- straight
        // into the user-facing result panel. preview_text() is the same call with
        // format_preview() applied, and is what every other call site already uses.
        val preview = Python.getInstance()
            .getModule("src.android_api")
            .callAttr("preview_text", outcome.file.absolutePath, AppPrefs.getCutoffDate(context))
        lastResult = "Imported ${outcome.file.name}\n\n$preview"
    }

    /** A shared export lands in the queue on Home, and the panel that says so
     * is on Home -- arriving from WhatsApp onto whichever screen was last open
     * is indistinguishable from nothing having happened, which is how it was
     * reported. Go where the result is. */
    fun receiveSharedExport(uri: Uri) {
        importAndPreview(uri)
        // Step 3 of first-run exists to greet exactly this arrival in place —
        // jumping to Home the moment a share lands would cut the walkthrough
        // short and land the user somewhere it never explained. Everywhere
        // else, go where the result is, same as before; inboxFiles (read by
        // both Home and first-run step 3) is already refreshed above.
        if (navController.currentDestination?.route == "first_run") return
        navController.navigate("home") {
            popUpTo(tabPopTargetRoute()) { saveState = true }
            launchSingleTop = true
        }
    }

    registerImportCallback { uri -> receiveSharedExport(uri) }

    // Collect a share that reached the activity before this callback existed.
    // In the composition, not in onCreate: importAndPreview writes state.
    LaunchedEffect(Unit) {
        (context as? MainActivity)?.takePendingImport()?.let { receiveSharedExport(it) }
    }

    /**
     * Import a whole selection at once.
     *
     * A per-file preview is the right answer for one file and the wrong one
     * for six: run through [importAndPreview] and the last file silently
     * overwrites everyone else's result, so a run where half the selection was
     * already queued reads as a clean success. One file still gets its
     * preview; a selection gets counted honestly instead.
     */
    fun importMany(uris: List<Uri>) {
        if (uris.size == 1) {
            importAndPreview(uris.first())
            return
        }
        var imported = 0
        var alreadyQueued = 0
        var failed = 0
        uris.forEach { uri ->
            val outcome = ImportManager.importUri(context, uri)
            when {
                outcome == null -> failed++
                outcome.alreadyQueued -> alreadyQueued++
                else -> imported++
            }
        }
        refreshInbox()
        lastResult = listOfNotNull(
            "Imported ${plural(imported, "file")}",
            "$alreadyQueued already queued".takeIf { alreadyQueued > 0 },
            "${plural(failed, "file")} could not be read".takeIf { failed > 0 },
        ).joinToString(" · ")
    }

    // OpenMultipleDocuments (not OpenDocument): the single-select contract
    // was the "only 1 file at a time" bug reported from Home testing.
    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> uris.forEach { importAndPreview(it) } }

    // ---- Backup & restore (P3) -----------------------------------------
    //
    // Two launchers rather than one picker with a mode: SAF has two contracts,
    // and conflating them means asking someone to "choose" a file that does not
    // exist yet. The work itself is off the main thread -- it opens SQLite
    // databases and walks every row of the ledger -- on the same Thread/Handler
    // pattern the rest of this file uses for Python calls.
    var migrationBusy by remember { mutableStateOf(false) }
    var migrationStatus by remember { mutableStateOf<String?>(null) }

    val saveBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(Migration.MIME_TYPE),
    ) { uri ->
        if (uri != null) {
            migrationBusy = true
            migrationStatus = "Saving..."
            Thread {
                val message = Migration.exportTo(context, uri)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    migrationStatus = message
                    migrationBusy = false
                }
            }.start()
        }
    }

    val restoreBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            migrationBusy = true
            migrationStatus = "Reading..."
            Thread {
                // Described before it is merged, so the line the user reads
                // names the backup they picked and not only the outcome --
                // picking the wrong file out of a folder of them is the likely
                // mistake here, and the merge itself says nothing about which
                // file it was.
                val described = Migration.describe(context, uri)
                if (described != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        migrationStatus = "$described - restoring..."
                    }
                }
                val message = Migration.importFrom(context, uri)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    migrationStatus = message
                    migrationBusy = false
                    refreshInbox()
                }
            }.start()
        }
    }

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

    // One scan at launch. The periodic worker's first run is up to a whole
    // interval away -- WorkManager's floor alone makes that 15 minutes -- so
    // a file dropped into the watched folder while the app was closed sat
    // there unnoticed with the app open and idle in front of it.
    //
    // LaunchedEffect(Unit): once per composition of this screen, which is
    // once per process. ON_RESUME would re-run it every time the user came
    // back from another app, which is a scan nobody asked for.
    LaunchedEffect(Unit) {
        if (shouldScanAtLaunch(autoWatchEnabled, watchedFolderUri)) {
            WatchFolderWorker.enqueueOnce(context)
        }
    }

    // ---- Background health (Batch E) -----------------------------------
    // Both facts behind this live outside the app, on system screens the user
    // leaves us to visit, so the only reliable moment to re-read them is coming
    // back: ON_RESUME. Keyed on autoWatchEnabled as well, because turning the
    // toggle on is the other moment the answer changes without anyone leaving
    // the app -- and the effect body recomputes on subscribe, so that case is
    // covered by the re-keying rather than by a second effect.
    //
    // A lifecycle observer rather than LifecycleEventEffect: that lives in
    // lifecycle-runtime-compose, which this module does not depend on, and one
    // DisposableEffect is not worth a new dependency.
    val lifecycleOwner = LocalLifecycleOwner.current
    var backgroundIssues by remember { mutableStateOf(emptyList<BackgroundIssue>()) }
    DisposableEffect(lifecycleOwner, autoWatchEnabled) {
        fun recompute() {
            backgroundIssues = backgroundHealthIssues(
                autoWatchOn = autoWatchEnabled,
                batteryExempt = isBatteryExempt(context),
                notificationsAllowed = notificationsAllowed(context),
            )
        }
        recompute()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) recompute()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A name the app works out during a background sync only lands in
    // selfSenderSource/selfSenderName on the next refreshSelfSender() call --
    // without this, the masthead Me row and Settings' summary row kept
    // showing a stale answer until Settings happened to be opened. Same
    // ON_RESUME pattern as the health-issue effect just above, kept as its
    // own DisposableEffect rather than folded into that one since the two
    // recompute unrelated state.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshSelfSender()
                refreshSelfSenderBanner()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ---- Sync defaults (Phase A5 Home sync controls) -------------------
    // Persisted (AppPrefs), not remember-only — previously reset to "day"/
    // false on every process death, and WatchFolderWorker's auto-sync
    // couldn't see the user's choice at all since it runs in a separate
    // process-less Worker with no access to this Compose state.
    var chunkSize by remember { mutableStateOf(AppPrefs.getChunkSize(context)) }
    var cutoffDate by remember { mutableStateOf(AppPrefs.getCutoffDate(context)) }
    var dryRunDefault by remember { mutableStateOf(AppPrefs.isDryRunDefault(context)) }

    // ---- Real sync via SyncWorker (Phase A4) ---------------------------
    val workManager = remember { WorkManager.getInstance(context) }
    var lastSyncWasDryRun by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* Sync still runs as a foreground service either way; a denied
          notification permission only means the user won't see progress. */ }

    fun startRealSync(chatFilter: String? = null) {
        // SyncWorker reads host/email from AppPrefs and the password from
        // SecretStore itself, same as the watched-folder auto-sync path.
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
                // "Test run" everywhere else on both platforms; "Dry-run" was the one
                // place the developer word leaked into the user-facing panel.
                val label = if (lastSyncWasDryRun) "Test run result" else "Sync result"
                lastResult = "$label:\n\n${syncWorkInfo.outputData.getString(SyncWorker.KEY_RESULT)}"
                refreshInbox()
                // A one-to-one export just synced may be exactly what taught
                // the app the owner's name -- same reasoning as
                // refreshSelfSender() just below, but for the one-time
                // banner rather than the ongoing masthead answer.
                refreshSelfSenderBanner()
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
    val autoSyncProgressPercent = autoSyncWorkInfo?.progress
        ?.getInt(SyncWorker.KEY_PROGRESS_PERCENT, -1)
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
            refreshSelfSenderBanner()
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
    // showing the import phase's stale "syncing to your mailbox…" forever once the
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
    val syncStatusPercent = when {
        manualSyncRunning ->
            syncWorkInfo?.progress?.getInt(SyncWorker.KEY_PROGRESS_PERCENT, -1)
        autoSyncRunning -> autoSyncProgressPercent
        else -> null
    }
    val onSyncStatusClick: () -> Unit = {
        when {
            manualSyncRunning -> navController.navigate("syncProgress")
            // A watched-folder sync has no progress screen of its own:
            // SyncProgressScreen observes the manual unique work only, so it
            // would render empty here. Settings was the old stand-in, and it
            // is neither where the tap was nor about the sync. The log is the
            // one screen with something to say about this run.
            autoSyncRunning -> navController.navigate("syncLog")
            else -> navController.navigate("syncLog")
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // Every screen keeps the tabs. The one exception is the full-screen sync
    // progress view, which is a modal moment with its own way out.
    val showBottomBar = currentRoute != "syncProgress" && currentRoute != "first_run"
    val selectedTab = tabForRoute(currentRoute)
    // The sync bar follows the same rule: everywhere except the progress
    // screen, where it would only repeat what already fills the screen.
    val showSyncBar = showBottomBar

    // The keyboard is never left standing across a screen change. Compose
    // keeps focus (and so the IME) alive when the destination changes, and
    // nothing in this app ever dismissed it -- so after typing into the mail
    // account fields the keyboard stayed up on whatever screen came next,
    // sitting on top of the tab bar and swallowing taps on Home.
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(currentRoute) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    Scaffold(
        // targetSdk 36 means edge-to-edge is enforced: the window is no longer
        // resized for the keyboard, and Scaffold's default insets cover the
        // system bars but not the IME. Without this the tab bar is drawn
        // *underneath* an open keyboard -- visible, but every tap lands on a
        // key instead, which reads as "the Home button does not work".
        modifier = Modifier.imePadding(),
        bottomBar = {
            Column {
                if (showSyncBar) {
                    SyncStatusBar(
                        text = syncStatusText,
                        fraction = syncStatusFraction,
                        percent = syncStatusPercent,
                        running = syncStatusRunning,
                        detached = false,
                        onClick = onSyncStatusClick,
                    )
                }
                if (showBottomBar) {
                    NavigationBar {
                        bottomDests.forEach { dest ->
                            NavigationBarItem(
                                selected = selectedTab == dest.route,
                                onClick = {
                                    // Home is a tab, and tabPopTargetRoute() is
                                    // always "home" -- deliberately not the
                                    // graph's start destination, which is
                                    // "first_run" for a fresh install and stays
                                    // that way for the rest of the process even
                                    // once the flow finishes (see
                                    // tabPopTargetRoute's doc). Popping to the
                                    // start with saveState files the popped
                                    // stack (settings -> mailAccount) against
                                    // the start destination; navigating to Home
                                    // with restoreState then hands that very
                                    // stack back, so tapping Home from a
                                    // settings sub-screen put you straight back
                                    // on it and read as a dead button. Home
                                    // therefore resets instead of restoring;
                                    // the other tabs keep their state.
                                    val homeRoute = tabPopTargetRoute()
                                    val goingHome = dest.route == homeRoute
                                    navController.navigate(dest.route) {
                                        popUpTo(homeRoute) {
                                            saveState = !goingHome
                                        }
                                        launchSingleTop = true
                                        restoreState = !goingHome
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
        // The connection pill sits in the masthead of every screen and is the
        // only place the connection is named once an account exists. It reads
        // as a button, so it is one: tapping it opens the mail account screen,
        // whichever screen it was tapped from. launchSingleTop so tapping it
        // while already there does not stack a second copy.
        CompositionLocalProvider(
            LocalConnectionPillAction provides {
                navController.navigate("mailAccount") { launchSingleTop = true }
            },
        ) {
        NavHost(
            navController = navController,
            startDestination = firstRunStartDestination,
            modifier = Modifier.padding(padding),
        ) {
            composable("first_run") {
                // Read once, at entry: a fresh install has no previous
                // back-stack entry (first_run is the graph's start
                // destination), while "Run setup again" from Advanced
                // settings (H7) pushes first_run on top of it, so this
                // single read is what tells the two launches apart.
                val cameFrom = navController.previousBackStackEntry?.destination?.route
                val finishTarget = firstRunFinishTarget(cameFrom)
                fun finishFirstRun() {
                    if (!firstRunDone) {
                        AppPrefs.setFirstRunDone(context, true)
                        firstRunDone = true
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
                    imapProviders = imapProviders,
                    stagePlan = stagePlan,
                    initialProvider = imapProvider,
                    initialEmail = imapEmail,
                    onConnect = ::connectWithStages,
                    queuedChatCount = inboxFiles.size,
                    onDoTestRun = { runDryRunSync() },
                    watchedFolderUri = watchedFolderUri,
                    onChooseFolder = { folderPicker.launch(null) },
                    watchIntervalMinutes = watchIntervalMinutes,
                    onWatchIntervalChange = { setWatchInterval(it) },
                    onTurnOn = {
                        setAutoWatch(true)
                        finishFirstRun()
                    },
                    onNotNow = { finishFirstRun() },
                    hasExistingMailbox = imapPasswordSaved,
                )
            }
            composable("home") {
                // Home's inbox list is only otherwise refreshed right after an
                // import or a sync completes — if the app was relaunched or
                // navigated back to from another tab after a sync finished
                // (e.g. the process was killed mid-sync, losing the in-memory
                // WorkManager id), the list would keep showing already-synced
                // files. Re-check every time this screen is (re)entered.
                LaunchedEffect(Unit) { refreshInbox() }
                // Same "re-read on entry" reasoning as Settings: this is now
                // also where the masthead Me row lives, and a sync that ran
                // while Home sat on the back stack may have just learned a
                // name.
                LaunchedEffect(Unit) { refreshSelfSender() }
                // The one-time banner lives only on Home, so this is the one
                // place it needs a "re-read on entry" alongside ON_RESUME and
                // the post-sync effects above.
                LaunchedEffect(Unit) { refreshSelfSenderBanner() }
                // The pair HomeScreen's connection chip and Sync-now gating
                // are driven by.
                val homeAccountLabel = imapEmail.ifBlank { null }
                val homeBackendReady = imapPasswordSaved && imapHost.isNotBlank()
                val homeConnectActionLabel = if (homeBackendReady) "Change" else "Set up"
                // Re-read on entry (remember is recreated when this
                // destination is re-composed after navigation) and whenever a
                // save reports back, so walking Settings -> save -> Home
                // clears the line rather than leaving it lying.
                val lastBackupAt = remember(migrationStatus) {
                    AppPrefs.getLastBackupAt(context)
                }
                val homeMeDisplay = selfSenderDisplay(selfSenderSource, selfSenderName)
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
                    inboxFiles = inboxFiles,
                    // Goes to our own picker, not the system one. The system
                    // picker is still reachable from inside it, one clearly
                    // secondary button down, for files outside the granted
                    // folder.
                    onImportPick = { navController.navigate("importPicker") },
                    onPreview = { name ->
                        val path = ChatMailApplication.inboxDir(context).resolve(name).absolutePath
                        Python.getInstance().getModule("src.android_api")
                            .callAttr("preview_text", path, AppPrefs.getCutoffDate(context))
                            .toString()
                    },
                    onRemoveFile = { name -> removeInboxFile(name) },
                    dryRunDefault = dryRunDefault,
                    onDryRunDefaultChange = { dryRunDefault = it; AppPrefs.setDryRunDefault(context, it) },
                    onSyncNow = { if (dryRunDefault) runDryRunSync() else startRealSync() },
                    lastResult = lastResult,
                    syncInProgress = anySyncRunning,
                    backgroundIssues = backgroundIssues,
                    onBackgroundIssueAction = { issue ->
                        // Null only below API 23, where there is no battery
                        // exemption to grant and the issue is never raised in
                        // the first place -- so nothing to fall back to.
                        backgroundIssueIntent(context, issue)?.let { context.startActivity(it) }
                    },
                    onOpenSyncLog = { navController.navigate("syncLog") },
                    onOpenQueue = { navController.navigate("queue") },
                    onOpenBackup = { navController.navigate("settings") },
                    meLabel = homeMeDisplay.label,
                    meColor = homeMeDisplay.color,
                    meDescription = selfSenderContentDescription(selfSenderSource, selfSenderName),
                    onMeClick = { navController.navigate("me") },
                    lastBackupAt = lastBackupAt,
                    cutoffDate = cutoffDate,
                    onOpenSettings = { navController.navigate("settings") },
                    pendingSelfSenderBannerName = pendingSelfSenderBannerName,
                    onSelfSenderBannerOk = { dismissSelfSenderBanner() },
                    onSelfSenderBannerNotMe = {
                        dismissSelfSenderBanner()
                        navController.navigate("me")
                    },
                )
            }
            composable("queue") {
                // Same rule as home: this screen is reached from a list that
                // may have gone stale while the app was away.
                LaunchedEffect(Unit) { refreshInbox() }
                QueueScreen(
                    files = inboxFiles,
                    onPreview = { name ->
                        val path = ChatMailApplication.inboxDir(context).resolve(name).absolutePath
                        Python.getInstance().getModule("src.android_api")
                            .callAttr("preview_text", path, AppPrefs.getCutoffDate(context))
                            .toString()
                    },
                    onRemove = { name -> removeInboxFile(name) },
                    onImportPick = { navController.navigate("importPicker") },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("chats") {
                // Same "re-read on entry" reasoning as Home: the Me row
                // lives here too, and a background sync may have learned
                // a name while Chats sat on the back stack.
                LaunchedEffect(Unit) { refreshSelfSender() }
                val chatsMeDisplay = selfSenderDisplay(selfSenderSource, selfSenderName)
                ChatsListScreen(
                    onOpenChat = { chatId -> navController.navigate("chat/$chatId") },
                    // The empty state offers the import directly rather than
                    // sending the user to Home to find it -- same launcher
                    // Home's own [Import] button uses, so a file picked from
                    // either place lands in the same inbox.
                    onImportChat = { pickFile.launch(arrayOf("*/*")) },
                    meLabel = chatsMeDisplay.label,
                    meColor = chatsMeDisplay.color,
                    meDescription = selfSenderContentDescription(selfSenderSource, selfSenderName),
                    onMeClick = { navController.navigate("me") },
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
                    selfSenderSource = selfSenderSource,
                    selfSenderName = selfSenderName,
                    onOpenMe = { navController.navigate("me") },
                )
            }
            composable("settings") {
                // Re-read on entry, not once at startup: a sync that happened
                // while this screen sat on the back stack may have worked the
                // name out, and the screen exists to say so.
                LaunchedEffect(Unit) { refreshSelfSender() }
                // Backend-neutral one-line status for the "Mail account" nav
                // row — SettingsScreen no longer receives the backend params
                // it would need to compute this itself now that the account
                // UI lives on its own screen.
                val mailAccountSummary =
                    if (imapPasswordSaved) imapEmail else "Not connected"
                SettingsScreen(
                    mailAccountSummary = mailAccountSummary,
                    onOpenMailAccount = { navController.navigate("mailAccount") },
                    onOpenHelp = { navController.navigate("help") },
                    onOpenPrivacy = { navController.navigate("privacy") },
                    onOpenAdvanced = { navController.navigate("advancedSettings") },
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onSaveBackup = {
                        migrationStatus = null
                        saveBackup.launch(Migration.suggestedFileName())
                    },
                    onRestoreBackup = {
                        migrationStatus = null
                        // Every type, not our own: a bundle that has been round
                        // -tripped through Drive or Gmail can come back typed as
                        // something else entirely, and a filter that hides the
                        // file the user came to pick is worse than no filter.
                        restoreBackup.launch(arrayOf("*/*"))
                    },
                    migrationBusy = migrationBusy,
                    migrationStatus = migrationStatus,
                    selfSenderSource = selfSenderSource,
                    selfSenderName = selfSenderName,
                    onOpenMe = { navController.navigate("me") },
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
                    cutoffDate = cutoffDate,
                    onCutoffDateChange = {
                        cutoffDate = it
                        AppPrefs.setCutoffDate(context, it)
                    },
                    dryRunDefault = dryRunDefault,
                    onDryRunDefaultChange = {
                        dryRunDefault = it
                        AppPrefs.setDryRunDefault(context, it)
                    },
                    chunkSize = chunkSize,
                    onChunkSizeChange = { chunkSize = it; AppPrefs.setChunkSize(context, it) },
                    onTestConnection = ::testImapConnection,
                )
            }
            composable("importPicker") {
                // Re-read on entry for the same reason Home does: the folder
                // grant can be changed from Settings, and a file can be shared
                // in from WhatsApp while this screen sits on the back stack.
                LaunchedEffect(Unit) { refreshInbox() }
                ImportPickerScreen(
                    onBack = { navController.popBackStack() },
                    watchedFolderUri = watchedFolderUri,
                    // Names, not uris: what is already queued is a file in our
                    // inbox directory, and ImportManager keys on the leaf name
                    // too, so this marks exactly what a second import would
                    // refuse.
                    queuedNames = inboxFiles.map { it.first }.toSet(),
                    onChooseFolder = { folderPicker.launch(null) },
                    onPickFromAnywhere = { pickFile.launch(arrayOf("*/*")) },
                    onImport = { uris ->
                        importMany(uris)
                        // Back to Home, where the queue and Sync now are. The
                        // picker's job is done the moment the files are in.
                        navController.popBackStack()
                    },
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
                    onTestConnection = ::testImapConnection,
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
                    onRunWizard = { navController.navigate("mailWizard") },
                )
            }
            composable("me") {
                // Three ways in - Home, Chats and a chat's own detail screen
                // (its strip) - so the back label is read off the stack, the
                // same way the sync log does it.
                val from = navController.previousBackStackEntry?.destination?.route
                LaunchedEffect(Unit) { refreshSelfSender() }
                var meSenders by remember { mutableStateOf(listOf<String>()) }
                LaunchedEffect(Unit) { meSenders = listChatSenders() }
                MeScreen(
                    display = selfSenderDisplay(selfSenderSource, selfSenderName),
                    detail = selfSenderDetail,
                    senders = meSenders,
                    override = selfSenderOverride,
                    onPick = { setSelfSender(it) },
                    onSave = { setSelfSender(it) },
                    onClear = { setSelfSender("") },
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
                    imapProviders = imapProviders,
                    stagePlan = stagePlan,
                    initialProvider = imapProvider,
                    initialEmail = imapEmail,
                    onConnect = ::connectWithStages,
                    hasExistingMailbox = imapPasswordSaved,
                    onKeepCurrentMailbox = { navController.popBackStack() },
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
            composable("privacy") {
                // Two ways in - Settings and Help - so the back label is read
                // off the stack, the same way the sync log does it.
                val from = navController.previousBackStackEntry?.destination?.route
                PrivacyScreen(
                    onBack = { navController.popBackStack() },
                    backLabel = backLabelForRoute(from),
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
        }
        }
    }
}
