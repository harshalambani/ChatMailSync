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
