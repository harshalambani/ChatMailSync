package com.chatmailsync.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A soft scrim at whichever end of a scrolling region still has content beyond it.
 *
 * Written after a live miss: the import picker listed six exports, four fitted,
 * and the fifth and sixth were never found -- so four were imported and the
 * other two chats simply did not get archived. Nothing was broken; the list
 * scrolled perfectly well. It just did not *say* it scrolled. The bottom row
 * happened to clip near a row boundary, and a hairline scrollbar at the screen
 * edge is not an answer to "is this all of them?".
 *
 * A fade is the answer because it is a statement about content, not about
 * chrome: ink running out under a gradient reads as "there is more this way"
 * without needing a legend. It appears only when there is genuinely more --
 * a permanent fade is decoration, and would teach people to stop believing it.
 *
 * [color] must be the colour actually painted behind the list, not simply
 * `surface`: the scrim fades content *into its own ground*, and a mismatch
 * shows up as a grey bruise across the last row.
 */
private val DefaultFadeHeight = 20.dp

@Composable
fun Modifier.fadingEdges(
    state: ScrollableState,
    color: Color = MaterialTheme.colorScheme.surface,
    height: Dp = DefaultFadeHeight,
    top: Boolean = true,
    bottom: Boolean = true,
): Modifier {
    // Animated rather than switched: at rest against either end the scrim has
    // to be genuinely absent, but popping it in and out on the first pixel of
    // a drag draws the eye to the edge instead of to the content.
    val topAlpha by animateFloatAsState(
        targetValue = if (top && state.canScrollBackward) 1f else 0f,
        label = "topFade",
    )
    val bottomAlpha by animateFloatAsState(
        targetValue = if (bottom && state.canScrollForward) 1f else 0f,
        label = "bottomFade",
    )
    return this.drawWithContent {
        drawContent()
        val band = height.toPx().coerceAtMost(size.height / 2f)
        if (topAlpha > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = topAlpha), Color.Transparent),
                    startY = 0f,
                    endY = band,
                ),
                size = Size(size.width, band),
            )
        }
        if (bottomAlpha > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, color.copy(alpha = bottomAlpha)),
                    startY = size.height - band,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - band),
                size = Size(size.width, band),
            )
        }
    }
}

/**
 * The same statement, made sideways, for a row of filter chips that runs off
 * the edge of the screen. Chips are the one place where what is hidden is a
 * *control* rather than content, so the cue matters more here, not less.
 */
@Composable
fun Modifier.fadingEdgesHorizontal(
    state: ScrollableState,
    color: Color = MaterialTheme.colorScheme.background,
    width: Dp = DefaultFadeHeight,
): Modifier {
    val startAlpha by animateFloatAsState(
        targetValue = if (state.canScrollBackward) 1f else 0f,
        label = "startFade",
    )
    val endAlpha by animateFloatAsState(
        targetValue = if (state.canScrollForward) 1f else 0f,
        label = "endFade",
    )
    return this.drawWithContent {
        drawContent()
        val band = width.toPx().coerceAtMost(size.width / 2f)
        if (startAlpha > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(color.copy(alpha = startAlpha), Color.Transparent),
                    startX = 0f,
                    endX = band,
                ),
                size = Size(band, size.height),
            )
        }
        if (endAlpha > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, color.copy(alpha = endAlpha)),
                    startX = size.width - band,
                    endX = size.width,
                ),
                topLeft = Offset(size.width - band, 0f),
                size = Size(band, size.height),
            )
        }
    }
}

/**
 * A scrollbar that is actually there.
 *
 * Android's own is transient by design: it appears while your finger is moving
 * and is gone a moment later, so the question "is there more below this?" --
 * asked *before* you touch anything -- has no answer on screen. That is the
 * question the import picker got wrong, and the fade above answers it softly.
 * This answers the other half: not just "there is more" but *how much* more.
 *
 * Drawn rather than composed, so it costs no layout and cannot steal a touch;
 * the list underneath keeps the full width and the thumb rides over the last
 * few pixels of it.
 */
private val ScrollbarWidth = 4.dp
private val ScrollbarMinThumb = 24.dp

// The thumb alone answers "how far down am I" but not "how far down does this
// go" -- with nothing behind it there is no scale for it to be a fraction of,
// and on a long list a short thumb floating in the margin was being read as a
// stray mark rather than a position. The track supplies the scale. It has to
// stay faint: at the thumb's own weight the two stop being figure and ground
// and the bar reads as a solid rule down the edge of every screen.
private const val ScrollbarThumbAlpha = 0.35f
private const val ScrollbarTrackAlpha = 0.10f

/** Track and thumb: same width, same radius, same inset, drawn as one. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScrollbar(
    color: Color,
    alpha: Float,
    thumbTop: Float,
    thumbHeight: Float,
) {
    val barWidth = ScrollbarWidth.toPx()
    val inset = 2.dp.toPx()
    val x = size.width - barWidth - inset
    val radius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
    // Full height, not the thumb's travel: the track is the whole document,
    // and stopping it short of either end would misstate where the ends are.
    drawRoundRect(
        color = color.copy(alpha = alpha * (ScrollbarTrackAlpha / ScrollbarThumbAlpha)),
        topLeft = Offset(x, 0f),
        size = Size(barWidth, size.height),
        cornerRadius = radius,
    )
    drawRoundRect(
        color = color.copy(alpha = alpha),
        topLeft = Offset(x, thumbTop),
        size = Size(barWidth, thumbHeight),
        cornerRadius = radius,
    )
}

@Composable
fun Modifier.verticalScrollbar(
    state: LazyListState,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
): Modifier {
    // Persistent, but only once there is something to say: on a list that fits,
    // a permanently parked full-height thumb is noise that means nothing.
    val visible by animateFloatAsState(
        targetValue = if (state.canScrollForward || state.canScrollBackward) ScrollbarThumbAlpha else 0f,
        label = "scrollbar",
    )
    return this.drawWithContent {
        drawContent()
        if (visible <= 0f) return@drawWithContent
        val info = state.layoutInfo
        val total = info.totalItemsCount
        val onScreen = info.visibleItemsInfo.size
        if (total <= 0 || onScreen <= 0) return@drawWithContent

        // Proportional on item counts rather than pixels: LazyColumn never
        // measures the rows it has not reached, so a pixel-exact thumb is not
        // available at any price. Rows here are near enough uniform that the
        // approximation is honest about position and length.
        val fraction = (onScreen.toFloat() / total).coerceIn(0f, 1f)
        val minThumb = ScrollbarMinThumb.toPx()
        val thumb = (size.height * fraction).coerceAtLeast(minThumb).coerceAtMost(size.height)
        val travel = size.height - thumb
        val progress = if (total > onScreen) {
            (info.visibleItemsInfo.firstOrNull()?.index ?: 0).toFloat() / (total - onScreen)
        } else {
            0f
        }
        val top = travel * progress.coerceIn(0f, 1f)
        drawScrollbar(color, visible, top, thumb)
    }
}

/**
 * The same bar for a plain `verticalScroll` region.
 *
 * Kept as a separate overload rather than folded into the ScrollableState one
 * because this case can be exact: a ScrollState knows its own maxValue, so
 * both the thumb's length and its position are real measurements rather than
 * the item-count approximation a LazyColumn forces.
 */
@Composable
fun Modifier.verticalScrollbar(
    state: ScrollState,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
): Modifier {
    val visible by animateFloatAsState(
        targetValue = if (state.maxValue > 0) ScrollbarThumbAlpha else 0f,
        label = "scrollbarPlain",
    )
    return this.drawWithContent {
        drawContent()
        if (visible <= 0f || state.maxValue <= 0) return@drawWithContent
        val viewport = size.height
        val content = viewport + state.maxValue
        val minThumb = ScrollbarMinThumb.toPx()
        val thumb = (viewport * (viewport / content)).coerceAtLeast(minThumb).coerceAtMost(viewport)
        val travel = viewport - thumb
        val top = travel * (state.value.toFloat() / state.maxValue).coerceIn(0f, 1f)
        drawScrollbar(color, visible, top, thumb)
    }
}
