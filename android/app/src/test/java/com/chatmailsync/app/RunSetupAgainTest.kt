package com.chatmailsync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Batch 5b item 7: "Run setup again" (Advanced settings) must open the
 * first-run walkthrough without clearing anything. There is no Robolectric
 * here to click the button and inspect prefs afterwards, so this pins it
 * the same way FrozenIdentifiersTest.kt pins its identifiers: by reading
 * MainActivity.kt's source and checking the call site is exactly the bare
 * navigate it is meant to be, with no prefs write anywhere near it.
 */
class RunSetupAgainTest {

    private fun mainActivitySource(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java/com/chatmailsync/app/MainActivity.kt")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("could not locate MainActivity.kt from ${File("").absolutePath}")
    }

    @Test
    fun `Run setup again is a bare navigate, with nothing else on the line`() {
        val text = mainActivitySource()
        val line = text.lines().firstOrNull { it.contains("onRunSetupAgain = {") }
        assertTrue("onRunSetupAgain wiring not found in MainActivity.kt", line != null)
        assertTrue(
            "onRunSetupAgain must be exactly navController.navigate(\"first_run\"), was: $line",
            line!!.trim() == """onRunSetupAgain = { navController.navigate("first_run") },""",
        )
    }

    @Test
    fun `opening Run setup again never calls setFirstRunDone(false)`() {
        // The failure this guards: someone "fixing" Run setup again to feel
        // more like a real reset by clearing the first-run flag on the way
        // in, which would also make a fresh install's own first launch look
        // indistinguishable from a replay one line away.
        val text = mainActivitySource()
        assertFalse(
            "MainActivity.kt must never call setFirstRunDone(false)",
            text.contains("setFirstRunDone(false)"),
        )
    }
}
