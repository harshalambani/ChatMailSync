package com.chatmailsync.core.mail

import kotlin.math.max

/**
 * Kotlin port of the size-budget helpers `effective_budget` / `media_budget`
 * (`src/mail_client.py`) and the small piece of `src/html_renderer.py` they
 * depend on (`max_raw_bytes_for`, and the three constants it uses). The rest
 * of html_renderer.py (HTML chunk rendering) is out of scope for Phase 1 --
 * see the PR body.
 */

/** Mirrors `config.MESSAGE_SIZE_SAFETY_FACTOR`. */
const val MESSAGE_SIZE_SAFETY_FACTOR = 0.90

// From src/html_renderer.py: base64 wraps at 76 chars per RFC 2045, plus a
// 2-byte line ending, and _PART_HEADER_BYTES is the MIME part's own header
// overhead budget.
private const val B64_LINE_LENGTH = 76
private const val B64_LINE_ENDING = 2
private const val PART_HEADER_BYTES = 220

// From src/mail_client.py: headroom reserved for the plain-text/HTML body
// and the index attachment sitting alongside the media in the same message.
private const val RENDER_OVERHEAD_BYTES = 512_000L

/** Mirrors `effective_budget`: the provider's raw limit, shaved by the safety factor. */
fun effectiveBudget(limitBytes: Long): Long = (limitBytes * MESSAGE_SIZE_SAFETY_FACTOR).toLong()

/**
 * Mirrors `html_renderer.max_raw_bytes_for`: the largest raw (pre-base64)
 * byte count that still fits in [wireBudget] once MIME part headers and
 * base64 line-wrapping overhead are accounted for.
 */
fun maxRawBytesFor(wireBudget: Long): Long {
    val usable = wireBudget - PART_HEADER_BYTES
    if (usable <= 0) return 0
    val b64Chars = (usable / (1.0 + B64_LINE_ENDING.toDouble() / B64_LINE_LENGTH)).toLong()
    return max(0L, (b64Chars / 4) * 3)
}

/** Mirrors `media_budget`: how many raw media bytes fit once the body/index overhead is subtracted. */
fun mediaBudget(limitBytes: Long): Long = maxRawBytesFor(effectiveBudget(limitBytes) - RENDER_OVERHEAD_BYTES)
