package com.chatmailsync.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chatmailsync.app.AppScreenSupport.hop
import com.chatmailsync.app.AppScreenSupport.openTab
import com.chatmailsync.app.AppScreenSupport.waitForText
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * KT-13 step 1: opens every route MainActivity declares, by tapping through the real UI from a launched
 * activity (no navigation hook, no fake). Each hop waits until the screen it left is gone and the new
 * one's own text is there, so a route that fails to open, or opens the wrong screen, fails here.
 * The `@Covers` names are checked against MainActivity's `composable(...)` list by RouteCoverageGuardTest.
 *
 * Fresh install state: no mailbox, first run answered (except first_run itself), a seeded chat with one
 * completed run, and demo exports in the inbox. Synthetic data, placeholder names.
 */
@RunWith(AndroidJUnit4::class)
class RouteSmokeTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        AppScreenSupport.clearInbox()
        AppScreenSupport.seedChatAndRun()
    }

    @After
    fun tearDown() {
        scenario?.close()
        AppScreenSupport.clearInbox()
        AppScreenSupport.prefs(firstRunDone = true, dryRun = false)
    }

    private fun launch(firstRunDone: Boolean = true, dryRun: Boolean = false, exports: Int = 0) {
        AppScreenSupport.prefs(firstRunDone, dryRun)
        if (exports > 0) AppScreenSupport.seedInbox(exports)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun atHome() = rule.waitForText("PRIVATE MAIL ARCHIVE") // the top bar upper-cases its eyebrow line

    @Covers("first_run")
    @Test
    fun firstRunOpensOnAFreshInstall() {
        launch(firstRunDone = false)
        rule.waitForText("Get started")
    }

    @Covers("home")
    @Test
    fun homeOpens() {
        launch()
        atHome()
        rule.openTab("Home")
    }

    @Covers("importPicker")
    @Test
    fun importPickerOpensFromHome() {
        launch()
        atHome()
        rule.hop("Choose exports to import", leaves = "PRIVATE MAIL ARCHIVE", arrives = "Import exports")
    }

    @Covers("queue")
    @Test
    fun queueOpensFromHomeWhenManyExportsWait() {
        launch(exports = 6)
        atHome()
        rule.hop("Show all 6 \u2013 manage the queue", leaves = "PRIVATE MAIL ARCHIVE", arrives = "Sync queue")
    }

    @Covers("syncProgress")
    @Test
    fun syncProgressOpensFromATestRun() {
        // Dry run: the app's own "Run test sync" path, no mailbox and no network involved.
        launch(dryRun = true, exports = 1)
        atHome()
        rule.hop("Run test sync", leaves = "PRIVATE MAIL ARCHIVE", arrives = "Sync progress")
    }

    @Covers("mailWizard")
    @Test
    fun mailWizardOpensFromHome() {
        launch()
        atHome()
        rule.hop("Set up", leaves = "PRIVATE MAIL ARCHIVE", arrives = "Set up your mailbox")
    }

    @Covers("chats")
    @Test
    fun chatsOpensFromTheTab() {
        launch()
        atHome()
        rule.openTab("Chats")
        rule.waitForText(AppScreenSupport.CHAT_NAME)
    }

    @Covers("chat/{chatId}")
    @Test
    fun chatDetailOpensFromTheList() {
        launch()
        atHome()
        rule.openTab("Chats")
        rule.waitForText(AppScreenSupport.CHAT_NAME)
        rule.hop(AppScreenSupport.CHAT_NAME, leaves = "Filter chats\u2026", arrives = "Reset (forget sync history)")
    }

    @Covers("settings")
    @Test
    fun settingsOpensFromTheTab() {
        launch()
        atHome()
        rule.openTab("Settings")
        rule.waitForText("Theme")
    }

    private fun inSettings() {
        launch()
        atHome()
        rule.openTab("Settings")
        rule.waitForText("Theme")
    }

    @Covers("mailAccount")
    @Test
    fun mailAccountOpensFromSettings() {
        inSettings()
        rule.hop("Mail account", leaves = "Theme", arrives = "Set up again, step by step")
    }

    @Covers("me")
    @Test
    fun meOpensFromSettings() {
        inSettings()
        rule.hop("Your messages", leaves = "Theme", arrives = "Your WhatsApp profile name")
    }

    @Covers("backupRestore")
    @Test
    fun backupRestoreOpensFromSettings() {
        inSettings()
        rule.hop("Backup & restore", leaves = "Theme", arrives = "Save a backup")
    }

    @Covers("advancedSettings")
    @Test
    fun advancedOpensFromSettings() {
        inSettings()
        rule.hop("Advanced", leaves = "Theme", arrives = "Run setup again")
    }

    @Covers("syncLog")
    @Test
    fun syncLogOpensFromAdvanced() {
        inSettings()
        rule.hop("Advanced", leaves = "Theme", arrives = "Run setup again")
        rule.hop("Sync log", leaves = "Run setup again", arrives = "LAST 90 DAYS")
    }

    @Covers("syncLog/{runId}")
    @Test
    fun syncRunDetailOpensFromTheLog() {
        inSettings()
        rule.hop("Advanced", leaves = "Theme", arrives = "Run setup again")
        rule.hop("Sync log", leaves = "Run setup again", arrives = "LAST 90 DAYS")
        rule.waitForText(AppScreenSupport.CHAT_NAME)
        rule.hop(AppScreenSupport.CHAT_NAME, leaves = "LAST 90 DAYS", arrives = "Back to sync log", firstMatch = true)
    }

    @Covers("helpAbout")
    @Test
    fun helpAboutOpensFromSettings() {
        inSettings()
        rule.hop("Help", leaves = "Theme", arrives = "Third-party notices")
    }

    private fun inHelpAbout() {
        inSettings()
        rule.hop("Help", leaves = "Theme", arrives = "Third-party notices")
    }

    @Covers("help")
    @Test
    fun helpAndFaqOpensFromHelp() {
        inHelpAbout()
        rule.hop("Help & FAQ", leaves = "Third-party notices", arrives = "Help & FAQ")
    }

    @Covers("privacy")
    @Test
    fun privacyOpensFromHelp() {
        inHelpAbout()
        rule.hop("Privacy policy", leaves = "Third-party notices", arrives = "Open in browser")
    }

    @Covers("licenses")
    @Test
    fun licencesOpenFromHelp() {
        inHelpAbout()
        rule.hop("Open-source licences", leaves = "Third-party notices", arrives = "Open-source licences")
    }
}
