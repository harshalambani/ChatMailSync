package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The Kotlin half of the connection-status contract (Batch G).
 *
 * These rows are the same judgement gui._auth_display makes on Windows, and
 * tests/test_connection_status.py asserts them there. If a row here changes
 * without the matching row there changing, the two products are telling the
 * same user two different things about the same mailbox.
 *
 * Plain JUnit, no Robolectric, for the same reason OauthVisibilityTest is:
 * connectionStatusOf takes two plain values and touches no Context. The
 * ConnectionState singleton around it does need one and stays covered by the
 * device checklist.
 */
class ConnectionStatusTest {

    @Test
    fun `no account beats any stored verdict`() {
        // A green dot over no credentials is the worst of the four lies
        // available here, so the account decides if the two ever disagree.
        assertEquals(ConnectionStatus.NONE, connectionStatusOf(false, null))
        assertEquals(ConnectionStatus.NONE, connectionStatusOf(false, true))
        assertEquals(ConnectionStatus.NONE, connectionStatusOf(false, false))
    }

    @Test
    fun `saved but never attempted is unverified, not connected`() {
        // The entire point of the batch: "saved" and "works" are different
        // facts, and this is the one that used to be reported as the other.
        // Every install upgrading into this release lands exactly here.
        assertEquals(ConnectionStatus.UNVERIFIED, connectionStatusOf(true, null))
    }

    @Test
    fun `the last attempt decides once there has been one`() {
        assertEquals(ConnectionStatus.OK, connectionStatusOf(true, true))
        assertEquals(ConnectionStatus.FAILED, connectionStatusOf(true, false))
    }

    @Test
    fun `every state says something different in words`() {
        // The colour is the fast path, not the message -- roughly one man in
        // twelve cannot tell this green from this red, so the four labels must
        // stand on their own.
        val labels = ConnectionStatus.values().map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        val spoken = ConnectionStatus.values().map { it.contentDescription }
        assertEquals(spoken.size, spoken.toSet().size)
    }

    @Test
    fun `connected and failed are not the same colour`() {
        assertNotEquals(ConnectionStatus.OK.dotColor, ConnectionStatus.FAILED.dotColor)
        assertNotEquals(ConnectionStatus.OK.dotColor, ConnectionStatus.UNVERIFIED.dotColor)
    }
}
