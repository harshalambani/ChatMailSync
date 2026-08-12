package com.wamailsync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin half of the OAuth-visibility contract.
 *
 * These are the same rows tests/test_config.py asserts against
 * config.oauth_is_visible. If a row here changes without the matching row
 * there changing, the two platforms disagree about who is offered Google
 * sign-in and exactly one of them is wrong.
 *
 * This is the first Kotlin test in the repo, and it is deliberately plain
 * JUnit with no Robolectric: AppPrefs.oauthIsVisible takes three plain values
 * and touches no Context, which is precisely what makes asserting it possible
 * without standing up an Android runtime to check one boolean. Everything else
 * in AppPrefs needs a Context and stays covered by the device checklist.
 */
class OauthVisibilityTest {

    @Test
    fun `a fresh user is never offered oauth`() {
        // The whole point of the v1.6.0 demotion: no saved choice, no
        // connected account, no unlock -> the option does not exist for
        // this person, because the sign-in behind it expires 7 days after
        // Google grants it.
        assertFalse(AppPrefs.oauthIsVisible(null, null, false))
    }

    @Test
    fun `an explicitly saved oauth backend keeps the option`() {
        assertTrue(
            AppPrefs.oauthIsVisible(AppPrefs.MAIL_BACKEND_GMAIL_OAUTH, null, false)
        )
    }

    @Test
    fun `a connected account is evidence of prior oauth use`() {
        // The Android equivalent of the desktop's token.json. Without this,
        // an existing OAuth user would be shown a backend they cannot select.
        assertTrue(AppPrefs.oauthIsVisible(null, "someone@example.com", false))
    }

    @Test
    fun `the unlock flag reveals oauth`() {
        assertTrue(AppPrefs.oauthIsVisible(null, null, true))
    }

    @Test
    fun `imap alone does not reveal oauth`() {
        assertFalse(AppPrefs.oauthIsVisible(AppPrefs.MAIL_BACKEND_IMAP, null, false))
    }

    @Test
    fun `a latched user who switches to imap keeps the option`() {
        // The one-way trap the latch exists to close. Their saved backend is
        // now imap and signing out cleared the connected email, so the unlock
        // flag is the only thing left holding the door open -- and it has to
        // be enough on its own.
        assertTrue(AppPrefs.oauthIsVisible(AppPrefs.MAIL_BACKEND_IMAP, null, true))
    }
}
