@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wagmailsync.app

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
        "It uses Gmail's insert API, which adds mail directly into your own mailbox without " +
        "actually sending anything. Nobody else receives these emails — they only appear in your Gmail.",
    "What happens if I sync the same export twice?" to
        "Every message is fingerprinted (hashed). Re-syncing the same file, or a fresh export that " +
        "overlaps an earlier one, skips anything already pushed — nothing is duplicated in Gmail.",
    "Why do message times look off by a few hours?" to
        "WhatsApp exports don't include a timezone — the app assumes the exporting phone's local " +
        "clock. If you export from a different timezone than the chat was recorded in, times may shift.",
    "What does Reset actually do?" to
        "It clears this app's local record of what's been synced for that chat. It does NOT delete " +
        "anything already in Gmail. Re-importing the export afterwards starts a brand-new Gmail thread.",
    "Why does it ask me to reconnect every week?" to
        "Only if you're using Google sign-in (OAuth) — the app hasn't gone through Google's app-" +
        "verification process, so Google treats it as \"Testing\": sign-in expires roughly every 7 days " +
        "and only accounts added as test users (up to 100) can connect. This is Google's rule for " +
        "unverified apps and can't be extended from within the app. The Email app password (IMAP) " +
        "backend doesn't have this limit — it's the default, and connecting once doesn't expire. You can " +
        "switch to it any time from Settings without losing anything already synced.",
    "What can't this app do?" to
        "It can't read your existing Gmail, send email on your behalf, or keep syncing live in the " +
        "background continuously — each sync is a one-time pass over whatever's waiting in the inbox.",
)

@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            WagmailTopBar(
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
