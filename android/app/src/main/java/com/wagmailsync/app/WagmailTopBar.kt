@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wagmailsync.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * surface-colored bar, with the app's mark (the same envelope/chat-bubble/
 * sync-arrows glyph as the launcher icon, drawable/ic_launcher_foreground)
 * to the left of a serif wordmark. [subtitle] is the small-caps eyebrow
 * line under the title — reserved for top-level tabs (Home/Chats/Settings);
 * detail screens pass null and just get mark+title.
 */
private val MastheadHeight = 88.dp

@Composable
fun WagmailTopBar(
    title: String,
    subtitle: String? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
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
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = androidx.compose.ui.Modifier.size(72.dp),
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
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}
