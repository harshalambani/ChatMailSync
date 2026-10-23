@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The app version line, read from BuildConfig (generated from versionName /
 * versionCode, so it cannot drift from the APK). versionCode is shown too: it
 * is the number `adb shell dumpsys package` reports and the one the store
 * orders by, so it answers "am I on the current build?". */
internal fun appVersionText(name: String, code: Int, debug: Boolean): String =
    "Chat Mail Sync $name ($code)" + if (debug) " — debug build" else ""

/**
 * UX-01: the Help sub-screen, reached by Settings' single "Help" row. Holds
 * what used to sit at the bottom of Settings: Help & FAQ, the privacy policy,
 * the open-source licences and the app version. The screens it opens say
 * "Help" on their back arrow (see backLabelForRoute).
 */
@Composable
fun HelpAboutScreen(
    onBack: () -> Unit,
    backLabel: String = "Settings",
    onOpenHelp: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatMailTopBar(
                title = "Help",
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
                .fadingEdges(scrollState, MaterialTheme.colorScheme.background)
                .verticalScrollbar(scrollState)
                .verticalScroll(scrollState)
                .padding(vertical = 8.dp),
        ) {
            HELP_ROWS.forEachIndexed { index, row ->
                when (row.id) {
                    "help_faq" -> SettingsListRow(
                        icon = Icons.Filled.Info,
                        title = "Help & FAQ",
                        subtitle = "Answers to the common questions",
                        kind = SubtitleKind.PLAIN,
                        onClick = onOpenHelp,
                    )

                    // The policy is carried in the app, not linked out to: a
                    // policy that needs a second app and a live connection
                    // before it can be read is not really inside the app.
                    "privacy_policy" -> SettingsListRow(
                        icon = Icons.Filled.Lock,
                        title = "Privacy policy",
                        subtitle = "What the app does and does not do with your data",
                        kind = SubtitleKind.PLAIN,
                        onClick = onOpenPrivacy,
                    )

                    // Third-party notices the distributed APK is required to
                    // carry; see NOTICE at the repo root.
                    "licences" -> SettingsListRow(
                        icon = Icons.Filled.Star,
                        title = "Open-source licences",
                        subtitle = "Third-party notices",
                        kind = SubtitleKind.PLAIN,
                        onClick = onOpenLicenses,
                    )

                    "app_version" -> SettingsListRow(
                        icon = Icons.Filled.Info,
                        title = "App version",
                        subtitle = appVersionText(
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                            BuildConfig.DEBUG,
                        ),
                        kind = SubtitleKind.VALUE,
                    )
                }
                if (index != HELP_ROWS.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                }
            }
        }
    }
}
