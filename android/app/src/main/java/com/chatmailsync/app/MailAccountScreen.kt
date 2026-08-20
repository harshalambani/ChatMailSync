@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.net.URLEncoder
import java.util.Calendar

/** Mirrors android_api.imap_providers(), itself a mirror of
 * src/config.py's IMAP_PROVIDERS — [host] is "" for "custom" (no preset).
 * Kept top-level here (not private) because MainActivity.kt also
 * instantiates/populates it when loading the provider list from Python. */
data class ImapProviderInfo(val key: String, val label: String, val host: String, val port: Int)

// Matches gui.py's _BACKEND_LABELS exactly, so the two apps describe the
// same choice with the same words. Moved here with the rest of the account
// UI it labels; MainActivity.kt uses it too (mail-account summary line).
//
// Both labels name the SCOPE of the choice, not just its mechanism. The old
// pair ("Google sign-in (OAuth)" / "Email app password (IMAP)") described how
// you authenticate but left the thing that actually decides the choice unsaid:
// the OAuth path can only ever reach a Gmail mailbox. It is built on Gmail API
// scopes (gmail.insert / gmail.labels, see MainActivity.kt's scope list), so
// picking it with an Outlook or Fastmail address in mind is a dead end the UI
// used to let you walk into.
val BACKEND_LABELS = mapOf(
    AppPrefs.MAIL_BACKEND_IMAP to "Any provider (IMAP app password)",
    AppPrefs.MAIL_BACKEND_GMAIL_OAUTH to "Gmail only (Google sign-in)",
)

// Official, human-verified "create an app password" pages, one per
// src/config.py IMAP_PROVIDERS key. Verified by fetching each URL and
// confirming it is the provider's own current app-password help page
// (see task report for verification notes) — do not swap in an
// unverified link, these go stale often as providers redesign support
// sites. "custom" has no entry: there's no provider to link to, so the
// UI falls back to generic guidance instead.
internal val APP_PASSWORD_HELP_URLS = mapOf(
    "gmail" to "https://support.google.com/accounts/answer/185833",
    "outlook" to "https://support.microsoft.com/en-us/account-billing/using-app-passwords-with-apps-that-don-t-support-two-step-verification-5896ed9b-4263-e681-128a-a6f2979a7944",
    "yahoo" to "https://help.yahoo.com/kb/SLN15241.html",
    "icloud" to "https://support.apple.com/en-us/102654",
    "fastmail" to "https://www.fastmail.help/hc/en-us/articles/360058752854-App-passwords",
)

internal val APP_PASSWORD_HELP_TEXT = mapOf(
    "gmail" to "Gmail app passwords are generated from your Google Account's security settings (requires 2-Step Verification to be on).",
    // Personal Microsoft accounts only. Work and school (Microsoft 365)
    // mailboxes have basic authentication switched off, so an app password is
    // refused there whatever host is entered -- see src/config.py's
    // IMAP_PROVIDERS note.
    "outlook" to "Outlook.com app passwords are generated from your personal Microsoft account's security settings (requires two-step verification to be on). Work or school Microsoft 365 accounts can't use an app password at all.",
    "yahoo" to "Yahoo app passwords are generated from your Yahoo Account security page.",
    "icloud" to "iCloud app-specific passwords are generated at appleid.apple.com, under Sign-In and Security.",
    "fastmail" to "Fastmail app passwords are generated from Settings > Password & Security in your Fastmail account.",
)

// Bump this string (to the month/year you actually re-checked the steps
// below) any time APP_PASSWORD_STEPS_GMAIL or APP_PASSWORD_STEPS_OUTLOOK is
// edited. It's rendered next to the steps so a user whose provider has since
// changed its menus knows to trust the "Ask Google" / help-page buttons over
// this in-app text rather than assume the app is simply wrong.
internal const val APP_PASSWORD_STEPS_REVIEWED = "August 2026"

// Derived from support.google.com/accounts/answer/185833 (fetched and read
// directly — see task report). That page does not itself enumerate numbered
// steps; it states the 2-Step Verification prerequisite and links
// myaccount.google.com/apppasswords as the place app passwords are created
// and managed. The steps below are written from those confirmed facts only
// — nothing here is invented UI copy that wasn't on the page.
internal val APP_PASSWORD_STEPS_GMAIL = listOf(
    "Turn on 2-Step Verification for your Google Account first — the app password option stays hidden until it's on.",
    "Go to myaccount.google.com/apppasswords (in a browser) and sign in.",
    "Create a new app password there — Google gives you a 16-character code.",
    "Paste that 16-character code into the \"App password\" field below (not your normal Google password).",
)

// Derived from support.microsoft.com's "Using app passwords with apps that
// don't support two-step verification" page (fetched and read directly —
// see task report), which describes: two-step verification must be on;
// go to Advanced security options; scroll to the App passwords section;
// select the option to create one; use it wherever the app would normally
// ask for your Microsoft account password.
internal val APP_PASSWORD_STEPS_OUTLOOK = listOf(
    "Turn on two-step verification for your Microsoft account first — app passwords are only offered once it's on.",
    "Go to your Microsoft account's Advanced security options (account.microsoft.com) and sign in.",
    "Scroll to the \"App passwords\" section and choose to create one.",
    "Paste the generated app password into the \"App password\" field below (not your normal Microsoft password).",
    "If this is a work or school (Microsoft 365) account, see the note below — IMAP may be disabled by the admin regardless.",
)

/** Builds the provider-specific question a user can copy into an AI
 * assistant or paste into a web search to get current, provider-specific
 * app-password steps. Deliberately takes only [providerKey]/[providerLabel]/
 * [host] — the email address and app password must NEVER be interpolated
 * into this string. It gets copied to the clipboard and/or opened in a
 * browser search, both of which are effectively public once triggered, so
 * leaking either credential here would be a real exposure, not a cosmetic
 * one. If you're editing this function to add more context, keep that
 * boundary — provider name and host only. */
internal fun buildAppPasswordPrompt(providerKey: String, providerLabel: String, host: String): String {
    val year = Calendar.getInstance().get(Calendar.YEAR)
    val providerPhrase = if (providerKey == "custom") {
        if (host.isNotBlank()) "my email provider at $host" else "my email provider"
    } else {
        providerLabel
    }
    return "How do I create an app password for $providerPhrase in $year to use with a third-party IMAP " +
        "email app? Tell me whether I need to turn on two-factor authentication first, the exact page or " +
        "menu path where I generate the app password, and the IMAP server name and port to use. Give me " +
        "the current steps and link the official help page."
}

/**
 * Everything this app knows about getting an app password out of one provider.
 *
 * Extracted so the two places that teach it -- the collapsed section on this
 * screen, and step 2 of the setup wizard, where the same material is the whole
 * point of the step -- cannot drift apart. It renders into whatever Column it
 * is placed in and owns no layout of its own beyond the spacing between its
 * own parts, so the wizard can put a button above it and this screen can put a
 * divider.
 *
 * Neither the email address nor the app password is a parameter, and that is
 * deliberate: the only string this builds for export is
 * [buildAppPasswordPrompt]'s, which goes to the clipboard and to a web search.
 */
@Composable
internal fun AppPasswordHelpBody(providerKey: String, providerLabel: String, host: String) {
    val context = LocalContext.current
    val helpUrl = APP_PASSWORD_HELP_URLS[providerKey]
    val helpText = APP_PASSWORD_HELP_TEXT[providerKey]
    // Flips to show inline confirmation after "Copy question" — kept minimal
    // per design note: Android 13+ already shows its own system confirmation
    // on clipboard writes, so this just needs to say what to do next (paste it
    // into an AI assistant), not duplicate "copied".
    var promptCopied by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (providerKey == "custom") {
            Text(
                "Turn on two-factor authentication in your email account first, then look " +
                    "for \"App passwords\" or \"App-specific passwords\" in its security settings.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (helpText != null) {
            Text(helpText, style = MaterialTheme.typography.bodySmall)
        }

        // Inline numbered steps — only for the two providers whose official
        // pages were actually read and translated into steps here (Gmail,
        // Outlook). Every other provider relies on the help-page link and the
        // prompt buttons below instead of guessed steps.
        val inlineSteps = when (providerKey) {
            "gmail" -> APP_PASSWORD_STEPS_GMAIL
            "outlook" -> APP_PASSWORD_STEPS_OUTLOOK
            else -> null
        }
        if (inlineSteps != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                inlineSteps.forEachIndexed { index, step ->
                    Text("${index + 1}. $step", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Steps checked $APP_PASSWORD_STEPS_REVIEWED. If they don't match what you " +
                        "see, use the buttons below to get the current version.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Provider-specific gotchas that aren't obvious from the generic help
        // text above, surfaced only when they're relevant.
        if (providerKey == "outlook") {
            Text(
                "Work or school Microsoft 365 accounts often have IMAP access disabled by " +
                    "the organisation's administrator — if so, even a correct app password " +
                    "will be rejected.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (providerKey == "icloud") {
            Text(
                "This must be an app-specific password generated at appleid.apple.com, not " +
                    "your main Apple ID password.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // A live-current fallback (and the primary path for providers with no
        // inline steps) that doesn't depend on any URL staying valid.
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    val prompt = buildAppPasswordPrompt(providerKey, providerLabel, host)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("App password question", prompt))
                    promptCopied = true
                },
            ) { Text("Copy question") }
            TextButton(
                onClick = {
                    val prompt = buildAppPasswordPrompt(providerKey, providerLabel, host)
                    val encoded = URLEncoder.encode(prompt, "UTF-8")
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded")),
                    )
                },
            ) { Text("Search for steps") }
        }
        if (promptCopied) {
            Text(
                "Copied — paste it into Gemini, ChatGPT, or any AI assistant.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Lower-emphasis third option: the static, pre-verified link. Precise
        // when current, but only as fresh as the last time someone re-verified
        // it — the two buttons above don't have that expiry problem.
        if (helpUrl != null) {
            TextButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(helpUrl))) },
            ) { Text("Open $providerLabel's help page") }
        }
    }
}

@Composable
fun MailAccountScreen(
    onBack: () -> Unit,
    connectedEmail: String?,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    accessTokenAvailable: Boolean,
    onTestConnection: ((String) -> Unit) -> Unit,
    mailBackend: String,
    onMailBackendChange: (String) -> Unit,
    imapProviders: List<ImapProviderInfo>,
    imapProvider: String,
    onImapProviderChange: (String) -> Unit,
    imapHost: String,
    onImapHostChange: (String) -> Unit,
    imapPort: Int,
    onImapPortChange: (Int) -> Unit,
    imapEmail: String,
    onImapEmailChange: (String) -> Unit,
    imapPasswordSaved: Boolean,
    onSaveImapSettings: (String, String, Int, String, String, (Boolean, String) -> Unit) -> Unit,
    onForgetImapPassword: () -> Unit,
    onRunWizard: () -> Unit,
) {
    val context = LocalContext.current
    // True if a saved password was found unreadable and thrown away since this
    // was last asked -- a Keystore key can go with a factory-reset-protection
    // event or a restore onto a new phone, and the app then simply asks for the
    // password again with nothing anywhere saying why. remember{} so the read
    // (which clears the flag) happens once per visit to this screen rather than
    // on every recomposition. This screen is the only reader: the caption has to
    // be consumed somewhere, and here it sits directly above the field it
    // explains.
    val passwordWasLost = remember { SecretStore.consumeSecretLost(context) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var backendMenuOpen by remember { mutableStateOf(false) }
    // Computed from the LIVE screen state, not from prefs alone: mailBackend
    // and connectedEmail are what this screen is currently showing, and a user
    // who just picked OAuth must not have the picker vanish from under them
    // mid-edit. Only the unlock flag is read from storage. Deliberately not
    // remembered -- it is cheap, and it has to follow those two values.
    //
    // The latch that makes this durable runs in MainActivity; see
    // AppPrefs.latchOauthIfInUse.
    val oauthVisible = AppPrefs.oauthIsVisible(
        mailBackend,
        connectedEmail,
        AppPrefs.isOauthUnlocked(context),
    )
    var providerMenuOpen by remember { mutableStateOf(false) }
    // Password is deliberately never pre-filled from a saved value — Compose
    // state here is plain (unencrypted) memory, and re-displaying a saved
    // password back into a text field is exactly the kind of surfacing the
    // security spec for this feature rules out. An empty field with
    // imapPasswordSaved shown as a separate status line is the same UX
    // gui.py's Settings window uses ("Leave blank to keep the currently
    // saved password. The password is never shown or logged.").
    var imapPasswordInput by remember { mutableStateOf("") }
    var imapSaveBusy by remember { mutableStateOf(false) }
    var imapSaveStatus by remember { mutableStateOf<String?>(null) }
    val backendUsable = if (mailBackend == AppPrefs.MAIL_BACKEND_IMAP) imapPasswordSaved else accessTokenAvailable
    // Collapsed by default. Expanded inline this runs to roughly a screen and a
    // half of text, and it used to sit between the password field and "Save &
    // connect" — so the one control every user needs was pushed off-screen by
    // help that most users need once, if ever.
    var helpExpanded by remember { mutableStateOf(false) }
    // "Touched" state so the required-field error only shows once the user
    // has actually visited and left the field blank, not on first render —
    // marking every required field as an error before the user has typed
    // anything would be hostile UX for a brand-new, empty screen.
    var emailTouched by remember { mutableStateOf(false) }
    var hostTouched by remember { mutableStateOf(false) }
    val emailIsError = emailTouched && imapEmail.isBlank()
    val hostIsError = imapProvider == "custom" && hostTouched && imapHost.isBlank()
    val saveEnabled = !imapSaveBusy && imapEmail.isNotBlank() && (imapProvider != "custom" || imapHost.isNotBlank())

    Scaffold(
        // Zero, deliberately: MainActivity's Scaffold has already padded
        // this NavHost for the status bar and the bottom bars, and insets
        // are not consumed by being turned into padding -- so a screen
        // Scaffold left on the default reserves the same strips a second
        // time. That silently cost about a row and a half of list height
        // on every screen, which is how two exports ended up below the
        // fold on the import picker.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatMailTopBar(
                title = "Mail account",
                backLabel = "Settings",
                onBack = onBack,
            )
        },
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(14.dp)
                .fadingEdges(scrollState, MaterialTheme.colorScheme.background)
                .verticalScrollbar(scrollState)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // One instance per mailbox. Parity with gui.py's mail-account panel,
            // which carries the same sentence in the same position -- first, above
            // the backend picker -- because this screen is where a second instance
            // gets pointed at a mailbox the first one is already archiving into,
            // which is the exact moment the mistake is made. The record of what
            // has been sent lives in this instance's own sync_state.db, not in the
            // mailbox, so a second one starts from zero knowledge and re-files
            // every chat it is given. Nothing downstream can catch that: the
            // de-duplication is per-instance by construction, and the app can add
            // mail but never remove it.
            //
            // "Instance", not "device" or "platform": two phones, two PCs and two
            // copies of the portable app in different folders on one PC are all
            // the same failure. Naming Windows-vs-Android would read as an
            // exhaustive list and quietly bless the other cases.
            //
            // Weighting, decided deliberately: ONE quiet line in the same muted
            // caption style as every other note here -- no dialog, no banner, no
            // error colour, and not repeated on the sync screen. A caveat that
            // interrupts work it does not apply to gets dismissed unread. The
            // full explanation lives in Help and the user guide.
            Text(
                "One instance per mailbox. What has already been archived is " +
                    "remembered by this copy of the app, not by your mailbox, so any " +
                    "second instance using the same account — another phone, a PC, or " +
                    "a reinstall — will archive the same chats again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Mail backend", style = MaterialTheme.typography.titleMedium)
            if (oauthVisible) {
                Box {
                    OutlinedButton(onClick = { backendMenuOpen = true }) {
                        Text(BACKEND_LABELS[mailBackend] ?: mailBackend)
                    }
                    DropdownMenu(expanded = backendMenuOpen, onDismissRequest = { backendMenuOpen = false }) {
                        BACKEND_LABELS.forEach { (backend, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { onMailBackendChange(backend); backendMenuOpen = false },
                            )
                        }
                    }
                }
            } else {
                // One way in, so this states a fact instead of offering a
                // choice. A one-item dropdown would be the worse lie: it
                // implies something else is behind it. Matches the static
                // label gui.py falls back to in the same situation.
                //
                // The Gmail sign-in path is demoted, not removed -- it still
                // runs for anyone using it. It is simply not offered to
                // someone who has never used it, because Google's "Testing"
                // publishing status expires that consent 7 days after it is
                // granted (see AppPrefs.oauthIsVisible).
                Text(BACKEND_LABELS[AppPrefs.MAIL_BACKEND_IMAP]!!)
            }

            if (mailBackend == AppPrefs.MAIL_BACKEND_IMAP) {
                // The way back into the guided setup. The wizard runs by itself
                // the first time, but it is the only place that walks somebody
                // through getting an app password in order, so it has to stay
                // reachable afterwards -- switching provider, or a password the
                // provider has since revoked, puts an existing user back at
                // exactly the problem the wizard was written for.
                //
                // Offered here rather than replacing this screen: this form is
                // the faster path once you know what the fields are, and taking
                // it away to force everyone back through four steps would be the
                // wrong trade for the person who just needs to fix a typo.
                OutlinedButton(onClick = onRunWizard, modifier = Modifier.fillMaxWidth()) {
                    Text("Set up again, step by step")
                }
                Box {
                    OutlinedButton(onClick = { providerMenuOpen = true }) {
                        Text(imapProviders.firstOrNull { it.key == imapProvider }?.label ?: imapProvider)
                    }
                    DropdownMenu(expanded = providerMenuOpen, onDismissRequest = { providerMenuOpen = false }) {
                        imapProviders.forEach { info ->
                            DropdownMenuItem(
                                text = { Text(info.label) },
                                onClick = { onImapProviderChange(info.key); providerMenuOpen = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = imapHost,
                    onValueChange = onImapHostChange,
                    label = { Text(if (imapProvider == "custom") "Host *" else "Host") },
                    enabled = imapProvider == "custom",
                    isError = hostIsError,
                    supportingText = {
                        if (hostIsError) Text("Required")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) hostTouched = true },
                )
                OutlinedTextField(
                    value = imapPort.toString(),
                    onValueChange = { it.toIntOrNull()?.let(onImapPortChange) },
                    label = { Text("Port") },
                    enabled = imapProvider == "custom",
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = imapEmail,
                    onValueChange = onImapEmailChange,
                    label = { Text("Email address *") },
                    isError = emailIsError,
                    supportingText = {
                        if (emailIsError) Text("Required")
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) emailTouched = true },
                )
                OutlinedTextField(
                    value = imapPasswordInput,
                    onValueChange = { imapPasswordInput = it },
                    label = { Text("App password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    if (imapPasswordSaved) "An app password is saved for $imapEmail."
                    else "No app password saved yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (passwordWasLost && !imapPasswordSaved) {
                    Text(
                        "Your saved app password could not be read on this device and " +
                            "has been cleared — this happens after a device reset or a " +
                            "restore onto a new phone. Nothing you have already synced " +
                            "is affected. Enter it once more to reconnect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Leave the password field blank to keep the currently saved password. " +
                        "The password is never shown or logged.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        enabled = saveEnabled,
                        onClick = {
                            imapSaveBusy = true
                            imapSaveStatus = null
                            onSaveImapSettings(imapProvider, imapHost, imapPort, imapEmail, imapPasswordInput) { success, message ->
                                imapSaveBusy = false
                                imapSaveStatus = message
                                if (success) imapPasswordInput = ""
                            }
                        },
                    ) { Text(if (imapSaveBusy) "Connecting…" else "Save & connect") }
                    if (imapPasswordSaved) {
                        TextButton(
                            onClick = onForgetImapPassword,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("Forget saved password") }
                    }
                }
                imapSaveStatus?.let { Text(it, modifier = Modifier.fillMaxWidth()) }

                // Contextual app-password help, keyed off the selected provider.
                // Deliberately placed *after* "Save & connect" and collapsed:
                // it is reference material for a once-per-account task, and
                // sitting above the button it pushed the only control that
                // matters off the bottom of the screen.
                //
                // Every static URL here was fetched and eyeballed as the
                // provider's own current "create an app password" page before
                // being hardcoded — this is the one place in this screen that
                // sends the user off-app, so it's worth the extra care.
                // Provider console menus drift over time though, which is why
                // this also offers a live "ask an AI / search" path rather than
                // relying solely on a static link.
                HorizontalDivider()
                val providerLabel = imapProviders.firstOrNull { it.key == imapProvider }?.label ?: imapProvider

                TextButton(onClick = { helpExpanded = !helpExpanded }) {
                    Text(
                        if (helpExpanded) "Hide app password help"
                        else "Not sure how to get an app password?",
                    )
                }

                if (helpExpanded) {
                    AppPasswordHelpBody(
                        providerKey = imapProvider,
                        providerLabel = providerLabel,
                        host = imapHost,
                    )
                }
            } else {
                Text("Account", style = MaterialTheme.typography.titleMedium)
                Text(connectedEmail ?: "Not connected", style = MaterialTheme.typography.bodyLarge)
                if (connectedEmail == null) {
                    Button(onClick = onReconnect) { Text("Connect") }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = onReconnect) { Text("Reconnect") }
                        TextButton(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("Disconnect") }
                    }
                }
                // Business reason IMAP exists at all: this app hasn't been
                // through Google's paid annual CASA verification, so it stays
                // in "Testing" mode — see HelpScreen for the full explanation.
                Text(
                    "Google sign-in is limited to 100 test users and expires roughly every 7 " +
                        "days while this app is in Google's \"Testing\" publishing mode. See " +
                        "Help & FAQ for details, or switch to \"Email app password (IMAP)\" above " +
                        "to avoid both limits.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()

            // "Developer tools" heading dropped in the split — this button is
            // connection-diagnostic, not a dev-only affordance, so it now
            // reads as part of account/connection setup instead of being
            // filed under a heading that undersold its relevance to normal
            // users troubleshooting a failed save.
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = {
                    onTestConnection { result -> testResult = result }
                },
                enabled = backendUsable,
            ) {
                Text("Test connection")
            }
            testResult?.let { Text(it, modifier = Modifier.fillMaxWidth()) }
        }
    }
}
