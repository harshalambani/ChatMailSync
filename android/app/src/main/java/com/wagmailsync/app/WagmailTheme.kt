package com.wagmailsync.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * "Quiet Archive" — the design direction picked from the design-directions
 * artifact (2026-07-05): cool paper neutrals, graphite ink, one postmark-blue
 * accent. A fixed brand palette rather than Material You dynamic color,
 * since the whole point of this identity is to look like a filing cabinet,
 * not whatever wallpaper the phone happens to have.
 */
private val PostmarkBlue = Color(0xFF2E4374)
private val PostmarkBlueLight = Color(0xFF93A8D6)
private val Graphite = Color(0xFF20242B)
private val PaperCool = Color(0xFFF5F6F4)
private val SlateMid = Color(0xFF8890A0)
private val Hairline = Color(0xFFD8DBD6)

private val QuietArchiveLight = lightColorScheme(
    primary = PostmarkBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE4F0),
    onPrimaryContainer = Color(0xFF16233F),
    secondary = SlateMid,
    onSecondary = Color.White,
    background = PaperCool,
    onBackground = Graphite,
    surface = Color.White,
    onSurface = Graphite,
    surfaceVariant = PaperCool,
    onSurfaceVariant = SlateMid,
    outline = Hairline,
)

private val QuietArchiveDark = darkColorScheme(
    primary = PostmarkBlueLight,
    onPrimary = Color(0xFF16233F),
    primaryContainer = PostmarkBlue,
    onPrimaryContainer = Color(0xFFDDE4F0),
    secondary = Color(0xFFA6AEBB),
    onSecondary = Color(0xFF1B2027),
    background = Color(0xFF17191D),
    onBackground = Color(0xFFECEDE9),
    surface = Color(0xFF202329),
    onSurface = Color(0xFFECEDE9),
    surfaceVariant = Color(0xFF20242B),
    onSurfaceVariant = Color(0xFFA6AEBB),
    outline = Color(0xFF3A3D44),
)

@Composable
fun WagmailTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) QuietArchiveDark else QuietArchiveLight,
        typography = Typography(),
        content = content,
    )
}
