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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Every third-party component that ships inside the APK, with the licence
 * that governs it. This list is the same one NOTICE documents in full --
 * see NoticeCoverageTest, which fails if a runtime `implementation`
 * dependency in app/build.gradle.kts (or the Chaquopy pip package) goes
 * missing from NOTICE, and NoticeAssetTest, which fails if the bundled
 * asset below drifts from the root NOTICE file's text.
 *
 * Kept as a short summary (name, licence, upstream) rather than repeating
 * full licence text here in Kotlin -- the full texts live only in NOTICE /
 * the bundled asset, both to avoid duplicating long licence bodies in
 * source and because pasted licence text is exactly the kind of content
 * that risks smuggling in a non-ASCII character SourceHygieneTest would
 * reject (see android/core's guard and this app's own discipline of
 * keeping licence prose out of .kt files).
 */
internal data class LicensedComponent(
    val name: String,
    val licence: String,
    val upstream: String,
)

internal val OPEN_SOURCE_COMPONENTS: List<LicensedComponent> = listOf(
    LicensedComponent("Chaquopy (Python runtime)", "MIT", "github.com/chaquo/chaquopy"),
    LicensedComponent(
        "CPython 3.13 (interpreter + standard library)",
        "Python Software Foundation License 2.0",
        "github.com/python/cpython",
    ),
    LicensedComponent(
        "python-dateutil",
        "Apache-2.0 AND BSD-3-Clause",
        "github.com/dateutil/dateutil",
    ),
    LicensedComponent("six", "MIT", "github.com/benjaminp/six"),
    LicensedComponent(
        "AndroidX / Jetpack Compose",
        "Apache-2.0",
        "github.com/androidx/androidx",
    ),
    LicensedComponent("Kotlin standard library", "Apache-2.0", "github.com/JetBrains/kotlin"),
    LicensedComponent(
        "kotlinx.coroutines",
        "Apache-2.0",
        "github.com/Kotlin/kotlinx.coroutines",
    ),
)

/** The asset path copyNoticeAsset (app/build.gradle.kts) copies NOTICE to. */
internal const val NOTICE_ASSET_PATH = "NOTICE.txt"

@Composable
fun OpenSourceLicensesScreen(onBack: () -> Unit, backLabel: String = "Settings") {
    val context = LocalContext.current
    // Read once per composition of this screen, not on every recomposition --
    // matches how PrivacyScreen treats LocalContext.current, just extended to
    // an actual file read. A missing asset (it should never be missing; the
    // Gradle task above wires it into every variant's preBuild) falls back to
    // a short explanatory line instead of crashing the screen.
    val noticeText = remember {
        try {
            context.assets.open(NOTICE_ASSET_PATH).bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            "NOTICE could not be loaded from the app package ($NOTICE_ASSET_PATH). " +
                "The full text is in the repository's own NOTICE file at " +
                "github.com/harshalambani/ChatMailSync."
        }
    }

    Scaffold(
        // Zero, deliberately -- see PrivacyScreen/HelpScreen for why:
        // MainActivity's Scaffold has already padded this NavHost.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatMailTopBar(
                title = "Open-source licences",
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
                "Chat Mail Sync is GPL-3.0 (see the Licence section of Settings). The " +
                    "APK also carries the third-party components below, each under its " +
                    "own licence. Their full licence texts follow the summary.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OPEN_SOURCE_COMPONENTS.forEach { component ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(component.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${component.licence} — ${component.upstream}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Full licence texts", style = MaterialTheme.typography.titleSmall)
            Text(
                noticeText,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
