@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// The policy carried in the app, rather than linked to.
//
// It used to be a link out to chatmailsync.ambani.tech/privacy.html and
// nothing else. That reads as reasonable until a store reviewer opens the app
// on an image with no browser installed, or with no network, and reports the
// policy as unreachable -- which Indus Appstore did, twice. A policy that
// needs another app and a working connection before it can be read is not
// really "in the app", so this screen holds the text itself. It renders with
// no network, no browser and no permissions.
//
// PARITY: the section headings and the "Last updated" line below are kept in
// step with docs/privacy.html and with gui.py's PRIVACY_POLICY on Windows.
// tests/test_privacy_parity.py fails the build if the three drift apart. The
// wording is shared too -- unlike the FAQ, a policy is a statement about
// behaviour, and the two platforms must not say different things about it.

internal const val PRIVACY_LAST_UPDATED =
    "Last updated: August 31, 2026 - revised for version 2.0.0."

internal val PRIVACY_POLICY: List<Pair<String, List<String>>> = listOf(
    "Summary" to listOf(
        "This app copies WhatsApp chat exports that you provide into your own mailbox. " +
            "It runs entirely on your device. There is no backend server and no account " +
            "system - the developer never receives, stores, or has access to your messages, " +
            "your mail, or your accounts. This version carries no analytics, no advertising " +
            "and no third-party SDK of any kind.",
        "One promise does not depend on which version you are reading: your chats, your " +
            "attachments and your credentials go only to the mailbox you name. They are never " +
            "sold, never used to target anything at you, and never sent to the developer or to " +
            "anyone else. If a future version ever collects anything at all, this page and the " +
            "store listing's data-safety declaration change with the release that does it - " +
            "before you install it, not after.",
        "There is one way to connect: an email app password over IMAP, which works with any " +
            "IMAP provider, including Gmail, Outlook, Yahoo, iCloud and Fastmail.",
        "None of this has to be taken on trust. The app is open source, and every claim on " +
            "this page can be checked against the code that is supposed to keep it: " +
            "github.com/harshalambani/ChatMailSync",
    ),
    "How connecting to your mailbox works" to listOf(
        "No account is created, and nothing is registered with the developer or with any " +
            "server:",
        "• You supply your email address, your provider's IMAP host and port, and an app " +
            "password - a separate credential your provider issues for one application, which " +
            "you can revoke at any time without changing your real password.",
        "• That app password is stored only on your own device and is sent only to the " +
            "IMAP server you named. It is encrypted at rest on both platforms: with the Android " +
            "Keystore on Android, and with Windows DPAPI on Windows, inside the app's own data " +
            "folder readable only by your Windows user account. Because both keys are tied to " +
            "the device, a saved password does not travel to another phone or PC.",
        "• The app uses the IMAP APPEND command, which adds a message to a folder. It " +
            "creates the folders it needs and lists folder names to check whether they already " +
            "exist. It does not fetch, search, modify or delete any message already in your " +
            "mailbox, and it never sends mail. To be exact about what enforces this: an app " +
            "password is not something your provider can restrict to a subset of operations, so " +
            "the limit is the app's own code, whose entire mail command surface is the four " +
            "commands just described. The source is public and the check takes about a minute.",
        "Your mail provider necessarily sees the messages you archive, because they are stored " +
            "in the mailbox they host for you. That relationship is governed by your provider's " +
            "own privacy policy, not this one.",
    ),
    "Data retention and deletion" to listOf(
        "The developer retains nothing, because the developer never receives anything. On your " +
            "own device, the saved app password is kept until you clear it in the app or " +
            "uninstall, and the local dedup database is stored until you uninstall or clear the " +
            "app's data. That database holds the names of the chats you have synced, the export " +
            "filenames, and a one-way hash of each message it has sent - no message text and no " +
            "attachments.",
        "Emails the app has added to your mailbox are yours - delete them like any other " +
            "message at any time. The app cannot do it for you, and that is by design rather " +
            "than an omission: it holds no permission to delete anything in your mailbox, so " +
            "nothing it could be told to do, and no defect in it, can remove mail you already " +
            "have.",
    ),
    "Where your data goes" to listOf(
        "Nowhere but your own device and your own mailbox. The WhatsApp export file you share " +
            "or import is copied into the app's private storage on your device, parsed there, " +
            "and sent directly from your device to your mail provider's IMAP server over TLS. " +
            "There is no intermediate server operated by the developer that your messages, " +
            "attachments, or credentials ever pass through.",
        "The app keeps a small local database on your device - chat names, sync status, and a " +
            "hash of each message sent - purely to avoid sending the same message twice. It " +
            "stays on your device unless you export a backup of it yourself. Deleting the app " +
            "removes it.",
    ),
    "Backups, and moving to a new device" to listOf(
        "A new phone with no copy of that database would re-send your entire history, so the " +
            "app can write a backup file for you to carry across. It is written only when you " +
            "ask for one, never automatically and never anywhere but where you choose to put it.",
        "What the file contains is fixed and deliberately narrow: the dedup database described " +
            "above, and a short list of preferences - theme, batch size, IMAP host, port and " +
            "email address. It contains no password and no credential of any kind. The app " +
            "builds it from a list of keys that are permitted to travel rather than by removing " +
            "ones known to be secret, and it refuses to write the file at all if a key that " +
            "looks like a credential ever appears on that list. Your new device asks you for " +
            "the app password once, as a fresh install would.",
        "The backup is an ordinary zip file and it is not encrypted. Once written it is a file " +
            "like any other: the app does not know where you put it and cannot reach it again. " +
            "Because it names the chats you have synced and your email address, treat it as you " +
            "would any personal document, and prefer moving it directly between your own " +
            "devices over leaving it somewhere shared.",
    ),
    "How your data is protected" to listOf(
        "Because the messages you sync are sensitive personal data, the app is built so that " +
            "this data is exposed to as few systems as possible:",
        "• Encrypted in transit. Every network request the app makes goes directly to your " +
            "mail provider's IMAP server over TLS. Your messages, attachments and app password " +
            "are never sent over an unencrypted connection.",
        "• No developer server, ever. There is no backend operated by the developer. Your " +
            "data travels only between your own device and your own mail provider's servers, so " +
            "there is no third-party system that could store, log, or leak it.",
        "• Credentials stay on your device. The app password is stored only in local app " +
            "storage, encrypted with a key tied to that device (Windows DPAPI on Windows; the " +
            "Android Keystore on Android). It is never transmitted anywhere except to the IMAP " +
            "server you named, and never written to a log line or an error message.",
        "• Minimal local footprint. The only thing the app keeps is a small on-device " +
            "database of chat names, sync status and message hashes, used purely to avoid " +
            "re-sending the same message. It contains no message text and no attachments, and " +
            "it leaves your device only in a backup you export yourself.",
        "• You control access and can revoke it instantly. An app password is issued by " +
            "your provider for one application and can be revoked there at any time, without " +
            "changing your real password (see the next section).",
        "Because your data lives only on your own device and in your own mailbox, we recommend " +
            "protecting both with the usual safeguards - a device lock/OS-level encryption, and " +
            "two-step verification on your mail account.",
    ),
    "Account connection and revoking access" to listOf(
        "Clear the saved password in the app (Settings, then Mail account, then Forget saved " +
            "password), and revoke the app password with your mail provider so it cannot be " +
            "used again.",
        "Revoking access does not delete anything already synced - those are ordinary emails in " +
            "your mailbox and are yours to keep or delete like any other message.",
    ),
    "Third parties" to listOf(
        "None. The app does not share, sell, or transmit any data to any third party. The only " +
            "network calls it makes are to the IMAP server you name, directly from your device.",
    ),
    "Changes to this policy" to listOf(
        "If this policy changes, the updated version is published at the same address with a " +
            "new \"Last updated\" date, and is carried in the app from the next release.",
    ),
    "Contact" to listOf(
        "Questions about this policy or the app can be raised as an issue on the GitHub " +
            "repository. The full source is there too, for anyone who would rather read the " +
            "code than the promise: github.com/harshalambani/ChatMailSync",
    ),
)

@Composable
fun PrivacyScreen(onBack: () -> Unit, backLabel: String = "Settings") {
    val context = LocalContext.current
    Scaffold(
        // Zero, deliberately -- see HelpScreen for why: MainActivity's
        // Scaffold has already padded this NavHost, and insets are not
        // consumed by being turned into padding.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatMailTopBar(
                title = "Privacy policy",
                backLabel = backLabel,
                onBack = onBack,
            )
        },
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .fadingEdges(scrollState, MaterialTheme.colorScheme.background)
                .verticalScrollbar(scrollState)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                PRIVACY_LAST_UPDATED,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PRIVACY_POLICY.forEachIndexed { index, (heading, paragraphs) ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(heading, style = MaterialTheme.typography.titleSmall)
                    paragraphs.forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // Secondary, not the way in. The text above is the policy; this is
            // for anyone who wants the canonical hosted copy, and it is allowed
            // to fail on a device with no browser because nothing depends on it.
            Text(
                "The same policy is published at $PRIVACY_POLICY_URL",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { openUrl(context, PRIVACY_POLICY_URL) },
            ) { Text("Open in browser") }
        }
    }
}
