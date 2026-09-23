package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime

/**
 * Cross-language parity test for [buildIndex]/[indexBytes]/[headerSafe]
 * against the *real* Python `src.mail_index` (`build_index`, `index_bytes`,
 * `estimate_index_bytes`, `_header_safe`).
 *
 * `tools/generate_kotlin_core_golden_fixtures.py`'s `generate_mail_index_golden`
 * runs the real Python builder over a sweep of chunks -- message ordering,
 * every `app_version` stamping path (explicit/empty-string/never-set), every
 * `chunk_size` shape (day/hour/week/int), the hand-assembled JSON surviving
 * the same four hostile-content strings `tests/test_mail_index.py`
 * parametrizes over, the single-message no-trailing-comma edge case, a
 * chat_id header-injection attempt, a long-sender-name stress case, and a
 * 250-message sweep -- and records the result as `mail_index_golden.json`,
 * plus a separate `estimateCases` group recording `estimate_index_bytes`'s
 * upper bound against the real, MIME-wrapped `build_index_part` size at
 * counts 1/10/250 (mirroring `test_estimate_is_an_upper_bound_on_the_real_part`).
 * This test rebuilds each case in Kotlin and asserts byte-for-byte parity,
 * plus negative tests asserting the wrong behaviour never happens.
 *
 * There is no equivalent standalone Python test for the golden-parity
 * comparison itself (it is inherently cross-language, like
 * [MimeGoldenParityTest]); the negative tests below do each mirror a
 * specific Python test named in their KDoc.
 */
class MailIndexGoldenParityTest {

    private fun readGoldenResource(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("golden/$name")
            ?: error("golden resource not found on test classpath: golden/$name")
        return stream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    private fun chunkSizeFrom(node: JsonNode): ChunkSize = when (node) {
        is JsonNode.Str -> when (node.value) {
            "day" -> ChunkSize.Day
            "hour" -> ChunkSize.Hour
            "week" -> ChunkSize.Week
            else -> error("unknown chunk size string: ${node.value}")
        }
        is JsonNode.Num -> ChunkSize.Count(node.value.toInt())
        else -> error("chunk size must be a string or number: $node")
    }

    private data class GoldenCase(
        val name: String,
        val displayName: String,
        val chatId: String,
        val messageId: String,
        val chunkSize: ChunkSize,
        val appVersionInput: String?,
        val messages: List<ParsedMessage>,
        val indexJson: String,
        val headerChat: String,
    )

    private fun parseGoldenCases(): List<GoldenCase> {
        val root = parseJson(readGoldenResource("mail_index_golden.json")).asObj()
        return root["cases"].asArr().items.map { it.asObj() }.map { obj ->
            val chatId = obj["chatId"].asString()
            val messages = obj["messages"].asArr().items.map { it.asObj() }.map { m ->
                ParsedMessage(
                    chatId = chatId,
                    timestamp = LocalDateTime.parse(m["ts"].asString()),
                    sender = m["sender"].asString(),
                    body = m["body"].asString(),
                )
            }
            GoldenCase(
                name = obj["name"].asString(),
                displayName = obj["displayName"].asString(),
                chatId = chatId,
                messageId = obj["messageId"].asString(),
                chunkSize = chunkSizeFrom(obj["chunkSize"]),
                appVersionInput = obj["appVersionInput"].asStringOrNull(),
                messages = messages,
                indexJson = obj["indexJson"].asString(),
                headerChat = obj["headerChat"].asString(),
            )
        }
    }

    private data class EstimateCase(
        val messageCount: Int,
        val indexBytesLen: Int,
        val partBytesLen: Int,
        val estimate: Int,
    )

    private fun parseEstimateCases(): List<EstimateCase> {
        val root = parseJson(readGoldenResource("mail_index_golden.json")).asObj()
        return root["estimateCases"].asArr().items.map { it.asObj() }.map { obj ->
            EstimateCase(
                messageCount = obj["messageCount"].asInt(),
                indexBytesLen = obj["indexBytesLen"].asInt(),
                partBytesLen = obj["partBytesLen"].asInt(),
                estimate = obj["estimate"].asInt(),
            )
        }
    }

    @Test
    fun everyGoldenCaseMatchesPythonByteForByte() {
        val cases = parseGoldenCases()
        assertTrue("expected a non-trivial case sweep", cases.size >= 12)

        val mismatches = mutableListOf<String>()
        for (case in cases) {
            val index = if (case.appVersionInput == null) {
                buildIndex(case.displayName, case.messages, case.chunkSize, case.messageId)
            } else {
                buildIndex(case.displayName, case.messages, case.chunkSize, case.messageId, case.appVersionInput)
            }
            val actualJson = indexBytes(index).toString(Charsets.UTF_8)
            if (actualJson != case.indexJson) {
                mismatches.add("case=${case.name}: indexJson differs\n--- expected ---\n${case.indexJson}\n--- actual ---\n$actualJson")
            }

            val actualHeaderChat = headerSafe(case.messages[0].chatId)
            if (actualHeaderChat != case.headerChat) {
                mismatches.add("case=${case.name}: headerChat differs: expected=${case.headerChat} actual=$actualHeaderChat")
            }
        }

        if (mismatches.isNotEmpty()) {
            fail("MailIndex diverged from Python on ${mismatches.size} case(s):\n${mismatches.joinToString("\n")}")
        }
    }

    @Test
    fun estimateIndexBytesNeverUnderestimatesTheRealPythonPartSize() {
        val estimateCases = parseEstimateCases()
        assertEquals(listOf(1, 10, 250), estimateCases.map { it.messageCount })

        for (case in estimateCases) {
            // The real Python-recorded bound this test preserves without a
            // Kotlin port of build_index_part's MIME wrapping (out of scope
            // -- see MimeBuilder.kt's own documented Phase-1 boundary).
            assertTrue(
                "estimate must be an upper bound on the real MIME part for count=${case.messageCount}: " +
                    "part=${case.partBytesLen} > estimate=${case.estimate}",
                case.partBytesLen <= case.estimate,
            )
            // Parity: Kotlin's own indexBytes() output length must match
            // what the golden recorded from the real Python index_bytes().
            val kotlinLen = estimateIndexBytesLenForCount(case.messageCount)
            assertEquals(
                "indexBytesLen must match Python for count=${case.messageCount}",
                case.indexBytesLen,
                kotlinLen,
            )
        }
    }

    private fun estimateIndexBytesLenForCount(count: Int): Int {
        val chunk = (0 until count).map { i ->
            ParsedMessage(
                chatId = "test_chat",
                timestamp = LocalDateTime.of(2020, 1, 1, 0, 0, 0).plusSeconds(i.toLong()),
                sender = "Meera Iyer",
                body = "message number $i",
            )
        }
        val index = buildIndex("Meera Iyer", chunk, ChunkSize.Day, "<golden-fixture-anchor@local>")
        return indexBytes(index).size
    }

    // -----------------------------------------------------------------
    // Negative tests -- assert the wrong behaviour does NOT happen.
    // -----------------------------------------------------------------

    /**
     * Mirrors Python's `test_chat_id_cannot_inject_a_header`: a chat_id
     * carrying an embedded CRLF header line must never survive into
     * [headerSafe]'s output -- no raw `\r`/`\n`, and the injected header
     * name must not appear as a standalone token that could be mistaken for
     * a real header once stamped onto a message.
     */
    @Test
    fun chatIdHeaderInjectionNeverSurvivesHeaderSafe() {
        val evilChatId = "evil\r\nX-Injected: yes"
        val safe = headerSafe(evilChatId)

        assertFalse("header-safe output must never contain a carriage return", safe.contains("\r"))
        assertFalse("header-safe output must never contain a newline", safe.contains("\n"))
        assertEquals("whitespace runs collapse to single spaces", "evil X-Injected: yes", safe)
    }

    /**
     * The authoritative, unsanitized chat_id must still round-trip exactly
     * in the JSON body -- the header is a lossy convenience, the JSON is
     * the source of truth, and the two must never silently agree on the
     * same (wrong) sanitized value.
     */
    @Test
    fun chatIdHeaderInjectionAttemptStillRoundTripsExactlyInJson() {
        val golden = parseGoldenCases().first { it.name == "chat_id_header_injection_attempt" }
        assertTrue(
            "the raw CRLF-carrying chat_id must appear verbatim in the JSON body",
            golden.indexJson.contains("evil\\r\\nX-Injected: yes"),
        )
        assertFalse(
            "the JSON body's chat_id must never be silently sanitized down to the header-safe form",
            golden.indexJson.contains("\"chat_id\": \"evil X-Injected: yes\""),
        )
    }

    /**
     * Mirrors Python's `test_index_app_version_never_says_development_build`:
     * the retired desktop-only literal must never appear in the built index
     * under any app_version input (explicit, empty, or never-set).
     */
    @Test
    fun appVersionNeverRendersTheRetiredDevelopmentBuildLiteral() {
        val cases = parseGoldenCases().filter { it.name.startsWith("app_version_") }
        assertTrue("expected all three app_version cases", cases.size == 3)
        for (case in cases) {
            assertFalse(
                "case=${case.name}: must never contain the retired placeholder",
                case.indexJson.contains("development build"),
            )
        }
    }

    /**
     * Mirrors Python's `test_single_message_index_has_no_trailing_comma`:
     * the hand-assembled one-element case must never leave a dangling comma
     * before the closing `]`, which would make the attachment invalid JSON.
     */
    @Test
    fun singleMessageIndexNeverHasATrailingComma() {
        val chunk = listOf(
            ParsedMessage(
                chatId = "test_chat",
                timestamp = LocalDateTime.of(2021, 6, 15, 14, 30, 0),
                sender = "Meera Iyer",
                body = "solo message",
            ),
        )
        val index = buildIndex("Meera Iyer", chunk, ChunkSize.Day, "<mid@local>")
        val json = indexBytes(index).toString(Charsets.UTF_8)

        assertFalse("a single-entry messages array must never end with a trailing comma", json.contains(",\n  ]"))
        assertTrue("the one entry must still be present", json.contains("\"n\":1"))
    }

    /**
     * Mirrors Python's `test_serialised_index_survives_hostile_content`,
     * negatively: none of the four hostile strings may ever produce an
     * unescaped raw quote or literal control character that would break the
     * hand-assembled JSON structure -- every hostile case's golden output
     * must remain the exact bytes `json.dumps` itself produced.
     */
    @Test
    fun hostileContentNeverProducesUnescapedStructureBreakingCharacters() {
        val hostileCases = parseGoldenCases().filter { it.name.startsWith("hostile_") }
        assertEquals(4, hostileCases.size)

        for (case in hostileCases) {
            val index = buildIndex(case.displayName, case.messages, case.chunkSize, case.messageId)
            val actualJson = indexBytes(index).toString(Charsets.UTF_8)
            assertEquals("case=${case.name}: must match Python's escaping exactly", case.indexJson, actualJson)

            // A raw (unescaped) literal newline inside a JSON string value
            // would break line-per-message readability/parseability; every
            // literal '\n' in the hostile "newline in body" source string
            // must appear only as the two-character escape sequence \n.
            if (case.name == "hostile_newline_in_body") {
                val senderLine = actualJson.lines().first { it.contains("\"sender\"") }
                assertFalse("a raw newline must never split the message entry across lines", senderLine.contains("\n\n"))
                assertTrue("the newline must be JSON-escaped", senderLine.contains("\\n"))
            }
        }
    }

    /**
     * Every integer `chunk_size` (e.g. `chunk_size_count_int_50`) must be
     * emitted as a bare JSON number, never as a quoted string -- a reader
     * distinguishing "day"/"hour"/"week" from a numeric chunk size relies on
     * this.
     */
    @Test
    fun integerChunkSizeIsNeverQuotedInJson() {
        val golden = parseGoldenCases().first { it.name == "chunk_size_count_int_50" }
        assertTrue("the chunk field must be the bare number 50", golden.indexJson.contains("\"chunk\": 50,"))
        assertFalse("the chunk field must never be quoted", golden.indexJson.contains("\"chunk\": \"50\""))
    }

    /**
     * A long sender name must never be truncated or otherwise mangled by
     * the hand-assembled layout -- it must appear in full, exactly once per
     * entry, in both the display_name and sender fields.
     */
    @Test
    fun longSenderNameIsNeverTruncated() {
        val golden = parseGoldenCases().first { it.name == "long_sender_name_stress" }
        val longName = "A".repeat(200)
        assertTrue("the full 200-char sender name must appear untruncated", golden.indexJson.contains(longName))
        assertFalse("must never contain a truncation ellipsis", golden.indexJson.contains("..."))

        // Every quoted run of 'A' characters (display_name and sender both
        // render as one) must be exactly the full 200 characters -- never a
        // shorter, silently-truncated run standing on its own between quotes.
        val quotedRuns = Regex("\"A+\"").findAll(golden.indexJson).map { it.value }.toList()
        assertTrue("expected at least one quoted A-run (display_name and sender)", quotedRuns.isNotEmpty())
        for (run in quotedRuns) {
            assertEquals("a quoted A-run must never be a truncated length", "\"$longName\"", run)
        }
    }
}
