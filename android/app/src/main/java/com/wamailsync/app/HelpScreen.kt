@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wamailsync.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Kept in sync with the Windows edition's help.html by hand for now — a
// shared-markdown generator (per the screen-guides doc §9) is future work,
// not justified for this single FAQ screen yet.
private val FAQ = listOf(
    "How do I export a chat from WhatsApp?" to
        "Open the chat in WhatsApp -> tap the three-dot menu -> More -> Export chat. " +
        "Choose \"Include media\" for a .zip with photos/videos, or \"Without media\" for a plain .txt. " +
        "Then share it to this app (or use \"Import a WhatsApp export\" on Home).",
    "Why doesn't this app \"send\" my messages anywhere?" to
        "It uses your mail provider's insert API (Gmail's insert API, or IMAP APPEND for other " +
        "providers), which adds mail directly into your own mailbox without actually sending " +
        "anything. Nobody else receives these emails — they only appear in your mailbox.",
    "What happens if I sync the same export twice?" to
        "Every message is fingerprinted (hashed). Re-syncing the same file, or a fresh export that " +
        "overlaps an earlier one, skips anything already pushed — nothing is duplicated in your " +
        "mailbox. That record belongs to this instance of the app, though, not to your mailbox — " +
        "see the next answer.",
    "Can two instances of the app use the same mailbox?" to
        "They can, but they will not know about each other and you will get duplicates. This is not " +
        "about Android versus Windows: any two instances behave this way — two phones, two PCs, one " +
        "of each, or two copies of the portable Windows app in different folders. The record of what " +
        "has been archived belongs to the instance that did the archiving; nothing about it is stored " +
        "in the mailbox. A second instance signed in to the same account starts from zero knowledge " +
        "and re-files every chat you give it. This app can add mail but never remove it, so clearing " +
        "the duplicates afterwards is manual work. Use one instance per mailbox, or give each its own " +
        "account. Replacing an instance is a different case: carry the sync state across and the new " +
        "one continues where the old one stopped.",
    "Why do message times look off by a few hours?" to
        "WhatsApp exports don't include a timezone — the app assumes the exporting phone's local " +
        "clock. If you export from a different timezone than the chat was recorded in, times may shift.",
    "What does Reset actually do?" to
        "It clears this app's local record of what's been synced for that chat. It does NOT delete " +
        "anything already in your mailbox — this app can only add mail, never remove it. The next " +
        "sync therefore files a fresh copy of the whole chat into a brand-new thread, so if the old " +
        "mail is still there you end up with two copies. That's why Reset asks you to clear the " +
        "chat's folder first, and to confirm you've done it. On Gmail, deleting the label is not " +
        "enough: the messages stay in All Mail. Open the label, select every conversation, delete " +
        "them, then empty the Bin.",
    "Why does it ask me to reconnect every week?" to
        "Only if you're using Google sign-in — the app hasn't gone through Google's app-" +
        "verification process, so Google treats it as \"Testing\": sign-in expires roughly every 7 days " +
        "and only accounts added as test users (up to 100) can connect. This is Google's rule for " +
        "unverified apps and can't be extended from within the app. That's why Google sign-in is no " +
        "longer offered to new setups: an email app password connects to any provider, Gmail " +
        "included, and doesn't expire. You can switch to it any time from Settings without losing " +
        "anything already synced.",
    "Where is my email app password kept?" to
        "Encrypted on this device, with a key held in the Android Keystore that never leaves the " +
        "phone's secure hardware. It's never written into the app's settings, never shown in the " +
        "password box again after you save it, and never included in any log or error message. " +
        "Because the key is tied to this device, the saved password doesn't travel to a new phone " +
        "or survive uninstalling the app — enter it again in Settings there. It stays valid at your " +
        "provider either way. (The Windows edition does the same thing with Windows DPAPI, tied to " +
        "your Windows account on that PC.)",
    "The sync said some media was \"too large to email\". What happened?" to
        "Every provider caps how big one email can be — 25 MB at Gmail, Outlook and Yahoo, 20 MB at " +
        "iCloud. A busy day is split across several emails to stay under that, so you'll almost never " +
        "notice. What can't be split is a single file, usually a long video, that's bigger than the " +
        "whole cap on its own — no email anywhere can carry it. The message itself is still archived " +
        "(text, sender, time, its place in the conversation) and the email shows a note in the video's " +
        "place naming the file and its size. The video isn't lost: it's still in the WhatsApp export " +
        "you imported, and still on your phone. It will be left out on every future sync too, which is " +
        "why the sync summary names it. One surprise worth knowing: a 20 MB video doesn't make a 20 MB " +
        "email — email can't carry raw files, so everything is re-encoded on the way out and grows by " +
        "about a third. The practical ceiling for one file is around 18 MB on a 25 MB provider.",
    "What is the watched folder for?" to
        "It saves you importing by hand. Point it at a folder, and anything WhatsApp drops there " +
        "(.txt or .zip) is picked up and queued for the next sync. Switch on \"Auto-import from " +
        "this folder\" and it checks on its own on the interval you choose; leave it off and it " +
        "only looks when you tap \"Sync now\". Only that one folder is looked at — subfolders are " +
        "left alone — and a file is only ever picked up once, so re-checking costs you nothing. " +
        "Your original is never touched at import time; the \"After import\" setting only takes " +
        "effect once the file has actually reached your mailbox, so a sync that fails or that you " +
        "stop leaves everything where it was. The Windows edition has the same feature with one " +
        "difference worth knowing: it can only check while the app is open, and its \"delete\" " +
        "option sends the file to the Recycle Bin rather than erasing it.",
    "What can't this app do?" to
        "It can't read your existing mail, send email on your behalf, or keep syncing live in the " +
        "background continuously — each sync is a one-time pass over whatever's waiting in the inbox.",
    "What stops it reading or deleting my mail?" to
        "With an email app password — how everyone connects unless they were already using Google " +
        "sign-in — the guarantee is the app's own code, because no provider can give out a limited " +
        "password: any password that can add mail can technically do anything. The app uses only " +
        "four mail commands — list folders, create a folder, subscribe to it, and add a message. " +
        "None of them can open or remove a message, and the source is public for anyone who wants " +
        "to check. On the older Google sign-in, Google enforces it instead: the app is granted " +
        "permission to add mail and nothing else, so a request to read your mail is refused at " +
        "Google's end — it would be refused even if the app tried.",
)

@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            WaMailTopBar(
                title = "Help & FAQ",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            FAQ.forEach { (question, answer) ->
                Column {
                    Text(question, style = MaterialTheme.typography.titleSmall)
                    Text(answer, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
