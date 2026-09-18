package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [checkConnection] and its small pure helpers
 * ([connectionStagePlan], [formatConnectionResult], [loginFailureHint]),
 * ported from the applicable parts of `tests/test_connection_check.py`.
 *
 * The DNS/TCP/TLS stages open real sockets and have no connection-factory
 * seam to fake in this phase (only the LOGIN stage does, via
 * `ImapTransport`'s `connectionFactory`) -- so this file cannot exercise a
 * full five-stage run without live network access, which is out of scope
 * here (see the PR body; `LiveImapHarness.kt` is the human-run equivalent).
 * What *is* fully covered without any network: the plan/labels helpers, the
 * early-exit validation path, [loginFailureHint]'s three branches, and,
 * crucially, the mandatory "check_connection never leaks the password" test
 * -- proven here for the reachable no-network path, and left as a
 * documented gap for the DNS/TCP/TLS/LOGIN/FOLDER stages, which a human
 * verifies by eye via the live harness.
 */
class ConnectionCheckTest {

    // mapping: test's counterpart in test_connection_check.py exercises
    // connection_stage_plan() directly; ported as-is.
    @Test
    fun connectionStagePlanListsAllFiveStagesInOrder() {
        val plan = connectionStagePlan()

        assertEquals(listOf("DNS", "TCP", "TLS", "LOGIN", "FOLDER"), plan.map { it.name })
        assertTrue(plan.all { it.label.isNotBlank() })
    }

    // Mandatory negative test from the brief: check_connection must never
    // leak the password, even on its very first (pre-network) validation
    // failure -- this is the one path fully reachable without a socket.
    @Test
    fun checkConnectionMissingFieldsNeverLeaksPasswordAndFailsAtDns() {
        val secretPassword = "s3cret-app-password"

        val result = checkConnection(
            host = "   ",
            port = 993,
            email = "meera.iyer@example.com",
            password = secretPassword,
        )

        assertFalse(result.ok)
        assertEquals("DNS", result.failedStage)
        assertFalse(result.message.contains(secretPassword))
        assertEquals("Fill in the server, port, email address and app password first.", result.message)
    }

    @Test
    fun checkConnectionMissingPortAlsoFailsEarlyWithoutLeakingPassword() {
        val secretPassword = "another-secret"

        val result = checkConnection(
            host = "imap.mail.yahoo.com",
            port = 0,
            email = "meera.iyer@example.com",
            password = secretPassword,
        )

        assertFalse(result.ok)
        assertFalse(result.message.contains(secretPassword))
    }

    @Test
    fun formatConnectionResultPrependsFailedStageLabelOnFailure() {
        val result = checkConnection(host = "", port = 993, email = "meera.iyer@example.com", password = "x")

        val formatted = formatConnectionResult(result)

        assertTrue(formatted.startsWith("Finding the server failed."))
    }

    @Test
    fun formatConnectionResultReturnsMessageAsIsOnSuccess() {
        val success = ConnectionResult(
            ok = true,
            stage = "FOLDER",
            failedStage = null,
            message = "All good.",
            stages = emptyList(),
        )

        assertEquals("All good.", formatConnectionResult(success))
    }

    // -----------------------------------------------------------------
    // loginFailureHint -- mirrors src/mail_client.py's login_failure_hint,
    // gmail_like and microsoft_like tests.
    // -----------------------------------------------------------------

    @Test
    fun loginFailureHintRecognisesGmailHost() {
        val hint = loginFailureHint("imap.gmail.com", "meera.iyer@example.com")
        assertTrue(hint.contains("app password") || hint.contains("Gmail") || hint.contains("gmail"))
    }

    @Test
    fun loginFailureHintRecognisesGmailAddressEvenOnAThirdPartyHost() {
        val hint = loginFailureHint("mail.example.net", "meera.iyer@gmail.com")
        assertTrue(hint.contains("Gmail") || hint.contains("gmail"))
    }

    @Test
    fun loginFailureHintDoesNotFalsePositiveOnLookalikeGmailDomain() {
        val hint = loginFailureHint("imap.notgmail.com.example.net", "meera.iyer@notgmail.com.example.net")
        assertFalse(hint.contains("Gmail") || hint.contains("gmail"))
    }

    @Test
    fun loginFailureHintRecognisesMicrosoftHost() {
        val hint = loginFailureHint("outlook.office365.com", "meera.iyer@example.com")
        assertTrue(hint.contains("OAuth") || hint.contains("Microsoft") || hint.contains("microsoft"))
    }

    @Test
    fun loginFailureHintFallsBackToGenericMessageForOtherProviders() {
        val hint = loginFailureHint("imap.mail.yahoo.com", "meera.iyer@yahoo.com")
        assertFalse(hint.contains("Gmail") || hint.contains("gmail"))
        assertFalse(hint.contains("Microsoft") || hint.contains("microsoft"))
        assertTrue(hint.isNotBlank())
    }

    // -----------------------------------------------------------------
    // Not ported -- out of scope for this phase (see PR body):
    //
    // - The full DNS/TCP/TLS/LOGIN/FOLDER staged run tests in
    //   test_connection_check.py that monkeypatch socket/ssl/imaplib at the
    //   module level: there is no connection-factory seam for the raw-socket
    //   DNS/TCP/TLS probe stages in this Kotlin port (only ImapTransport's
    //   LOGIN stage is fakeable), so those stages are exercised only by a
    //   human via LiveImapHarness.kt against a real Yahoo mailbox.
    // - test_worst_case_across_all_five_stages_stays_well_under_the_ci_budget
    //   (a timing/perf assertion tied to the Python monkeypatched-stage
    //   harness; not meaningful without that harness).
    // -----------------------------------------------------------------
}
