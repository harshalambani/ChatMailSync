package com.chatmailsync.app

import java.net.URLDecoder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-17: a custom host name never goes into the Google help search link. */
class AppPasswordSearchUrlTest {

    private val customHost = "mail.private-example.test"

    @Test
    fun customHostIsAbsentFromTheSearchUrl() {
        val url = buildAppPasswordSearchUrl("custom", "Custom", customHost)
        val decoded = URLDecoder.decode(url, "UTF-8")
        assertFalse(url, url.contains(customHost))
        assertFalse(decoded, decoded.contains(customHost))
        assertTrue(url, url.startsWith("https://www.google.com/search?q="))
    }

    // The clipboard copy is the user's own, so it still names the host.
    @Test
    fun clipboardPromptStillNamesTheCustomHost() {
        assertTrue(buildAppPasswordPrompt("custom", "Custom", customHost).contains(customHost))
    }

    @Test
    fun presetProviderSearchStillNamesTheProvider() {
        val decoded = URLDecoder.decode(buildAppPasswordSearchUrl("yahoo", "Yahoo", "imap.mail.yahoo.com"), "UTF-8")
        assertTrue(decoded, decoded.contains("Yahoo"))
    }
}
