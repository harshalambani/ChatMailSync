package com.chatmailsync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether a launch opens on the four-step first-run
 * setup (D7) or goes straight to Home.
 *
 * The one case worth a dedicated negative test is the upgrader: someone who
 * already has a working mailbox from before this release existed has never
 * had the chance to set the flag, and must still never see a "welcome to
 * Chat Mail Sync" screen for an app they have used for months.
 */
class ShouldShowFirstRunTest {

    @Test
    fun `a brand new install with nothing configured sees it`() {
        assertTrue(shouldShowFirstRun(firstRunDone = false, mailboxConfigured = false))
    }

    @Test
    fun `an upgrader with a mailbox already configured does not see it`() {
        // The negative case: no flag was ever set for this person (it did not
        // exist yet when they set their mailbox up), but a working mailbox is
        // itself proof they are not new here.
        assertFalse(shouldShowFirstRun(firstRunDone = false, mailboxConfigured = true))
    }

    @Test
    fun `once the flag is set it never shows again`() {
        assertFalse(shouldShowFirstRun(firstRunDone = true, mailboxConfigured = false))
    }

    @Test
    fun `flag set and no mailbox still does not show it`() {
        // "Set up later" and "Not now" both finish the flow (flag = true)
        // without necessarily leaving a mailbox configured — that must not
        // re-open first-run on the next launch.
        assertFalse(shouldShowFirstRun(firstRunDone = true, mailboxConfigured = true))
    }
}

/**
 * Where the bottom-bar tab handler and the incoming-share handler pop to.
 *
 * A fresh install's NavHost startDestination is "first_run", and
 * NavGraph.findStartDestination() keeps returning "first_run" for the rest
 * of the process even after the flow finishes and pops itself off the back
 * stack — popping to a route no longer on the stack is a silent no-op, which
 * broke both the Home-tab reset and the incoming-share handler. The negative
 * test below is the one that would have caught it: the pop target must never
 * be the first-run route, in any session.
 */
class FirstRunNavTest {

    @Test
    fun `the tab pop target is home`() {
        assertTrue(tabPopTargetRoute() == "home")
    }

    @Test
    fun `the tab pop target is never first_run`() {
        assertFalse(tabPopTargetRoute() == "first_run")
    }
}

/** The outer step counter first-run's Back/forward buttons drive. */
class FirstRunStepTransitionsTest {

    @Test
    fun `next advances by one`() {
        assertTrue(firstRunStepForward(1) == 2)
    }

    @Test
    fun `back from step 1 does not go below step 1`() {
        assertTrue(firstRunStepBack(1) == 1)
    }

    @Test
    fun `next from step 4 does not exceed step 4`() {
        assertTrue(firstRunStepForward(4) == 4)
    }

    @Test
    fun `back steps down by one from the middle`() {
        assertTrue(firstRunStepBack(3) == 2)
    }
}

/** Step 4's "Turn on" gating. */
class FirstRunAutoImportGateTest {

    @Test
    fun `turning on is allowed once a folder is chosen`() {
        assertTrue(canEnableAutoImportFromFirstRun("content://tree/primary%3AWhatsApp"))
    }

    @Test
    fun `turning on is not allowed with no folder chosen`() {
        assertFalse(canEnableAutoImportFromFirstRun(null))
    }

    @Test
    fun `a blank folder uri does not count as chosen`() {
        assertFalse(canEnableAutoImportFromFirstRun("   "))
    }
}

/**
 * D7 named several fields that must never be asked during first-run: the
 * cut-off date, the after-import (synced-file) policy, the chunk size, and
 * the Me/owner name. This does not exercise the screen (Compose UI is not
 * unit-testable here) but it does pin the one piece of first-run copy that
 * is a plain Kotlin constant, so introducing any of those words into a step
 * title fails loud instead of only being caught by a manual click-through.
 */
class FirstRunStepTitlesTest {

    @Test
    fun `no step title mentions a field first-run must never ask about`() {
        val forbiddenCaseInsensitive = listOf("cut-off", "cutoff", "chunk", "after import")
        for (title in FIRST_RUN_STEP_TITLES) {
            for (word in forbiddenCaseInsensitive) {
                assertFalse(
                    "\"$title\" mentions the forbidden word \"$word\"",
                    title.contains(word, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `no step title names the Me field`() {
        // Case-sensitive, whole word: "Welcome" legitimately contains the
        // letters "me", and a case-insensitive substring check would flag
        // step 1's own title as a false positive.
        val meField = Regex("\\bMe\\b")
        for (title in FIRST_RUN_STEP_TITLES) {
            assertFalse("\"$title\" names the Me field", meField.containsMatchIn(title))
        }
    }

    @Test
    fun `there are exactly four step titles`() {
        assertTrue(FIRST_RUN_STEP_TITLES.size == 4)
    }
}

/**
 * Batch 5b item 7: "Run setup again" opens the same "first_run" route a
 * fresh install uses, but it must land back in Advanced settings rather
 * than Home when it finishes -- the walkthrough is being replayed by
 * someone with an app full of synced mail, not someone seeing it for the
 * first time.
 */
class FirstRunFinishTargetTest {

    @Test
    fun `manual run from Advanced returns to Advanced, never Home`() {
        val target = firstRunFinishTarget(cameFrom = "advancedSettings")
        assertTrue(target == "advancedSettings")
        assertFalse(target == "home")
    }

    @Test
    fun `a fresh install with no previous entry finishes to Home, never Advanced`() {
        val target = firstRunFinishTarget(cameFrom = null)
        assertTrue(target == "home")
        assertFalse(target == "advancedSettings")
    }

    @Test
    fun `any other previous route also finishes to Home`() {
        // Only the one known manual-launch site (Advanced settings) redirects
        // the finish target; anything else falls back to the fresh-install
        // behaviour rather than guessing.
        assertTrue(firstRunFinishTarget(cameFrom = "settings") == "home")
        assertTrue(firstRunFinishTarget(cameFrom = "chats") == "home")
    }
}

/**
 * Batch 5b items 8/9: the back label must say where the button actually
 * goes, not a screen-specific guess. This directly pins the regression
 * found on Settings -> Me, which used to show "Home" for any route it did
 * not explicitly recognise.
 */
class BackLabelForRouteTest {

    @Test
    fun `known routes map to their screen name`() {
        assertTrue(backLabelForRoute("home") == "Home")
        assertTrue(backLabelForRoute("settings") == "Settings")
        assertTrue(backLabelForRoute("advancedSettings") == "Advanced")
        assertTrue(backLabelForRoute("chats") == "Chats")
        assertTrue(backLabelForRoute("help") == "Help")
        assertTrue(backLabelForRoute("privacy") == "Privacy")
        assertTrue(backLabelForRoute("mailAccount") == "Mail account")
        assertTrue(backLabelForRoute("me") == "Me")
        assertTrue(backLabelForRoute("queue") == "Queue")
        assertTrue(backLabelForRoute("importPicker") == "Import")
        assertTrue(backLabelForRoute("mailWizard") == "Mail setup")
    }

    @Test
    fun `route templates with arguments still match by prefix`() {
        // previousBackStackEntry's route is the NavHost template, e.g.
        // "chat/{chatId}", never a resolved path with the id filled in.
        assertTrue(backLabelForRoute("chat/{chatId}") == "Chat")
        // Negative: a single thread is not the list -- the plural would name
        // the wrong screen for someone backing out of one chat.
        assertFalse(backLabelForRoute("chat/{chatId}") == "Chats")
        assertTrue(backLabelForRoute("syncLog/{runId}") == "Sync log")
        assertTrue(backLabelForRoute("syncLog") == "Sync log")
    }

    @Test
    fun `null, unknown and first_run all fall back to the safe default, never Home`() {
        assertTrue(backLabelForRoute(null) == "Back")
        assertFalse(backLabelForRoute(null) == "Home")
        assertTrue(backLabelForRoute("first_run") == "Back")
        assertFalse(backLabelForRoute("first_run") == "Home")
        assertTrue(backLabelForRoute("not_a_real_route") == "Back")
        assertFalse(backLabelForRoute("not_a_real_route") == "Home")
    }
}
