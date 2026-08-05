package com.wamailsync.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * "Quiet Archive" — the design direction picked from the design-directions
 * artifact (2026-07-05): cool paper neutrals, graphite ink, one postmark-blue
 * accent. A fixed brand palette rather than Material You dynamic color,
 * since the whole point of this identity is to look like a filing cabinet,
 * not whatever wallpaper the phone happens to have.
 *
 * EVERY role is assigned deliberately. lightColorScheme()/darkColorScheme()
 * only override the roles you actually pass, and fall back to Material 3's
 * *baseline purple* palette for the rest — so the earlier short scheme, which
 * set nine roles, left the app quietly rendering M3 purple anywhere a
 * component reached for a role it hadn't named. The most visible case was
 * AlertDialog: its container is surfaceContainerHigh, which was unset, so
 * every dialog in the app came up lavender (#ECE6F0) in the middle of a
 * blue-and-paper screen. Cards, menus, the navigation bar and every elevated
 * surface had the same problem via surfaceContainer/surfaceTint. Leaving a
 * role unset is not "use the neutral default", it is "use somebody else's
 * brand colour", so the list below is exhaustive on purpose - if a role is
 * added to Material 3 later, add it here rather than letting it default.
 */

// --- Brand ---------------------------------------------------------------
private val PostmarkBlue = Color(0xFF2E4374)
private val PostmarkBlueLight = Color(0xFF93A8D6)
private val Graphite = Color(0xFF20242B)

// A muted oxblood rather than M3's baseline #B3261E. The baseline red is a
// signal-flare next to this palette, and it sits on the two most destructive
// controls in the app (Reset, Delete), so it dominated every screen it
// appeared on. This still reads unmistakably as "danger" but belongs to the
// same muted family as the blue.
private val Oxblood = Color(0xFF8C2F2A)

// The archival green from the launcher mark's chat bubble, promoted to a real
// theme role so screens stop hardcoding one-off greens for "OK" indicators.
private val ArchiveGreen = Color(0xFF3F6B52)

private val QuietArchiveLight = lightColorScheme(
    primary = PostmarkBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE4F0),
    onPrimaryContainer = Color(0xFF16233F),
    inversePrimary = PostmarkBlueLight,

    secondary = Color(0xFF515A6B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE1E9),
    onSecondaryContainer = Color(0xFF1B2027),

    tertiary = ArchiveGreen,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5E6DC),
    onTertiaryContainer = Color(0xFF17281F),

    error = Oxblood,
    onError = Color.White,
    errorContainer = Color(0xFFF6DEDA),
    onErrorContainer = Color(0xFF3F110E),

    background = Color(0xFFF5F6F4),
    onBackground = Graphite,

    // The surface ramp is what gives stacked things (sheet over card over
    // page) their separation without drop shadows. Cool paper greys, stepped
    // evenly, so a dialog sits *just* off the page rather than glowing.
    surface = Color(0xFFFBFBF9),
    onSurface = Graphite,
    surfaceDim = Color(0xFFDCDDD9),
    surfaceBright = Color(0xFFFBFBF9),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F6F4),
    surfaceContainer = Color(0xFFEFF0ED),
    surfaceContainerHigh = Color(0xFFE9EAE7),
    surfaceContainerHighest = Color(0xFFE3E5E1),

    surfaceVariant = Color(0xFFE2E4E6),
    // Was #8890A0. Material 3 uses onSurfaceVariant for *body text* in
    // dialogs, supporting text under fields, and list secondary lines - not
    // just for hints - and #8890A0 on the dialog container was about 2.4:1,
    // well under the 4.5:1 body-text floor. This is the same slate, taken
    // dark enough to read (7.4:1 on surfaceContainerHigh).
    onSurfaceVariant = Color(0xFF4B5361),

    // M3 draws borders with `outline` and dividers with `outlineVariant`; the
    // old scheme put the hairline in `outline`, which made real borders
    // (OutlinedButton, OutlinedTextField) nearly invisible.
    outline = Color(0xFF767D89),
    outlineVariant = Color(0xFFD8DBD6),

    // Unset, this tints every elevated surface with baseline purple.
    surfaceTint = PostmarkBlue,
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2F3238),
    inverseOnSurface = Color(0xFFF1F2EE),
)

private val QuietArchiveDark = darkColorScheme(
    primary = PostmarkBlueLight,
    onPrimary = Color(0xFF16233F),
    primaryContainer = PostmarkBlue,
    onPrimaryContainer = Color(0xFFDDE4F0),
    inversePrimary = PostmarkBlue,

    secondary = Color(0xFFA6AEBB),
    onSecondary = Color(0xFF1B2027),
    secondaryContainer = Color(0xFF3A4250),
    onSecondaryContainer = Color(0xFFDCE1E9),

    tertiary = Color(0xFF9FCBB2),
    onTertiary = Color(0xFF0B2618),
    tertiaryContainer = ArchiveGreen,
    onTertiaryContainer = Color(0xFFD5E6DC),

    // Dark schemes invert error: a light red drawn *on* dark, with dark text
    // on top of it when it is used as a container.
    error = Color(0xFFF2B8B2),
    onError = Color(0xFF561713),
    errorContainer = Oxblood,
    onErrorContainer = Color(0xFFF9DEDC),

    background = Color(0xFF17191D),
    onBackground = Color(0xFFECEDE9),

    surface = Color(0xFF17191D),
    onSurface = Color(0xFFECEDE9),
    surfaceDim = Color(0xFF17191D),
    surfaceBright = Color(0xFF3B3F46),
    surfaceContainerLowest = Color(0xFF101216),
    surfaceContainerLow = Color(0xFF1B1E23),
    surfaceContainer = Color(0xFF202329),
    surfaceContainerHigh = Color(0xFF2A2E35),
    surfaceContainerHighest = Color(0xFF353A42),

    surfaceVariant = Color(0xFF3A3F47),
    onSurfaceVariant = Color(0xFFC2C8D0),

    outline = Color(0xFF8A9199),
    outlineVariant = Color(0xFF3A3D44),

    surfaceTint = PostmarkBlueLight,
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFECEDE9),
    inverseOnSurface = Color(0xFF2F3238),
)

/**
 * Baseline Material 3 type, with the two display/headline roles switched to
 * the same serif the masthead wordmark uses. Dialog titles are headlineSmall,
 * so this is what ties a dialog back to the app's identity instead of leaving
 * it looking like a stock system prompt.
 */
private val QuietArchiveTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Serif),
        headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
        ),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif),
    )
}

@Composable
fun WaMailTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) QuietArchiveDark else QuietArchiveLight,
        typography = QuietArchiveTypography,
        content = content,
    )
}
