@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chatmailsync.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The masthead bar: a postmark-blue banner rather than a flat
 * surface-colored bar, with the app's mark to the left of a serif wordmark.
 * [subtitle] is the small-caps eyebrow line under the title — reserved for
 * top-level tabs (Home/Chats/Settings); detail screens pass null and just get
 * mark+title.
 *
 * The mark is drawable/ic_masthead, NOT ic_launcher_foreground. It used to be
 * the latter, which is the adaptive-icon foreground layer and is groundless by
 * design because a launcher composites it over its own background. Painted
 * straight onto this navy band there was nothing beneath it: the chevron and
 * the envelope notch showed band colour through, and the mark had no container,
 * so it read as unfinished. ic_masthead is the ringed badge cut for this one
 * surface. Everywhere else — launcher, taskbar, splash — the mark stands alone
 * with no ring, so do not reuse ic_masthead outside the banner.
 */
private val MastheadHeight = 88.dp

/**
 * The labelled back affordance: `← Chats`, not a bare arrow.
 *
 * A back arrow on its own says only "leave", and the feedback on this app was
 * exactly that it "is not very intuitive or obvious" -- it does not say what
 * you are leaving *to*, which on a screen reached from two places is the only
 * thing worth knowing. The Windows window has said "Back to sync" / "Back to
 * settings" since its panel stack shipped; this is the Android half of that
 * parity.
 */
@Composable
private fun BackAffordance(label: String, onBack: () -> Unit) {
    TextButton(
        onClick = onBack,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        contentPadding = PaddingValues(start = 8.dp, end = 12.dp),
    ) {
        Icon(
            // AutoMirrored: this is now the app's only back arrow, so there is
            // no consistency reason left to keep the deprecated fixed-direction
            // one, and it flips correctly under a right-to-left locale.
            Icons.AutoMirrored.Filled.ArrowBack,
            // null, not "Back": the label beside it is already the button's
            // accessible name, and a description here makes a screen reader
            // announce the control twice.
            contentDescription = null,
            modifier = androidx.compose.ui.Modifier.size(18.dp),
        )
        Text(
            label,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            maxLines = 1,
            modifier = androidx.compose.ui.Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
fun ChatMailTopBar(
    title: String,
    subtitle: String? = null,
    // Where back goes, in words. Set these instead of navigationIcon on any
    // screen that is pushed onto another -- the mark steps aside when they
    // are, because an 88dp band cannot carry a 56dp badge, a labelled back
    // and a title without one of them being squeezed, and on a pushed screen
    // the badge is the least useful of the three.
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val labelledBack = backLabel != null && onBack != null
    TopAppBar(
        expandedHeight = MastheadHeight,
        title = {
            // TopAppBar's own vertical placement of the title slot anchors
            // it toward the bottom of expandedHeight (matching Large/Medium
            // top-bar collapse behavior) rather than centering it, so with
            // an expandedHeight taller than the default this left a lot of
            // dead space above the mark+wordmark. A fixed-height box (not
            // fillMaxHeight — the slot's height constraint here is
            // unbounded, which blows fillMaxHeight up to fill the screen)
            // matching expandedHeight, centered inside, overrides that.
            Box(modifier = androidx.compose.ui.Modifier.height(MastheadHeight), contentAlignment = Alignment.CenterStart) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!labelledBack) Image(
                        painter = painterResource(R.drawable.ic_masthead),
                        contentDescription = null,
                        // 56dp, not the old 72dp. The adaptive foreground drew
                        // its mark at 72/108 of its canvas, so a 72dp box put
                        // ~48dp of ink on screen. ic_masthead is a full-bleed
                        // badge, so the same box would render half again as
                        // large and crowd an 88dp band.
                        modifier = androidx.compose.ui.Modifier.size(56.dp),
                    )
                    Column {
                        Text(
                            title,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                        )
                        subtitle?.let {
                            Text(
                                it.uppercase(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.4.sp,
                            )
                        }
                    }
                }
            }
        },
        navigationIcon = {
            if (backLabel != null && onBack != null) BackAffordance(backLabel, onBack) else navigationIcon()
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}
