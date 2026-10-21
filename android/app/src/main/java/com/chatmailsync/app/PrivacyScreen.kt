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
    "Last updated: October 21, 2026 - revised for version 2.9.0."

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
            "IMAP provider, including Gmail, Yahoo, iCloud and Fastmail.",
        "None of this has to be taken on trust. The app is open source, and every claim on " +
            "this page can be checked against the code that is supposed to keep it: " +
            "github.com/harshalambani/ChatMailSync",
    ),
    "How connecting to your mailbox works" to listOf(
        "No account is created, and nothing is registered with the developer or with any " +
            "server:",
        "\u2022 You supply your email address, your provider's IMAP host and port, and an app " +
            "password - a separate credential your provider issues for one application, which " +
            "you can revoke at any time without changing your real password.",
        "\u2022 That app password is stored only on your own device and is sent only to the " +
            "IMAP server you named. It is encrypted at rest with the Android Keystore, inside the " +
            "app's own private storage. Android's own backup can carry the encrypted copy to a " +
            "new phone (see the section on backups below), but the key that opens it never " +
            "leaves the phone it was made on, so the copy cannot be read anywhere else and you " +
            "enter the password again on the new phone.",
        "\u2022 The app uses the IMAP APPEND command, which adds a message to a folder. It " +
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
        "The developer retains nothing, because the developer never receives anything. This is " +
            "everything version 2.9.0 keeps on your own device:",
        "\u2022 The export you import, until its sync is finished. The app holds its own copy of " +
            "the export file (the text, and any media if it is a zip) in its private storage. " +
            "When a sync of that chat completes, the app deletes that copy. If the sync fails, " +
            "is stopped, or finds no messages in the file, the copy is kept so you can try " +
            "again, and it is deleted when a later sync of it completes. The original file, " +
            "wherever you keep it, is not touched by this; the only thing that acts on it is " +
            "the watched-folder setting you choose (leave it, move it, or delete it).",
        "\u2022 A small database, until you uninstall or clear the app's data. It holds the names " +
            "of the chats you have synced and the export filenames; for each message sent, a " +
            "one-way hash and the message's time; for each chat, the names or phone numbers of " +
            "the people who appear in it, with the first and last time each was seen and how " +
            "many messages each sent; the history of sync runs (times, counts and any error " +
            "text); and the identifiers of the mailbox folder, conversation and message the " +
            "app uses to continue a chat in the same place. It holds neither message text nor " +
            "attachments.",
        "\u2022 Your settings. Your email address, IMAP host and port, the folder you watch, your " +
            "theme and batch choices, and the saved app password (encrypted), which is kept " +
            "until you clear it in the app or uninstall.",
        "If you have version 2.2.0 or earlier: those versions kept a copy of every export " +
            "after it had synced, in the app's private storage (a folder named processed), " +
            "with no time limit. It was the whole export, message text and any media " +
            "included, one copy per chat (versions before 2.0.0 could keep more than one). " +
            "You could not see that folder from outside the app. Version 2.9.0 deletes those " +
            "copies: the first time a sync runs after you update, it clears that folder. " +
            "Until that sync has run, the copies are still there. Uninstalling the app or " +
            "clearing its data removes them too.",
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
        "The app also keeps a small local database on your device, described under \"Data " +
            "retention and deletion\" above, purely to avoid sending the same message twice. " +
            "Apart from the backup routes in the next section, it stays on your device. " +
            "Deleting the app removes it.",
    ),
    "Backups, and moving to a new device" to listOf(
        "Android's own backup. The app allows Android to back up two things: the database " +
            "described above and the app's preferences. Android sends them to your Google " +
            "account's device backup, if you have that turned on, and carries them across in a " +
            "phone-to-phone transfer. The exports, media and the Keystore key are not " +
            "included. The preferences include the encrypted app password, but the key that " +
            "opens it never leaves the phone it was made on, so on a new phone the copy cannot " +
            "be read and the app asks for the password once. This backup is controlled in your " +
            "phone's own settings, where you can switch it off.",
        "The backup file you write yourself. A new phone with no copy of that database would " +
            "re-send your entire history, so the app can also write a backup file for you to " +
            "carry across. It is written only when you ask for one, never automatically and " +
            "never anywhere but where you choose to put it.",
        "What the file contains is fixed and deliberately narrow: the dedup database described " +
            "above, and a short list of preferences - theme, batch size, mail provider, port " +
            "and email address. It contains no password and no credential of any kind. The app " +
            "builds it from a list of keys that are permitted to travel rather than by removing " +
            "ones known to be secret, and it refuses to write the file at all if a key that " +
            "looks like a credential ever appears on that list. Your new device asks you for " +
            "the app password once, as a fresh install would.",
        "The backup is an ordinary zip file and it is not encrypted. Once written it is a file " +
            "like any other: the app does not know where you put it and cannot reach it again. " +
            "Because it names the chats you have synced, the people in them and your email " +
            "address, treat it as you " +
            "would any personal document, and prefer moving it directly between your own " +
            "devices over leaving it somewhere shared.",
    ),
    "How your data is protected" to listOf(
        "Because the messages you sync are sensitive personal data, the app is built so that " +
            "this data is exposed to as few systems as possible:",
        "\u2022 Encrypted in transit. Every network request the app makes goes directly to your " +
            "mail provider's IMAP server over TLS. Your messages, attachments and app password " +
            "are never sent over an unencrypted connection.",
        "\u2022 No developer server, ever. There is no backend operated by the developer. Your " +
            "data travels only between your own device and your own mail provider's servers, so " +
            "there is no third-party system that could store, log, or leak it.",
        "\u2022 Credentials stay on your device. The app password is stored only in local app " +
            "storage, encrypted with a key tied to that device (the Android Keystore). It is never transmitted anywhere except to the IMAP " +
            "server you named, and never written to a log line or an error message.",
        "\u2022 Limited local footprint. Apart from your settings, the app keeps a small " +
            "on-device database (chat names, the people in each chat, sync history and " +
            "message hashes), used purely to avoid re-sending the same message, and it keeps " +
            "your export file only until its sync is finished. The database contains neither " +
            "message text nor attachments. It leaves your device only in a backup you " +
            "export yourself, or in Android's own backup and device transfer described above.",
        "\u2022 You control access and can revoke it instantly. An app password is issued by " +
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
        "Questions about this policy or the app can be sent to the developer at " +
            "chat.mail.sync+privacy@gmail.com, or raised as an issue on the GitHub repository. The " +
            "full source is there too, for anyone who would rather read the code than the " +
            "promise: github.com/harshalambani/ChatMailSync",
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
