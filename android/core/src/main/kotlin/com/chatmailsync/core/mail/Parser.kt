package com.chatmailsync.core.mail

import java.io.File
import java.time.DateTimeException
import java.time.LocalDateTime
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Kotlin port of `src/parser.py` (Phase 2 "parser" of the Kotlin core port —
 * see `2026-09-17-kotlin-core-fdroid-plan-and-windows-audit.md`, sections
 * D/E, which calls this "the fragile one" and lays out the specific
 * regex-flag, date-parsing and file-reading traps this file has to avoid). A
 * faithful behavioural twin of the Python module's public functions, except
 * for two deliberate, explicitly-noted divergences below (`\d` scope, and
 * replacing `dateutil.parser.parse` with a hand-rolled time-of-day parser
 * that reproduces its answers for exactly the inputs `TIMESTAMP_PATTERNS`
 * can ever hand it).
 *
 * NOT wired into `:app` yet — `:app`/`SyncWorker.kt` still reach
 * `src/parser.py` through Chaquopy (`sync_manager.py` calls it internally).
 *
 * ## Regex flags and the Python/Java divergences this file pins deliberately
 *
 * Every pattern below is compiled with [Pattern.UNICODE_CHARACTER_CLASS], so
 * `\s` matches the same Unicode `White_Space`-property characters Python's
 * `\s` matches in default (Unicode) mode — critically including U+202F
 * NARROW NO-BREAK SPACE, which iOS inserts before AM/PM in exported
 * timestamps (`[3/4/25, 2:05:33␣PM]` where `␣` is U+202F, not U+0020). Java's
 * `\s` *without* that flag is ASCII-only ([ \t\n\x0B\f\r]) and would silently
 * fail to lock the AM/PM formats against a real iOS export. See
 * [aNarrowNoBreakSpaceBeforeAmPmMatchesLikePython] in the test twin.
 *
 * `\d`, by contrast, is deliberately kept **ASCII-only** here
 * ([toAsciiDigitPattern] rewrites every `\d` to `[0-9]` before compiling),
 * which *diverges* from Python: Python's `\d` in Unicode mode also matches
 * non-ASCII decimal-digit characters (e.g. Devanagari ٠-٩ or full-width
 * ０-９), so a WhatsApp export using non-Western digits in its timestamps
 * would still lock a format in Python and would not in Kotlin. This is the
 * plan document's explicit recommendation (section D: "decide explicitly
 * (recommend ASCII digits only and assert the difference in a negative
 * test)") — real WhatsApp exports use Western Arabic numerals for
 * timestamps regardless of device locale, so the divergence is believed to
 * be unreachable in practice, and pinning it loudly beats reproducing a
 * behaviour nobody asked for. See [aFullWidthDigitDoesNotMatchUnlikePython]
 * in the test twin.
 */

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

/** Twin of `parser.py:extract_chat_info`'s `(chat_id, display_name)` tuple. */
data class ChatInfo(val chatId: String, val displayName: String)

/** Twin of `parser.py:_detect_format`'s `(format_key, line_re)` tuple. */
data class DetectedFormat(val formatKey: String, val lineRegex: Pattern)

/**
 * Thrown for the same conditions `parser.py:_read_chat_text` raises
 * `ValueError` for (zip-bomb guard tripped, or no `.txt` entry in the
 * archive). Twin boundary: callers that want Python's "log and skip" outcome
 * for an unparseable *timestamp* should not catch this — that is a distinct,
 * narrower failure handled inside [parseFile] itself (see
 * [TimestampParseException]).
 */
class ChatTextReadException(message: String) : IllegalArgumentException(message)

/**
 * Thrown internally when a `(date_str, time_str)` pair cannot become a valid
 * `LocalDateTime`. Twin of the `ValueError`/`OverflowError` `_parse_timestamp`
 * raises (invalid day/month, invalid hour/minute/second, or an AM/PM marker
 * paired with an hour outside 0..12 — see [parseTimeOfDay]'s KDoc for the
 * `dateutil`-pinned cases this reproduces). [parseFile] catches this the same
 * way `_build_message` catches `(ValueError, OverflowError)`: log and skip
 * that one message, never abort the file.
 */
class TimestampParseException(message: String) : RuntimeException(message)

/**
 * Sink for the two things `parser.py` logs at WARNING (a foreign-format line
 * absorbed into a continuation, and non-empty lines with nothing to attach
 * to) plus one DEBUG summary line. A plain interface rather than a logging
 * framework dependency, matching `:core`'s "stays dependency-free" stance
 * (see `Json.kt`'s KDoc) — `:app` can supply a real sink later; tests supply
 * an in-memory fake (see `RecordingParserLog` in the test twin).
 */
fun interface ParserLog {
    fun warn(message: String)
}

/** [ParserLog] that discards everything — the default for callers that do
 * not care about parse anomalies (mirrors letting Python's `log.warning`
 * go wherever the ambient logging config sends it, unobserved). */
object NoOpParserLog : ParserLog {
    override fun warn(message: String) {}
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Yield [ParsedMessage]s from a WhatsApp `.txt` export file. Twin of
 * `parser.py:parse_file`.
 *
 * Auto-detects the timestamp format from the first lines of the file, then
 * locks that pattern for all subsequent lines (fast path), exactly like the
 * Python generator.
 *
 * @param filepath Path to the `.txt` export file (or a `.zip` containing one).
 * @param chatId Normalized chat identifier (from [extractChatInfo]).
 * @param dateOrder `"DMY"` or `"MDY"` to override auto-detection, or `null`
 *   to auto-resolve (twin of Python's `date_order: Optional[str] = None`).
 * @param log Receives the same two WARNING-level anomaly messages Python
 *   logs; see [ParserLog].
 */
fun parseFile(
    filepath: File,
    chatId: String,
    dateOrder: String? = null,
    log: ParserLog = NoOpParserLog,
): Sequence<ParsedMessage> = sequence {
    val text = readChatText(filepath)
    val lines = pythonSplitlines(text)

    // --- Format detection ---
    val detectionLines = lines.filter { pythonStrip(it).isNotEmpty() }.take(FORMAT_DETECTION_LINES)
    val detected = detectFormat(detectionLines)
    if (detected == null) {
        // Twin of `log.warning("No timestamp pattern matched in %s -- skipping file", ...)`.
        log.warn("No timestamp pattern matched in ${filepath.name} -- skipping file")
        return@sequence
    }
    val (formatKey, lineRe) = detected

    // --- Date order resolution ---
    val effectiveOrder = dateOrder ?: resolveDateOrder(lines, lineRe, formatKey)

    // --- Line-by-line parsing ---
    var current: CurrentMessage? = null
    var continuationCount = 0
    var orphanCount = 0
    val foreignHits = linkedMapOf<String, Int>()
    val otherRes = TIMESTAMP_PATTERNS.filter { it.key != formatKey }
        .map { it.key to buildLineRegex(it.pattern) }

    for (rawLine in lines) {
        val line = cleanText(rawLine)
        val m = lineRe.matcher(line)

        if (m.lookingAt() && m.matches()) {
            // New timestamp line -- emit whatever was accumulated, then start fresh.
            // (Twin of Python's `line_re.match(line)`: matched at the start AND the
            // pattern itself is anchored with a trailing `$`, so `.matches()` after a
            // successful `.lookingAt()` reproduces Python `re.match`'s semantics for
            // this fully-anchored pattern -- see buildLineRegex.)
            current?.let { toEmit ->
                val msg = buildMessage(toEmit, chatId, formatKey, effectiveOrder, log)
                if (msg != null) yield(msg)
            }
            current = null

            val dateStr = m.group(1)
            val timeStr = m.group(2)
            val content = cleanText(m.group(3))

            // Bare system messages have no "Sender: body" structure.
            if (!content.contains(": ")) continue

            val sepIndex = content.indexOf(": ")
            val sender = content.substring(0, sepIndex)
            val body = content.substring(sepIndex + 2)

            // Filter body-level system messages (e.g. an "image omitted" marker,
            // "This message was deleted"). Checked against the body only, not the
            // full content, to minimise false positives.
            if (isSystemBody(body)) continue

            current = CurrentMessage(
                dateStr = dateStr,
                timeStr = timeStr,
                sender = pythonStrip(sender),
                bodyLines = mutableListOf(body),
            )
        } else {
            // Continuation line -- append to the previous message body,
            // preserving original whitespace/blank lines.
            if (line.isNotEmpty()) {
                continuationCount++
                // A line that is a valid message line in a *different* timestamp
                // format is the strongest available evidence that this is not
                // prose: the export's shape has moved and the locked pattern no
                // longer describes it.
                for ((otherKey, otherRe) in otherRes) {
                    val om = otherRe.matcher(line)
                    if (om.lookingAt() && om.matches()) {
                        foreignHits[otherKey] = (foreignHits[otherKey] ?: 0) + 1
                        break
                    }
                }
            }

            val cur = current
            if (cur != null) {
                cur.bodyLines.add(rawLine)
            } else if (line.isNotEmpty()) {
                // Nothing to attach to, so the line is discarded outright.
                orphanCount++
            }
        }
    }

    // Emit the final accumulated message.
    current?.let { toEmit ->
        val msg = buildMessage(toEmit, chatId, formatKey, effectiveOrder, log)
        if (msg != null) yield(msg)
    }

    logParseAnomalies(filepath.name, formatKey, continuationCount, orphanCount, foreignHits, log)
}

private data class CurrentMessage(
    val dateStr: String,
    val timeStr: String,
    val sender: String,
    val bodyLines: MutableList<String>,
)

/** Twin of `parser.py:_log_parse_anomalies`. Counts and format names only —
 * chat content is deliberately never logged, matching the Python docstring's
 * reasoning verbatim. */
private fun logParseAnomalies(
    filename: String,
    formatKey: String,
    continuationCount: Int,
    orphanCount: Int,
    foreignHits: Map<String, Int>,
    log: ParserLog,
) {
    if (foreignHits.isNotEmpty()) {
        val total = foreignHits.values.sum()
        val detail = foreignHits.toSortedMap().entries.joinToString(", ") { (k, v) -> "$k=$v" }
        log.warn(
            "$filename: $total line(s) parse as message lines in a timestamp format " +
                "other than the locked '$formatKey' ($detail), and were absorbed into " +
                "the preceding message body. The export format may have changed.",
        )
    }
    if (orphanCount > 0) {
        log.warn(
            "$filename: $orphanCount non-empty line(s) preceded the first message " +
                "line and were discarded.",
        )
    }
    // Python's unconditional `log.debug(...continuation line(s) absorbed...)` has no
    // twin here -- [ParserLog] only carries the WARNING-level sink real callers and
    // tests care about; `continuationCount` is computed identically either way.
}

/**
 * Parse a WhatsApp export filename into (chat_id, display_name). Twin of
 * `parser.py:extract_chat_info`. Handles Android (`WhatsApp Chat with
 * Name.txt`) and iOS (`Name.txt`).
 */
fun extractChatInfo(filename: String): ChatInfo {
    val stem = pathStem(filename)

    val prefix = "whatsapp chat with "
    var displayName = if (stem.lowercase(Locale.ROOT).startsWith(prefix)) {
        stem.substring(prefix.length)
    } else {
        stem
    }

    // A chat's identity is derived from this name, so a copy that Downloads
    // renamed -- "WhatsApp Chat with Priya Nair (1).txt", which is what you get
    // when the same export arrives a second time -- used to become a second
    // chat with its own thread, and every message mailed again on top of the
    // first copy.
    val stripped = pythonStrip(TRAILING_COUNTER_RE.matcher(displayName).replaceAll(""))
    if (stripped.isNotEmpty()) {
        displayName = stripped
    }

    // Normalize: ASCII alphanumeric only, spaces collapsed to underscores.
    var chatId = NON_ASCII_ALNUM_RE.matcher(displayName.lowercase(Locale.ROOT)).replaceAll("")
    chatId = WHITESPACE_RUN_RE.matcher(pythonStrip(chatId)).replaceAll("_").trim('_')
    if (chatId.isEmpty()) chatId = "unknown_chat"

    return ChatInfo(chatId, displayName)
}

private val TRAILING_COUNTER_RE: Pattern =
    Pattern.compile("\\s*\\([0-9]+\\)$", Pattern.UNICODE_CHARACTER_CLASS)
private val NON_ASCII_ALNUM_RE: Pattern =
    Pattern.compile("[^a-z0-9\\s]", Pattern.UNICODE_CHARACTER_CLASS)
private val WHITESPACE_RUN_RE: Pattern =
    Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS)

/**
 * Twin of `pathlib.Path(filename).stem`: the filename with its last
 * extension suffix removed (a suffix only counts when there is at least one
 * character before the final dot). Kotlin's `File.nameWithoutExtension`
 * matches this for every filename shape a WhatsApp export uses (a name, or
 * a name with a single `.txt`/`.zip` extension); it is not re-verified
 * against every pathlib edge case (a leading-dot-only filename like `.txt`),
 * since no export or test fixture ever produces one.
 */
private fun pathStem(filename: String): String = File(filename).nameWithoutExtension

// ---------------------------------------------------------------------------
// File reading (plain text or ZIP)
// ---------------------------------------------------------------------------

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

/**
 * Return the chat text from a file, handling both plain `.txt` and ZIP
 * archives. Twin of `parser.py:_read_chat_text`.
 *
 * WhatsApp on iPhone exports a ZIP containing a `_chat.txt` (or a file named
 * after the contact). The ZIP is detected by attempting to open it as one
 * (Java's [ZipFile] validates the central directory, the same class of check
 * Python's `zipfile.is_zipfile` performs), not by file extension.
 */
fun readChatText(filepath: File): String {
    val zip = tryOpenZip(filepath)
    if (zip != null) {
        zip.use { zf ->
            // Zip-bomb guard: check total uncompressed size before reading anything.
            val entries = zf.entries().toList()
            val totalUncompressed = entries.sumOf { if (it.size >= 0) it.size else 0L }
            if (totalUncompressed > MAX_ZIP_DECOMPRESSED_BYTES) {
                throw ChatTextReadException(
                    "ZIP archive '${filepath.name}' would decompress to " +
                        "${"%,d".format(totalUncompressed)} bytes, which exceeds the " +
                        "${"%,d".format(MAX_ZIP_DECOMPRESSED_BYTES)}-byte safety limit.",
                )
            }
            // Prefer '_chat.txt'; fall back to the first .txt entry found.
            val txtNames = entries.map { it.name }.filter { it.lowercase(Locale.ROOT).endsWith(".txt") }
            if (txtNames.isEmpty()) {
                throw ChatTextReadException("ZIP archive '${filepath.name}' contains no .txt file.")
            }
            val target = txtNames.firstOrNull { "_chat" in it.lowercase(Locale.ROOT) } ?: txtNames.first()
            val raw = zf.getInputStream(zf.getEntry(target)).use { it.readBytes() }
            return decodeZipText(raw)
        }
    }
    // Plain text: twin of `filepath.read_text(encoding="utf-8-sig", errors="replace")`.
    val raw = filepath.readBytes()
    return decodeUtf8SigReplace(raw)
}

private fun tryOpenZip(filepath: File): ZipFile? = try {
    ZipFile(filepath)
} catch (_: ZipException) {
    null
} catch (_: java.io.IOException) {
    null
}

/** Twin of the zip-branch decode fallback chain `("utf-8-sig", "utf-8", "latin-1")`. */
private fun decodeZipText(raw: ByteArray): String {
    val stripped = if (raw.size >= 3 && raw[0] == UTF8_BOM[0] && raw[1] == UTF8_BOM[1] && raw[2] == UTF8_BOM[2]) {
        raw.copyOfRange(3, raw.size)
    } else {
        raw
    }
    strictUtf8Decode(stripped)?.let { return it }
    strictUtf8Decode(raw)?.let { return it }
    return String(raw, Charsets.ISO_8859_1) // latin-1, never raises
}

private fun strictUtf8Decode(bytes: ByteArray): String? {
    val decoder: java.nio.charset.CharsetDecoder = Charsets.UTF_8.newDecoder()
    decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
    decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return try {
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }
}

/**
 * Twin of `encoding="utf-8-sig", errors="replace"`: strip a leading UTF-8 BOM
 * if present, then decode as UTF-8, replacing malformed/unmappable byte
 * sequences with U+FFFD rather than raising. Python's `errors="replace"`
 * granularity (how many U+FFFD one bad multi-byte sequence becomes) is not
 * guaranteed byte-identical to the JVM decoder's here -- both produce valid,
 * readable text with a replacement marker at the bad spot, which is what a
 * genuinely corrupt/mis-encoded export needs; no test in `tests/test_parser.py`
 * exercises the exact replacement count, so this is not pinned further.
 */
private fun decodeUtf8SigReplace(raw: ByteArray): String {
    val stripped = if (raw.size >= 3 && raw[0] == UTF8_BOM[0] && raw[1] == UTF8_BOM[1] && raw[2] == UTF8_BOM[2]) {
        raw.copyOfRange(3, raw.size)
    } else {
        raw
    }
    val decoder: java.nio.charset.CharsetDecoder = Charsets.UTF_8.newDecoder()
    decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
    decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
    return decoder.decode(java.nio.ByteBuffer.wrap(stripped)).toString()
}

// ---------------------------------------------------------------------------
// Format detection
// ---------------------------------------------------------------------------

/** Rewrites every `\d` in [raw] to the ASCII-only `[0-9]` -- see this file's
 * top-level KDoc for why `\d` is deliberately not Unicode-aware here. */
private fun toAsciiDigitPattern(raw: String): String = raw.replace("\\d", "[0-9]")

/**
 * Wrap a raw timestamp pattern into a full message-line regex. Twin of
 * `parser.py:_build_line_re`. The resulting pattern captures three groups:
 * (1) date_part (2) time_part (3) remainder (sender: body or system text).
 */
fun buildLineRegex(tsPattern: String): Pattern =
    Pattern.compile("^" + toAsciiDigitPattern(tsPattern) + " - (.+)$", Pattern.UNICODE_CHARACTER_CLASS)

/**
 * Return (format_key, line_regex) for the first pattern that matches any
 * line. Twin of `parser.py:_detect_format`. Patterns are tried in the
 * priority order defined in [TIMESTAMP_PATTERNS].
 */
fun detectFormat(lines: List<String>): DetectedFormat? {
    for (entry in TIMESTAMP_PATTERNS) {
        val lineRe = buildLineRegex(entry.pattern)
        for (line in lines) {
            val m = lineRe.matcher(cleanText(line))
            if (m.lookingAt() && m.matches()) {
                return DetectedFormat(entry.key, lineRe)
            }
        }
    }
    return null
}

// ---------------------------------------------------------------------------
// Date order resolution
// ---------------------------------------------------------------------------

/**
 * Determine whether date fields are DD/MM (DMY) or MM/DD (MDY). Twin of
 * `parser.py:_resolve_date_order`. Three-step approach (architecture doc
 * §2): (1) definitive — any first-field value > 12 → DMY, any second-field
 * value > 12 → MDY; (2) heuristic — first-field max > second-field max →
 * DMY (weak signal); (3) default — [DATE_ORDER].
 */
fun resolveDateOrder(lines: List<String>, lineRe: Pattern, formatKey: String): String {
    val sep = if (formatKey == "dash_24h") "-" else "/"
    val firstVals = mutableListOf<Int>()
    val secondVals = mutableListOf<Int>()

    for (line in lines) {
        if (firstVals.size >= DATE_ORDER_SCAN_MESSAGES) break
        val m = lineRe.matcher(cleanText(line))
        if (!(m.lookingAt() && m.matches())) continue
        val parts = m.group(1).split(sep)
        if (parts.size < 2) continue
        val a = parts[0].toIntOrNull() ?: continue
        val b = parts[1].toIntOrNull() ?: continue
        firstVals.add(a)
        secondVals.add(b)
    }

    if (firstVals.isEmpty()) return DATE_ORDER

    // Step 1: definitive
    if (firstVals.any { it > 12 }) return "DMY"
    if (secondVals.any { it > 12 }) return "MDY"

    // Step 2: heuristic -- days reach higher values than months
    if ((firstVals.max()) > (secondVals.max())) return "DMY"

    // Step 3: configured default
    return DATE_ORDER
}

// ---------------------------------------------------------------------------
// Timestamp construction
// ---------------------------------------------------------------------------

private val TIME_OF_DAY_RE: Pattern = Pattern.compile(
    "^([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?(?:\\s*([AaPp][Mm]))?$",
    Pattern.UNICODE_CHARACTER_CLASS,
)

/**
 * Hour/minute/second parsed from a `TIMESTAMP_PATTERNS` time capture group.
 * Twin of the time-of-day half of `dateutil_parser.parse(time_str.strip(),
 * default=datetime(1900, 1, 1))` in `parser.py:_parse_timestamp` -- but
 * hand-rolled instead of a general natural-language date/time library,
 * because the set of strings that can ever reach it is fully constrained by
 * [TIMESTAMP_PATTERNS]'s five capture groups: `H:MM`, `H:MM:SS`, optionally
 * followed by whitespace and a one-letter-then-M AM/PM marker.
 *
 * The AM/PM hour rule was pinned by running `dateutil.parser.parse` once
 * against the open-ended cases and recording its answers (see the plan
 * document, section D, and [parseTimeOfDayAmPmEdgeCasesMatchDateutil] in the
 * test twin for the full pinned table):
 *  - `"12:00 AM"` → 0 (dateutil), `"12:00 PM"` → 12, `"0:30 PM"` → 12 --
 *    i.e. `effectiveHour = hour % 12`, then `+ 12` if PM.
 *  - `"13:00 PM"` and `"14:05:33 PM"` both raise `dateutil.parser.ParserError`
 *    (a `ValueError` subclass) -- an AM/PM marker paired with an hour outside
 *    `0..12` is invalid, not silently reinterpreted.
 * Without an AM/PM marker, the hour is used literally (24-hour clock) and
 * must be `0..23` -- dateutil raises for `"24:00"`/`"25:00"`, reproduced here.
 * Minute and second must each be `0..59` in both cases -- dateutil raises for
 * `"9:41:60"`, reproduced here.
 */
internal fun parseTimeOfDay(raw: String): Triple<Int, Int, Int> {
    val trimmed = pythonStrip(raw)
    val m = TIME_OF_DAY_RE.matcher(trimmed)
    if (!m.matches()) {
        throw TimestampParseException("Unparseable time of day: '$raw'")
    }
    val rawHour = m.group(1).toInt()
    val minute = m.group(2).toInt()
    val second = m.group(3)?.toInt() ?: 0
    val ampm = m.group(4)?.lowercase(Locale.ROOT)

    if (minute !in 0..59) {
        throw TimestampParseException("minute must be in 0..59, not $minute: '$raw'")
    }
    if (second !in 0..59) {
        throw TimestampParseException("second must be in 0..59, not $second: '$raw'")
    }

    val hour = if (ampm != null) {
        if (rawHour !in 0..12) {
            throw TimestampParseException("hour must be in 0..12 with an AM/PM marker, not $rawHour: '$raw'")
        }
        val base = rawHour % 12
        if (ampm.startsWith("p")) base + 12 else base
    } else {
        if (rawHour !in 0..23) {
            throw TimestampParseException("hour must be in 0..23, not $rawHour: '$raw'")
        }
        rawHour
    }

    return Triple(hour, minute, second)
}

/**
 * Build a naive [LocalDateTime] from the two captured regex groups. Twin of
 * `parser.py:_parse_timestamp`. Handles 2-digit years (< 50 → 2000s, ≥ 50 →
 * 1900s); a 3-digit year value passes through unchanged, same as Python (see
 * this file's KDoc note on `_parse_timestamp`'s comment: "replicate, don't
 * fix").
 */
internal fun parseTimestamp(dateStr: String, timeStr: String, formatKey: String, dateOrder: String): LocalDateTime {
    val sep = if (formatKey == "dash_24h") "-" else "/"
    val parts = dateStr.split(sep)
    if (parts.size < 3) {
        throw TimestampParseException("Unexpected date string: '$dateStr'")
    }

    val a = parts[0].toIntOrNull() ?: throw TimestampParseException("Unexpected date string: '$dateStr'")
    val b = parts[1].toIntOrNull() ?: throw TimestampParseException("Unexpected date string: '$dateStr'")
    val yearRaw = parts[2].toIntOrNull() ?: throw TimestampParseException("Unexpected date string: '$dateStr'")
    val year = if (yearRaw >= 100) yearRaw else if (yearRaw < 50) 2000 + yearRaw else 1900 + yearRaw

    val (day, month) = if (dateOrder == "DMY") a to b else b to a

    val (hour, minute, second) = parseTimeOfDay(timeStr)

    return try {
        LocalDateTime.of(year, month, day, hour, minute, second)
    } catch (e: DateTimeException) {
        throw TimestampParseException(e.message ?: "invalid date/time: $dateStr $timeStr")
    }
}

// ---------------------------------------------------------------------------
// System message filtering
// ---------------------------------------------------------------------------

/**
 * Return `true` if the body of a "sender: body" line is a system
 * notification. Twin of `parser.py:_is_system_body`. Uses
 * [SYSTEM_BODY_PHRASES] (not the combined list) so that group-event phrases
 * like `" left"` -- which only ever appear on bare system lines with no
 * colon -- cannot false-positive on a real message like "I left my charger".
 */
fun isSystemBody(body: String): Boolean {
    val bodyLower = cleanText(body).lowercase(Locale.ROOT)
    return SYSTEM_BODY_PHRASES.any { it in bodyLower }
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/**
 * Strip the Unicode artifacts WhatsApp injects into exported text: U+200E
 * (LEFT-TO-RIGHT MARK), U+200F (RIGHT-TO-LEFT MARK), U+FEFF (BOM / ZERO
 * WIDTH NO-BREAK SPACE), U+200B (ZERO WIDTH SPACE). Twin of
 * `parser.py:_clean_text` (`str.maketrans("", "", "‎‏﻿​")`).
 * Written as `\u` escapes per this repo's "no literal control/invisible
 * characters in Kotlin sources" rule.
 */
fun cleanText(text: String): String =
    text.filterNot { it == '‎' || it == '‏' || it == '﻿' || it == '​' }

/**
 * Split [text] on every line-boundary character Python's `str.splitlines()`
 * recognises, with terminators removed (Python's default `keepends=False`).
 * Twin of `parser.py`'s use of `text.splitlines()` in `parse_file`. Not
 * Java's `\R`/`String.lines()`, which recognise a different (and, for `\v`
 * `\f` `\x1c`-`\x1e`, non-overlapping) boundary set -- see this file's
 * top-level KDoc and [aFormFeedIsALineBoundaryLikePython] in the test twin.
 *
 * Boundary set (from the CPython `str.splitlines` docs): `\n`, `\r`, `\r\n`
 * (counted once), `\v`/`\x0b`, `\f`/`\x0c`, `\x1c`, `\x1d`, `\x1e`, `\x85`
 * (NEL), ` ` (LINE SEPARATOR), ` ` (PARAGRAPH SEPARATOR).
 */
fun pythonSplitlines(text: String): List<String> {
    val result = mutableListOf<String>()
    val sb = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (isPythonLineBoundary(c)) {
            result.add(sb.toString())
            sb.setLength(0)
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                i++ // \r\n counts as a single boundary
            }
        } else {
            sb.append(c)
        }
        i++
    }
    if (sb.isNotEmpty()) {
        result.add(sb.toString())
    }
    return result
}

private fun isPythonLineBoundary(c: Char): Boolean = when (c) {
    '\n', '\r', '', '', '', '', '', '', ' ', ' ' -> true
    else -> false
}

/**
 * Construct a [ParsedMessage] from an accumulated line's fields. Twin of
 * `parser.py:_build_message`. Returns `null` and logs a warning if the
 * timestamp cannot be parsed -- the message is dropped, the file keeps
 * going.
 */
private fun buildMessage(
    current: CurrentMessage,
    chatId: String,
    formatKey: String,
    dateOrder: String,
    log: ParserLog,
): ParsedMessage? {
    val ts = try {
        parseTimestamp(current.dateStr, current.timeStr, formatKey, dateOrder)
    } catch (e: TimestampParseException) {
        log.warn(
            "Skipping message with unparseable timestamp '${current.dateStr} " +
                "${current.timeStr}': ${e.message}",
        )
        return null
    }

    val body = current.bodyLines.joinToString("\n")

    // Detect attachment lines (Phase 2.5). The body is kept verbatim for
    // hashing stability; attachmentFilename is a separate field for the HTML
    // renderer to use.
    var attachmentFilename: String? = null
    val strippedBody = pythonStrip(body)
    for (attRe in ATTACHMENT_RES) {
        val m = attRe.matcher(strippedBody)
        if (m.matches()) {
            attachmentFilename = pythonStrip(m.group(1))
            break
        }
    }

    return ParsedMessage(
        chatId = chatId,
        timestamp = ts,
        sender = current.sender,
        body = body,
        attachmentFilename = attachmentFilename,
    )
}

/** Compiled once — twin of `parser.py`'s module-level `_ATTACHMENT_RES`
 * (`re.IGNORECASE`). */
private val ATTACHMENT_RES: List<Pattern> = ATTACHMENT_PATTERNS.map {
    Pattern.compile(it, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE or Pattern.UNICODE_CHARACTER_CLASS)
}
