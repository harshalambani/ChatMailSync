package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which bottom tab lights up for a given route.
 *
 * The bug this guards: the sync log was mapped to Settings, on the assumption
 * that Settings was the only way into it. It is not — the status card on Home
 * and the always-visible sync bar both open it too, so two thirds of the time
 * the bar lit a tab the user had never touched.
 *
 * Plain JUnit, no Robolectric: tabForRoute takes a String and touches nothing.
 * The rule to keep in mind when adding a route here is that a sub-screen may
 * only claim a tab if that tab is its *only* entry point.
 */
class TabForRouteTest {

    @Test
    fun `the three top-level routes light their own tabs`() {
        assertEquals("home", tabForRoute("home"))
        assertEquals("chats", tabForRoute("chats"))
        assertEquals("settings", tabForRoute("settings"))
    }

    @Test
    fun `single-entry sub-screens light the tab they were opened from`() {
        // These are reachable one way only, so claiming a tab is truthful.
        assertEquals("home", tabForRoute("syncProgress"))
        assertEquals("home", tabForRoute("importPicker"))
        assertEquals("chats", tabForRoute("chat/Some%20Chat"))
        assertEquals("settings", tabForRoute("mailAccount"))
        assertEquals("settings", tabForRoute("help"))
    }

    @Test
    fun `the sync log lights no tab, at either level`() {
        // The regression. Both the list and the run detail below it.
        assertNull(tabForRoute("syncLog"))
        assertNull(tabForRoute("syncLog/{runId}"))
    }

    @Test
    fun `an unknown route lights nothing rather than guessing`() {
        assertNull(tabForRoute(null))
        assertNull(tabForRoute("someRouteAddedLater"))
    }
}
