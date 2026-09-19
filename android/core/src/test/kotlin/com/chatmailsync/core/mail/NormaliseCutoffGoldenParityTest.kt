package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Cross-language parity test for [normaliseCutoff] against the *real*
 * Python `state.normalise_cutoff` -- held fix (b): normaliseCutoff must be
 * exactly as lenient as the Python parsing, including exactly what it
 * REJECTS, not just what it accepts.
 *
 * `tools/generate_kotlin_core_golden_fixtures.py`'s `generate_cutoff_golden`
 * runs the real `state.normalise_cutoff` over a table of inputs -- padded
 * and unpadded dates, leading/trailing whitespace, a full ISO timestamp,
 * invalid calendar dates (month 13, Feb 30, Feb 29 on a non-leap year,
 * month 0, day 32, April 31), two-digit/non-4-digit years, empty string,
 * `None`, and garbage -- and records either the normalised result or that
 * Python raised, as `cutoff_golden.json`. This test reads that JSON and
 * asserts the Kotlin [normaliseCutoff] produces the same accept/reject
 * decision, and the same normalised output when it accepts, for every
 * single recorded input.
 */
class NormaliseCutoffGoldenParityTest {

    private fun readGoldenResource(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("golden/$name")
            ?: error("golden resource not found on test classpath: golden/$name")
        return stream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    @Test
    fun normaliseCutoffMatchesThePythonImplementationForEveryGoldenEntry() {
        val root = parseJson(readGoldenResource("cutoff_golden.json")).asObj()
        val entries = root["entries"].asArr().items.map { it.asObj() }
        assertTrue("expected a non-trivial golden sweep", entries.size >= 20)

        val mismatches = mutableListOf<String>()

        for (entry in entries) {
            val input = entry["input"].asStringOrNull()
            val ok = entry["ok"].asBoolean()

            if (ok) {
                val expected = entry["result"].asStringOrNull()
                try {
                    val actual = normaliseCutoff(input)
                    if (actual != expected) {
                        mismatches.add("input=${reprForReport(input)}: python=$expected kotlin=$actual")
                    }
                } catch (e: Exception) {
                    mismatches.add(
                        "input=${reprForReport(input)}: python accepted -> $expected" +
                            " but kotlin threw ${e::class.simpleName}: ${e.message}",
                    )
                }
            } else {
                try {
                    val actual = normaliseCutoff(input)
                    mismatches.add(
                        "input=${reprForReport(input)}: python REJECTED this input" +
                            " but kotlin accepted it and returned $actual",
                    )
                } catch (e: Exception) {
                    // Kotlin throwing anything at all for an input Python rejects is the
                    // contract, matching the TimeOfDayGoldenParityTest pattern -- we don't
                    // require the same exception class/message, only that it throws.
                }
            }
        }

        if (mismatches.isNotEmpty()) {
            fail(
                "normaliseCutoff diverged from Python's normalise_cutoff on " +
                    "${mismatches.size}/${entries.size} golden entries:\n" +
                    mismatches.joinToString("\n"),
            )
        }
    }

    /** Escapes non-printable/whitespace characters so a mismatch message stays one line. */
    private fun reprForReport(s: String?): String {
        if (s == null) return "null"
        return s.map { c ->
            when {
                c == '\t' -> "\\t"
                c == '\n' -> "\\n"
                c == '\r' -> "\\r"
                c.code in 0x20..0x7E -> c.toString()
                else -> "\\u%04x".format(c.code)
            }
        }.joinToString("", prefix = "\"", postfix = "\"")
    }
}
