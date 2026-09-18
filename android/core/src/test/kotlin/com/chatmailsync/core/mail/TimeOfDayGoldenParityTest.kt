package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Cross-language parity test for [parseTimeOfDay] against the *real*
 * `dateutil.parser.parse` -- the third-party dependency [parseTimeOfDay]
 * hand-rolls a twin of (see that function's KDoc).
 *
 * `tools/generate_kotlin_core_golden_fixtures.py`'s `generate_time_of_day_golden`
 * runs `dateutil.parser.parse(time_str.strip(), default=datetime(1900, 1, 1))`
 * over a broad sweep of time-of-day strings (every hour 0-24, a sample of
 * minutes/seconds, every AM/PM marker spelling/whitespace variant, ASCII and
 * Unicode-digit forms, and leading/trailing whitespace) and records either
 * the resulting (hour, minute, second) or the raised exception's class name,
 * as `android/core/src/test/resources/golden/time_of_day_golden.json`. This
 * test reads that JSON and asserts the Kotlin [parseTimeOfDay] produces the
 * same result -- or throws -- for every single recorded input.
 */
class TimeOfDayGoldenParityTest {

    private fun readGoldenResource(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("golden/$name")
            ?: error("golden resource not found on test classpath: golden/$name")
        return stream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    @Test
    fun parseTimeOfDayMatchesDateutilForEveryGoldenEntry() {
        val root = parseJson(readGoldenResource("time_of_day_golden.json")).asObj()
        val entries = root["entries"].asArr().items.map { it.asObj() }
        assertTrue("golden sweep should be non-trivial", entries.size > 100)

        var checked = 0
        val mismatches = mutableListOf<String>()

        for (entry in entries) {
            val input = entry["input"].asString()
            val ok = entry["ok"].asBoolean()

            if (ok) {
                val expectedHour = entry["hour"].asInt()
                val expectedMinute = entry["minute"].asInt()
                val expectedSecond = entry["second"].asInt()
                try {
                    val (h, m, s) = parseTimeOfDay(input)
                    if (h != expectedHour || m != expectedMinute || s != expectedSecond) {
                        mismatches.add(
                            "input=${reprForReport(input)}: dateutil=($expectedHour,$expectedMinute,$expectedSecond)" +
                                " kotlin=($h,$m,$s)",
                        )
                    }
                } catch (e: Exception) {
                    mismatches.add(
                        "input=${reprForReport(input)}: dateutil ok=($expectedHour,$expectedMinute,$expectedSecond)" +
                            " kotlin threw ${e::class.simpleName}: ${e.message}",
                    )
                }
            } else {
                val expectedExceptionClass = entry["exceptionClass"].asString()
                try {
                    val result = parseTimeOfDay(input)
                    mismatches.add(
                        "input=${reprForReport(input)}: dateutil raised $expectedExceptionClass" +
                            " but kotlin returned $result",
                    )
                } catch (e: Exception) {
                    // Kotlin throwing anything at all for an input dateutil rejects is the
                    // contract -- see parseTimeOfDay's KDoc. We don't require the same
                    // exception *class* (Kotlin uses TimestampParseException throughout),
                    // only that it throws.
                }
            }
            checked++
        }

        if (mismatches.isNotEmpty()) {
            fail(
                "parseTimeOfDay diverged from dateutil on ${mismatches.size}/$checked golden entries:\n" +
                    mismatches.take(50).joinToString("\n") +
                    if (mismatches.size > 50) "\n... (${mismatches.size - 50} more)" else "",
            )
        }

        assertEquals(entries.size, checked)
    }

    /** Escapes non-printable/whitespace characters so a mismatch message stays one line. */
    private fun reprForReport(s: String): String =
        s.map { c ->
            when {
                c == '\t' -> "\\t"
                c == '\n' -> "\\n"
                c == '\r' -> "\\r"
                c.code in 0x20..0x7E -> c.toString()
                else -> "\\u%04x".format(c.code)
            }
        }.joinToString("")
}
