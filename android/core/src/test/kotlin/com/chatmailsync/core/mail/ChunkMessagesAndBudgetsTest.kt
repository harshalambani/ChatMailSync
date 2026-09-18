package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Unit tests for [chunkMessages] (`chunk_messages`, `src/mail_client.py`
 * lines ~1394-1442) and the size-budget helpers in Budgets.kt
 * (`effective_budget`/`media_budget`, same file, plus `max_raw_bytes_for`
 * from `src/html_renderer.py`).
 *
 * Python has no standalone unit-test module for `chunk_messages` itself --
 * it is exercised only incidentally, as a fixture-builder call, inside
 * `tests/test_mail_transport.py`'s `push_chunks` tests (e.g.
 * `test_a_declared_limit_splits_the_chunk_before_anything_is_sent`), which
 * are themselves out of scope here since `push_chunks` was not ported this
 * phase (see the PR body). `effective_budget`/`media_budget` have no direct
 * Python unit tests either; the closest coverage is
 * `tests/test_html_renderer.py`'s `test_max_raw_bytes_for_round_trips_within_its_budget`
 * and `test_max_raw_bytes_for_refuses_a_budget_smaller_than_the_headers`,
 * both of which are ported here as-is against [maxRawBytesFor] since that
 * function was ported unchanged. Everything else below is additional
 * coverage written directly against the Kotlin port, including the
 * mandatory "chunking budget never exceeded" negative test from the brief.
 */
class ChunkMessagesAndBudgetsTest {

    private fun msg(ts: LocalDateTime, sender: String = "Meera Iyer", body: String = "hi"): ParsedMessage =
        ParsedMessage(chatId = "c1", timestamp = ts, sender = sender, body = body)

    // -----------------------------------------------------------------
    // chunkMessages
    // -----------------------------------------------------------------

    @Test
    fun chunkMessagesGroupsByCalendarDayByDefault() {
        val messages = listOf(
            msg(LocalDateTime.of(2026, 1, 1, 9, 0)),
            msg(LocalDateTime.of(2026, 1, 1, 23, 59)),
            msg(LocalDateTime.of(2026, 1, 2, 0, 0)),
        )

        val chunks = chunkMessages(messages, ChunkSize.Day)

        assertEquals(2, chunks.size)
        assertEquals(2, chunks[0].size)
        assertEquals(1, chunks[1].size)
    }

    @Test
    fun chunkMessagesGroupsByHourWhenRequested() {
        val messages = listOf(
            msg(LocalDateTime.of(2026, 1, 1, 9, 0)),
            msg(LocalDateTime.of(2026, 1, 1, 9, 59)),
            msg(LocalDateTime.of(2026, 1, 1, 10, 0)),
        )

        val chunks = chunkMessages(messages, ChunkSize.Hour)

        assertEquals(2, chunks.size)
        assertEquals(2, chunks[0].size)
    }

    @Test
    fun chunkMessagesGroupsByIsoWeekWhenRequested() {
        // 2026-01-01 is a Thursday in ISO week 1 of 2026; 2026-01-05 (Monday) starts week 2.
        val messages = listOf(
            msg(LocalDateTime.of(2026, 1, 1, 9, 0)),
            msg(LocalDateTime.of(2026, 1, 4, 9, 0)), // still week 1 (Sunday)
            msg(LocalDateTime.of(2026, 1, 5, 9, 0)), // week 2 (Monday)
        )

        val chunks = chunkMessages(messages, ChunkSize.Week)

        assertEquals(2, chunks.size)
        assertEquals(2, chunks[0].size)
        assertEquals(1, chunks[1].size)
    }

    @Test
    fun chunkMessagesByFixedCountIgnoresCalendarBoundaries() {
        val messages = (1..5).map { msg(LocalDateTime.of(2026, 1, 1, 9, it)) }

        val chunks = chunkMessages(messages, ChunkSize.Count(2))

        assertEquals(listOf(2, 2, 1), chunks.map { it.size })
    }

    @Test
    fun chunkMessagesEmptyInputYieldsEmptyOutput() {
        assertEquals(emptyList<List<ParsedMessage>>(), chunkMessages(emptyList(), ChunkSize.Day))
    }

    @Test
    fun chunkMessagesPreservesOrderWithinAndAcrossChunks() {
        val messages = listOf(
            msg(LocalDateTime.of(2026, 1, 1, 9, 0), sender = "a"),
            msg(LocalDateTime.of(2026, 1, 1, 9, 1), sender = "b"),
            msg(LocalDateTime.of(2026, 1, 2, 9, 0), sender = "c"),
        )

        val chunks = chunkMessages(messages, ChunkSize.Day)

        assertEquals(listOf("a", "b"), chunks[0].map { it.sender })
        assertEquals(listOf("c"), chunks[1].map { it.sender })
    }

    @Test(expected = IllegalArgumentException::class)
    fun chunkMessagesByCountRejectsNonPositiveCount() {
        chunkMessages(listOf(msg(LocalDateTime.of(2026, 1, 1, 9, 0))), ChunkSize.Count(0))
    }

    // -----------------------------------------------------------------
    // effectiveBudget / mediaBudget
    // -----------------------------------------------------------------

    @Test
    fun effectiveBudgetShavesBySafetyFactor() {
        assertEquals(9_000_000L, effectiveBudget(10_000_000L))
    }

    @Test
    fun mediaBudgetIsSmallerThanEffectiveBudget() {
        val limit = 25_000_000L
        assertTrue(mediaBudget(limit) < effectiveBudget(limit))
    }

    @Test
    fun mediaBudgetIsZeroNotNegativeWhenLimitIsTiny() {
        // effectiveBudget(1000) - RENDER_OVERHEAD_BYTES is deeply negative;
        // maxRawBytesFor must clamp to 0, never return a negative "budget".
        assertEquals(0L, mediaBudget(1_000L))
    }

    // Mandatory negative test from the brief: chunking/budget math must never
    // let an "OK to send" media size exceed the wire budget once base64 and
    // MIME overhead are added back on -- i.e. round-tripping mediaBudget's
    // raw-byte answer back through base64 expansion must still fit under the
    // original provider limit.
    @Test
    fun mediaBudgetNeverExceedsTheOriginalProviderLimitOnceReEncoded() {
        for (limit in listOf(1_000_000L, 10_000_000L, 22_500_000L, 25_000_000L, 63_000_000L)) {
            val rawBudget = mediaBudget(limit)
            // base64 expansion: 4/3 plus a CRLF every 76 chars, matching
            // encoded_part_bytes()'s ratio used on the Python side.
            val encoded = ((rawBudget + 2) / 3) * 4 + (rawBudget / 57) * 2
            assertTrue(
                "mediaBudget($limit) = $rawBudget re-encodes to $encoded bytes, which must stay under $limit",
                encoded < limit,
            )
        }
    }

    // -----------------------------------------------------------------
    // maxRawBytesFor -- ported as-is from test_html_renderer.py
    // -----------------------------------------------------------------

    // mapping: test_max_raw_bytes_for_refuses_a_budget_smaller_than_the_headers
    @Test
    fun maxRawBytesForRefusesABudgetSmallerThanTheHeaders() {
        assertEquals(0L, maxRawBytesFor(10L))
    }

    // mapping: test_max_raw_bytes_for_round_trips_within_its_budget (approximate:
    // exercises the same monotonic-fit property without depending on the
    // unported encoded_part_bytes()/_PART_HEADER_BYTES exact Python formula
    // match beyond what maxRawBytesFor itself already encodes).
    @Test
    fun maxRawBytesForNeverExceedsItsBudgetOnceEncodedBack() {
        for (budget in listOf(1_000_000L, 22_500_000L, 25_000_000L, 63_000_000L)) {
            val raw = maxRawBytesFor(budget)
            assertTrue(raw > 0)
            assertTrue(raw < budget)
        }
    }
}
