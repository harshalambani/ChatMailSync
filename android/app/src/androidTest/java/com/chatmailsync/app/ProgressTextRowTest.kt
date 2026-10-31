package com.chatmailsync.app

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** BUG-20: a long progress text must not squeeze the percent into a one-character column. */
@RunWith(AndroidJUnit4::class)
class ProgressTextRowTest {
    @get:Rule
    val rule = createComposeRule()

    private val longText =
        "Rohan Mehta and Meera Iyer - trip plan and family photos - 11 / 15 messages synced so far"

    private fun layoutOf(tag: String): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(out) }
        return out.first()
    }

    private fun render() {
        rule.setContent {
            ProgressTextRow(text = longText, percentLabel = "73%", modifier = Modifier.width(200.dp))
        }
    }

    // NEGATIVE: the percent must never wrap onto several lines.
    @Test
    fun percentStaysOnOneLineBesideLongText() {
        render()
        assertEquals(1, layoutOf(PROGRESS_PERCENT_TAG).lineCount)
    }

    // NEGATIVE: the percent is not cut short: the whole string is on the one visible line, not ellipsised.
    @Test
    fun percentIsShownInFull() {
        render()
        rule.onNodeWithTag(PROGRESS_PERCENT_TAG).assertIsDisplayed()
        val layout = layoutOf(PROGRESS_PERCENT_TAG)
        assertEquals("73%", layout.layoutInput.text.text)
        assertTrue(!layout.isLineEllipsized(0))
        assertEquals("73%".length, layout.getLineEnd(0, visibleEnd = true))
    }

    @Test
    fun longTextIsStillShownAndWrapsOnItsOwnLines() {
        render()
        rule.onNodeWithTag(PROGRESS_TEXT_TAG).assertIsDisplayed()
        val layout = layoutOf(PROGRESS_TEXT_TAG)
        assertEquals(longText, layout.layoutInput.text.text)
        assertTrue(layout.lineCount > 1)
    }
}
