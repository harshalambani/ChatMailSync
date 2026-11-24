package com.chatmailsync.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chatmailsync.app.AppScreenSupport.has
import com.chatmailsync.app.AppScreenSupport.tap
import com.chatmailsync.app.AppScreenSupport.waitForText
import com.chatmailsync.app.AppScreenSupport.waitGone
import com.chatmailsync.core.mail.StateRepository
import com.chatmailsync.core.mail.extractChatInfo
import com.chatmailsync.core.mail.parseFile
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * KT-13 step 3: the one rememberSaveable in MainActivity is the snoozed "same chat?" pairs
 * (UX-12). A real rotation (the activity is torn down and rebuilt) on the real MainActivity: a question is
 * snoozed with "Ask me later", the phone turns, and the question must stay hidden while Home still
 * shows the file as waiting. A plain `remember` there brings the dialog back and fails this test.
 *
 * Synthetic data, placeholder names. The export's messages are dated and worded so that no other test's
 * demo export matches the archived fingerprints seeded here (that would raise a question in their runs).
 */
@RunWith(AndroidJUnit4::class)
class LinkSnoozeRotationUiTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        AppScreenSupport.clearInbox()
        seedRenamedChat()
    }

    @After
    fun tearDown() {
        scenario?.let { AppScreenSupport.unrotate(it) }
        scenario?.close()
        AppScreenSupport.clearInbox()
        AppScreenSupport.prefs(firstRunDone = true)
    }

    /**
     * An archived chat "Rohan Mehta" whose stored fingerprints match an export now waiting in the inbox under
     * the name "Rohan M": core.linkProposals() then returns one RENAME question.
     */
    private fun seedRenamedChat() {
        val text = listOf(
            "25/04/25, 09:00 - Meera Iyer: Snooze check one.",
            "25/04/25, 09:01 - Rohan Mehta: Snooze check two.",
            "25/04/25, 09:02 - Meera Iyer: Snooze check three.",
        ).joinToString("\n") + "\n"
        val export = File(ChatMailApplication.inboxDir(AppScreenSupport.context), "WhatsApp Chat with Rohan M.txt")
        export.writeText(text)

        val oldName = "WhatsApp Chat with Rohan Mehta.txt"
        val oldId = extractChatInfo(oldName).chatId
        val db = ChatMailApplication.core(AppScreenSupport.context).paths.stateDbPath
        val repo = StateRepository { AndroidStateDb(db) }
        repo.initDb()
        repo.upsertChat(oldId, "Rohan Mehta", oldName)
        val run = repo.startSyncRun(oldId)
        val entries = parseFile(export, oldId).toList().map {
            StateRepository.HashEntry(
                StateRepository.computeMessageHash(oldId, it.timestampIso, it.sender, it.body, it.occurrence),
                oldId,
                it.timestampIso,
                run,
            )
        }
        repo.insertMessageHashes(entries)
        repo.completeSyncRun(run, "2025-04-25T09:02:00", null, entries.size, entries.size, 0, 0)
    }

    @Test
    fun aSnoozedLinkQuestionStaysSnoozedAcrossARotation() {
        AppScreenSupport.prefs(firstRunDone = true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitForText("Is this the same chat?")
        rule.tap("Ask me later")
        rule.waitGone("Is this the same chat?")
        // Snoozed, not answered: Home still lists the export as waiting.
        rule.waitForText(WAITING_ROW_TEXT)

        AppScreenSupport.rotate(scenario!!, rule)

        // The rebuilt screen has re-read the questions (the waiting row is back) ...
        rule.waitForText(WAITING_ROW_TEXT)
        // NEGATIVE: ... and the snooze survived, so the dialog is not asked again.
        assertFalse("the snoozed question came back after a rotation", rule.has("Is this the same chat?"))
    }
}
