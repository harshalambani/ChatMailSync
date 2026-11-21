package com.chatmailsync.app

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chatmailsync.app.AppScreenSupport.has
import com.chatmailsync.app.AppScreenSupport.hop
import com.chatmailsync.app.AppScreenSupport.tap
import com.chatmailsync.app.AppScreenSupport.waitForText
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * KT-13 step 1: a real rotation (the activity is torn down and rebuilt) on every screen that keeps
 * state across one. Those are the two that use rememberSaveable: the first-run flow's step and the
 * mailbox wizard's step, provider and email. UX-12's pattern: each test sets state, rotates, and asserts
 * the state is still there; a `remember` where a `rememberSaveable` belongs fails them.
 *
 * Also a negative the wizard documents on purpose: the app password is NOT kept across a rotation
 * (the saved-state bundle is not a safe place for a secret).
 */
@RunWith(AndroidJUnit4::class)
class RotationStateTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        AppScreenSupport.clearInbox()
    }

    @After
    fun tearDown() {
        scenario?.let { AppScreenSupport.unrotate(it) }
        scenario?.close()
        AppScreenSupport.prefs(firstRunDone = true)
    }

    private fun launch(firstRunDone: Boolean) {
        AppScreenSupport.prefs(firstRunDone)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun rotate() = AppScreenSupport.rotate(scenario!!, rule)

    @Test
    fun firstRunKeepsItsStepAcrossARotation() {
        launch(firstRunDone = false)
        rule.waitForText("Get started")
        rule.tap("Get started")
        rule.waitForText("STEP 2 OF 4", substring = true)
        rotate()
        // NEGATIVE (the top bar upper-cases its eyebrow line, hence the capitals): not dropped back to the welcome step.
        rule.waitForText("STEP 2 OF 4", substring = true)
        assertFalse("rotation sent first-run back to Welcome", rule.has("Get started"))
    }

    private fun openWizardAtSignIn() {
        launch(firstRunDone = true)
        rule.waitForText("PRIVATE MAIL ARCHIVE")
        rule.hop("Set up", leaves = "PRIVATE MAIL ARCHIVE", arrives = "Set up your mailbox")
        rule.waitForText("STEP 1 OF 4", substring = true)
    }

    @Test
    fun wizardKeepsTheProviderPickedAcrossARotation() {
        openWizardAtSignIn()
        rule.tap("Yahoo")
        rule.waitForText("imap.mail.yahoo.com", substring = true)
        rotate()
        rule.waitForText("imap.mail.yahoo.com", substring = true)
        assertTrue("still on step 1", rule.has("STEP 1 OF 4", substring = true))
    }

    @Test
    fun wizardKeepsItsStepAndTheEmailAcrossARotation() {
        openWizardAtSignIn()
        rule.tap("Yahoo")
        rule.tap("Next")
        rule.waitForText("STEP 2 OF 4", substring = true)
        rule.tap("I have my app password")
        rule.waitForText("STEP 3 OF 4", substring = true)
        rule.onNode(hasSetTextAction() and hasText("Email address *")).performScrollTo().performTextInput(EMAIL)
        rotate()
        rule.waitForText("STEP 3 OF 4", substring = true)
        // NEGATIVE: the step is not reset to the provider picker, and the typed address is not lost.
        assertFalse(rule.has("STEP 1 OF 4", substring = true))
        rule.onNode(hasSetTextAction() and hasText("Email address *")).assertTextContains(EMAIL, substring = true)
    }

    @Test
    fun wizardDoesNotKeepTheAppPasswordAcrossARotation() {
        openWizardAtSignIn()
        rule.tap("Yahoo")
        rule.tap("Next")
        rule.tap("I have my app password")
        rule.waitForText("STEP 3 OF 4", substring = true)
        rule.onNode(hasSetTextAction() and hasText("App password *")).performScrollTo().performTextInput(SECRET)
        rotate()
        rule.waitForText("STEP 3 OF 4", substring = true)
        // NEGATIVE: the secret is not in the rebuilt screen, shown or masked.
        assertFalse("the app password survived a rotation", rule.has(SECRET, substring = true))
        rule.onNode(hasSetTextAction() and hasText("App password *")).assertTextContains("", substring = true)
        assertFalse(
            "no masked copy either",
            rule.onAllNodes(hasSetTextAction() and hasText("\u2022".repeat(SECRET.length), substring = true))
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private companion object {
        const val EMAIL = "meera.iyer@example.com"
        const val SECRET = "demo-app-password-0000"
    }
}
