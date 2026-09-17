package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit coverage for the "test connection never returns" fix.
 *
 * [runConnectionCheck] itself needs a real `Handler(Looper.getMainLooper())`
 * loop that isn't exercisable from a plain JVM unit test (no Robolectric
 * here). [runConnectionCheckWithScheduler] exists precisely so the
 * exactly-once/watchdog race it implements *is* reachable from a test: the
 * scheduler (`post`/`postDelayed`/`removeCallbacks`) and the
 * [ConnectionState] write are passed in rather than reached for directly, so
 * a test can supply a same-thread `post` and drive both orderings --
 * watchdog-first and check-first -- deterministically. The one part that
 * still runs on a real background `Thread` is the `check` lambda itself, so
 * those tests synchronize on it with latches instead of assuming ordering.
 *
 * Save & connect (`saveImapSettings` in MainActivity.kt) reuses
 * [runConnectionCheck] directly and is not separately covered here: its own
 * "only persist when the callback says connected == true" gate is a single
 * `if (connected)` block around the AppPrefs/SecretStore writes, so the
 * "failed Save & connect must not mark the password saved" guarantee reduces
 * to "onResult is never called with connected == true unless the check
 * itself reported success" -- which is exactly what the watchdog-first and
 * throwing-check tests below establish for [runConnectionCheckWithScheduler].
 * `saveImapSettings` itself captures Composable-local state and a real
 * `Context`/`SecretStore`/`AppPrefs`, and is not reachable from a
 * Robolectric-free JVM test.
 */
class ConnectionCheckHelpersTest {

    @Test
    fun `the watchdog timeout text says timed out and never blames a specific stage by name`() {
        val text = connectionWatchdogTimeoutText()
        assertTrue(text.contains("timed out", ignoreCase = true))
    }

    @Test
    fun `redactSecretText masks the password when it appears in the text`() {
        val redacted = redactSecretText("Could not connect: auth failed for fake-app-password", "fake-app-password")
        assertFalse(
            "the password itself must not survive redaction",
            redacted.contains("fake-app-password"),
        )
        assertTrue(redacted.contains("********"))
    }

    // Negative: this is the actual requirement from the hard rules -- the
    // password must never reach a produced result string, under any of the
    // ways it could sneak back through the connection-check path (raw
    // exception text, a null/blank secret, or a secret that doesn't happen
    // to occur in this particular message).
    @Test
    fun `the password never appears in redactSecretText output, in any of these cases`() {
        val password = "fake-app-password"
        val cases = listOf(
            "Could not connect: $password",
            "Login failed for user with secret $password rejected",
            "unrelated failure, no secret in this message",
        )
        for (message in cases) {
            val redacted = redactSecretText(message, password)
            assertFalse(
                "password leaked through for input: $message",
                redacted.contains(password),
            )
        }
        // A null or blank secret must be a safe no-op, not a crash.
        assertEquals("unchanged", redactSecretText("unchanged", null))
        assertEquals("unchanged", redactSecretText("unchanged", ""))
    }

    // Negative (GA4.1 / item 5): a check that fails with a non-Exception
    // Throwable -- e.g. something crossing the Chaquopy bridge as an Error --
    // must still reach onResult exactly once, and the password must not be
    // in the text it delivers. Before the `catch (t: Throwable)` in
    // runConnectionCheckWithScheduler (deliberately not `catch (e:
    // Exception)`), this class of failure silently ended the worker thread
    // with the UI never told -- the same class of hang GA4.1 exists to close.
    @Test
    fun `a check that throws a non-Exception Throwable still delivers exactly one result`() {
        val password = "fake-app-password"
        val resultCount = AtomicInteger(0)
        val delivered = CountDownLatch(1)
        var lastConnected = true
        var lastText = ""

        runConnectionCheckWithScheduler(
            password = password,
            post = { it.run() },
            postDelayed = { _, _ -> /* watchdog never fires in this test */ },
            removeCallbacks = { },
            recordConnection = { },
            onResult = { connected, text ->
                resultCount.incrementAndGet()
                lastConnected = connected
                lastText = text
                delivered.countDown()
            },
            check = { throw OutOfMemoryError("simulated bridge crash near $password") },
        )

        assertTrue("onResult was never called", delivered.await(5, TimeUnit.SECONDS))
        assertEquals(1, resultCount.get())
        assertFalse("a thrown check must not report connected", lastConnected)
        assertFalse("the password must not appear in the delivered text", lastText.contains(password))
    }

    // Negative (GA4.1 / item 5): if the watchdog fires before a slow check
    // finishes, exactly one result (the watchdog's timeout text) is
    // delivered -- and when the slow check *does* eventually finish and
    // tries to report its own (different) result, that second attempt must
    // be silently swallowed, not delivered on top of the first.
    @Test
    fun `a never-returning check triggers the watchdog text and does not also deliver a second result`() {
        val password = "fake-app-password"
        val resultCount = AtomicInteger(0)
        val texts = mutableListOf<String>()
        var watchdog: Runnable? = null
        val checkStarted = CountDownLatch(1)
        val allowCheckToFinish = CountDownLatch(1)
        val firstResultDelivered = CountDownLatch(1)

        runConnectionCheckWithScheduler(
            password = password,
            post = { it.run() },
            postDelayed = { _, runnable -> watchdog = runnable },
            removeCallbacks = { },
            recordConnection = { },
            onResult = { _, text ->
                resultCount.incrementAndGet()
                texts.add(text)
                firstResultDelivered.countDown()
            },
            check = {
                checkStarted.countDown()
                // Stands in for a check that never returns in time -- the
                // test controls exactly when it is allowed to finish.
                assertTrue("test setup: check was never released", allowCheckToFinish.await(5, TimeUnit.SECONDS))
                true to "connected, all good, arrived far too late"
            },
        )

        assertTrue("check never started", checkStarted.await(5, TimeUnit.SECONDS))
        // Fire the watchdog before the check has any chance to finish.
        watchdog!!.run()
        assertTrue("watchdog result was never delivered", firstResultDelivered.await(5, TimeUnit.SECONDS))
        assertEquals(1, resultCount.get())
        assertTrue(texts.single().contains("timed out", ignoreCase = true))
        assertFalse("the password must not appear in the watchdog text", texts.single().contains(password))

        // Now let the slow check finish and attempt to deliver its own,
        // different result -- it must be swallowed by the exactly-once gate.
        allowCheckToFinish.countDown()
        Thread.sleep(300) // give the background thread's finally-block post a chance to run, if it were going to
        assertEquals("the late result must not be delivered a second time", 1, resultCount.get())
    }

    // Negative (GA4.1 / item 5): a successful check must not leave the
    // watchdog able to fire afterwards and overwrite the real result with a
    // spurious timeout -- removeCallbacks is expected to be used for this,
    // and this test fails if it is not.
    @Test
    fun `a normal successful check delivers exactly one result and cancels the watchdog`() {
        val resultCount = AtomicInteger(0)
        var removeCallbacksCalled = false
        val delivered = CountDownLatch(1)

        runConnectionCheckWithScheduler(
            password = "fake-app-password",
            post = { it.run() },
            postDelayed = { _, _ -> /* not fired in this test */ },
            removeCallbacks = { removeCallbacksCalled = true },
            recordConnection = { },
            onResult = { connected, _ ->
                resultCount.incrementAndGet()
                assertTrue("a successful check must report connected", connected)
                delivered.countDown()
            },
            check = { true to "connected, all good" },
        )

        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        assertEquals(1, resultCount.get())
        assertTrue("the watchdog must be cancelled once a real result arrives", removeCallbacksCalled)
    }
}
