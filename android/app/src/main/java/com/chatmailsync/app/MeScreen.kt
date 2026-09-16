@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The Me screen: reached by tapping the masthead's Me row, this is where that
 * row's own state is explained and can be changed by hand.
 *
 * Structure mirrors [MailAccountScreen] -- zero-inset Scaffold (MainActivity's
 * own Scaffold already pays for the status-bar strip), a labelled
 * [ChatMailTopBar] back, and a padded, scrollable Column -- rather than
 * inventing a second pushed-screen shape for the app to carry.
 */
@Composable
fun MeScreen(
    display: SelfSenderDisplay,
    // Python-owned wording from android_api.get_self_sender()["detail"] --
    // this screen shows it verbatim rather than writing its own explanation,
    // so the two front-ends never say this two different ways.
    detail: String,
    // Every sender name seen across every export, most active first --
    // MainActivity's listChatSenders(), already deduplicated and summed.
    senders: List<String>,
    // The raw stored override ("" when none), so the pick list can mark
    // which entry -- if any -- is the one currently in force.
    override: String,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    backLabel: String,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatMailTopBar(
                title = "Me",
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
                .padding(14.dp)
                .fadingEdges(scrollState, MaterialTheme.colorScheme.background)
                .verticalScrollbar(scrollState)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The same chip language as the masthead row, just larger: a
            // filled pill in [display.color] rather than that colour used as
            // text, because on this screen's light surface the palette reads
            // as intended only as a background -- see SelfSenderDisplay's own
            // doc comment for why these three hexes are fixed rather than
            // theme roles.
            Surface(
                color = display.color,
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    display.label,
                    color = Color.Black,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (override.isNotBlank()) {
                TextButton(onClick = onClear) {
                    Text("Clear override -- go back to working it out automatically")
                }
            }

            HorizontalDivider()

            Text("Pick a name", style = MaterialTheme.typography.titleMedium)
            Text(
                "Names seen in your exports so far. Tapping one sets it as an " +
                    "override, the same as typing it in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (senders.isEmpty()) {
                Text(
                    "No senders yet -- import or sync a chat first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column {
                    for (sender in senders) {
                        val isCurrent = sender == override
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClickLabel = "Set $sender as Me") { onPick(sender) }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(
                                sender,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isCurrent) {
                                    androidx.compose.ui.text.font.FontWeight.SemiBold
                                } else {
                                    androidx.compose.ui.text.font.FontWeight.Normal
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (isCurrent) {
                                Text(
                                    "Current",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
