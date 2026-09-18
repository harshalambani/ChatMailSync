package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-language parity test for [StateRepository.computeMessageHash]
 * (`State.kt`) against the *real* Python `state.compute_message_hash`.
 *
 * `tools/generate_kotlin_core_golden_fixtures.py`'s `generate_state_hash_golden()`
 * re-parses every parser golden fixture through the real Python parser, plus
 * a hand-written sweep of edge cases (`HASH_SWEEP_EXTRA`: emoji body, emoji
 * sender name, a U+202F narrow no-break space inside the body, an empty
 * body, a multiline body, an attachment-filename-shaped body, and a body
 * containing a literal NUL byte -- proving the hash's own `\x00` field
 * separator can't be confused with real message content), computes the
 * *real* `state.compute_message_hash` for every one, and writes the inputs
 * plus expected hashes as `state_hash_golden.json`. This is the "golden
 * sweep" proof required for the compute_message_hash byte-exact-parity
 * invariant -- see the PR body.
 *
 * This test reads that same JSON from the classpath and re-runs the
 * *Kotlin* hash function over each entry's exact input, asserting the hash
 * output is byte-identical to what Python produced. There is no equivalent
 * standalone Python test for this comparison (it is inherently
 * cross-language); this file is new, not a port of an existing Python test.
 */
class StateHashGoldenParityTest {

    private fun readGoldenResource(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("golden/$name")
            ?: error("golden resource not found on test classpath: golden/$name")
        return stream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    @Test
    fun computeMessageHashMatchesThePythonImplementationForEveryGoldenEntry() {
        val root = parseJson(readGoldenResource("state_hash_golden.json")).asObj()
        val entries = root["entries"].asArr().items.map { it.asObj() }

        // Sanity check the fixture itself isn't accidentally empty -- a
        // vacuously-passing loop over zero entries would not actually prove
        // anything about hash parity.
        assertTrue("expected a non-trivial golden sweep", entries.size >= 20)

        for ((i, entry) in entries.withIndex()) {
            val chatId = entry["chatId"].asString()
            val timestampIso = entry["timestampIso"].asString()
            val sender = entry["sender"].asString()
            val body = entry["body"].asString()
            val expectedHash = entry["hash"].asString()

            val actualHash = StateRepository.computeMessageHash(chatId, timestampIso, sender, body)
            assertEquals(
                "entry[$i] (chatId='$chatId', timestampIso='$timestampIso', sender='$sender')",
                expectedHash,
                actualHash,
            )
        }
    }
}
