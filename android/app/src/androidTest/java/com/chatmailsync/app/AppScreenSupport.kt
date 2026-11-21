package com.chatmailsync.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.chatmailsync.core.mail.StateRepository
import java.io.File

/**
 * KT-13 step 1: names a route a smoke test opens. A plain unit test
 * (RouteCoverageGuardTest) reads these and fails when MainActivity gains a route nobody opens.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Covers(val route: String)

/**
 * Shared helpers for the route smoke and rotation tests. Everything goes through the app's own public
 * entry points (AppPrefs, ChatMailApplication, StateRepository): no production change, no hook.
 * Synthetic data only, placeholder names.
 */
internal object AppScreenSupport {
    const val WAIT_MS = 15_000L
    const val CHAT_NAME = "Meera Iyer"
    const val CHAT_ID = "meera-iyer-demo"

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun prefs(firstRunDone: Boolean, dryRun: Boolean = false) {
        AppPrefs.setFirstRunDone(context, firstRunDone)
        AppPrefs.setDryRunDefault(context, dryRun)
    }

    private fun exportText(who: String): String = listOf(
        "14/03/25, 10:09 - $who: Shall we go?",
        "14/03/25, 10:10 - Rohan Mehta: Yes, six o'clock.",
    ).joinToString("\n") + "\n"

    /** Writes [n] demo exports into the app's own inbox; returns them. */
    fun seedInbox(n: Int): List<File> = (1..n).map { i ->
        val name = if (i == 1) "WhatsApp Chat with $CHAT_NAME.txt" else "WhatsApp Chat with Demo Chat $i.txt"
        File(ChatMailApplication.inboxDir(context), name).also { it.writeText(exportText(CHAT_NAME)) }
    }

    fun clearInbox() {
        ChatMailApplication.inboxDir(context).listFiles()?.forEach { it.delete() }
    }

    /** One chat with one completed run in the app's own ledger, so the chat and run screens have a row. */
    fun seedChatAndRun() {
        val db = ChatMailApplication.core(context).paths.stateDbPath
        val repo = StateRepository { AndroidStateDb(db) }
        repo.initDb()
        repo.upsertChat(CHAT_ID, CHAT_NAME, "WhatsApp Chat with $CHAT_NAME.txt")
        val run = repo.startSyncRun(CHAT_ID)
        repo.completeSyncRun(run, "2025-03-14T10:10:00", null, 2, 2, 0, 0)
    }

    /** Rotates to landscape and waits until the activity has really been recreated. */
    fun rotate(scenario: ActivityScenario<MainActivity>, rule: ComposeTestRule) {
        var before: MainActivity? = null
        scenario.onActivity { before = it }
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        val deadline = System.currentTimeMillis() + WAIT_MS
        var now: MainActivity? = before
        while (now === before && System.currentTimeMillis() < deadline) {
            scenario.onActivity { now = it }
            Thread.sleep(100)
        }
        check(now !== before) { "the activity was not recreated by the rotation" }
        rule.waitForIdle()
    }

    fun unrotate(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // ---- finding things -----------------------------------------------------------------

    fun ComposeTestRule.has(text: String, substring: Boolean = false) =
        onAllNodes(hasText(text, substring = substring, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty()

    fun ComposeTestRule.waitForText(text: String, substring: Boolean = false) {
        waitUntil(WAIT_MS) { has(text, substring) }
    }

    fun ComposeTestRule.waitGone(text: String, substring: Boolean = false) {
        waitUntil(WAIT_MS) { !has(text, substring) }
    }

    private fun clickable(text: String): SemanticsMatcher = hasText(text) and hasClickAction()

    /** Taps a bottom tab and waits until it is the selected one. */
    fun ComposeTestRule.openTab(label: String) {
        onNode(clickable(label)).performClick()
        waitUntil(WAIT_MS) { onAllNodes(hasText(label) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * One step of navigation: tap [click], then wait until the screen it left ([leaves], text that only the
     * old screen shows) is gone AND the new one ([arrives], text only it shows) is there. Both halves matter:
     * a bare "arrives" can already be true on the old screen when a row and a title share a name.
     */
    fun ComposeTestRule.hop(
        click: String,
        leaves: String,
        arrives: String,
        firstMatch: Boolean = false,
    ) {
        clickText(click, firstMatch)
        waitGone(leaves)
        waitForText(arrives)
    }

    /** Scrolls the node into view when it sits in a scroll container (a fixed screen has none), then taps it. */
    private fun ComposeTestRule.clickText(text: String, first: Boolean = false) {
        val node = if (first) onAllNodes(clickable(text)).onFirst() else onNode(clickable(text))
        try { node.performScrollTo() } catch (_: AssertionError) { /* not in a scroll container: already placed */ }
        node.performClick()
    }

    fun ComposeTestRule.tap(text: String) = clickText(text)
}
