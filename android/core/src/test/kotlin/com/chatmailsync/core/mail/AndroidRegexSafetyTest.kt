package com.chatmailsync.core.mail

import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * CUT-02 step 3 (the Nord crash). Android's java.util.regex is ICU-backed and REJECTS the Unicode
 * character-class flag, its inline form and CANON_EQ with IllegalArgumentException. Desktop-JVM and
 * Robolectric tests run the desktop regex engine, so they cannot see it. This file is the part that
 * CAN be checked off-device:
 *   (1) a source scan that fails if any such construct comes back into core or app main;
 *   (2) parity of the explicit classes against what Python 3 re gives for str patterns.
 *
 * HOW THE EXPECTED VALUES BELOW WERE MADE (do not edit by hand): Python 3.14.4, re module, run on
 * 21 Oct 2026 with `re.compile(r"\s")` fullmatch over every code point U+0000..U+10FFFF (ranges),
 * and with the real `src/parser.py` (`parse_file`, `extract_chat_info`) on the texts in
 * [CASES]. Placeholder names only.
 */
class AndroidRegexSafetyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------------------------------------------------------- (1) the guard

    /** Constructs Android's ICU regex rejects (or treats differently from desktop Java). */
    private val forbidden: List<Pair<String, Regex>> = listOf(
        "the Unicode character-class flag" to Regex("UNICODE_CHARACTER_CLASS"),
        "CANON_EQ" to Regex("CANON_EQ"),
        "inline (?U) flag, alone or combined" to Regex("""\(\?[a-zA-Z]*U[a-zA-Z-]*[:)]"""),
        // \R \X \h \v \H \V mean different things (or nothing) between desktop Java and ICU.
        "\\R \\X \\h \\v \\H \\V in a code line" to Regex("""\\{1,2}[RXhvHV](?![A-Za-z0-9_])"""),
    )

    private fun isCommentLine(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
    }

    /** Returns one message per offence in [text]; comment lines are skipped only for the escape-letter rule. */
    internal fun offences(name: String, text: String): List<String> {
        val out = ArrayList<String>()
        text.lines().forEachIndexed { i, line ->
            for ((what, re) in forbidden) {
                val isEscapeRule = what.startsWith("\\R")
                if (isEscapeRule && isCommentLine(line)) continue
                if (re.containsMatchIn(line)) out.add("$name:${i + 1}: $what")
            }
        }
        return out
    }

    private fun androidRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "core/src/main").isDirectory && File(dir, "app/src/main").isDirectory)) {
            dir = dir.parentFile
        }
        return dir ?: error("android/ root with core/src/main and app/src/main not found")
    }

    @Test
    fun noSourceUsesAFlagAndroidRegexRejects() {
        val root = androidRoot()
        val files = listOf("core/src/main", "app/src/main")
            .flatMap { File(root, it).walkTopDown().filter { f -> f.isFile && (f.extension == "kt" || f.extension == "java") }.toList() }
        assertTrue("scanner looked at too few files: ${files.size}", files.size > 40)
        val bad = files.flatMap { offences(it.name, it.readText()) }
        assertTrue("Android ICU regex rejects/differs on:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    // NEGATIVE: the scanner itself must catch a planted bad pattern (in-memory, no file).
    @Test
    fun scannerCatchesPlantedBadPatterns() {
        val flag = "Pattern." + "UNICODE_" + "CHARACTER_CLASS"
        val canon = "Pattern." + "CANON" + "_EQ"
        val inline1 = "Regex(\"(?" + "U)abc\")"
        val inline2 = "Regex(\"(?i" + "U:abc)\")"
        val esc = "Regex(\"a\\\\" + "h b\")"
        for (planted in listOf(flag, canon, inline1, inline2)) {
            assertTrue("not caught: $planted", offences("planted.kt", "val p = $planted").isNotEmpty())
        }
        assertTrue("escape not caught: $esc", offences("planted.kt", "val p = $esc").isNotEmpty())
        // ...and it does not cry wolf on what is allowed.
        val ok = listOf(
            "Pattern.compile(x, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)",
            "Regex(\"(?i)abc\")",
            "Regex(\"(?<name>[a-z]+)\")",
            "// a comment about \\h is fine",
            " * and so is a KDoc line with \\R in it",
        )
        for (line in ok) assertTrue("false alarm: $line", offences("ok.kt", line).isEmpty())
    }

    // ---------------------------------------------------------------- (2) parity with Python

    /** `re.compile(r"\s")` fullmatch ranges, Python 3.14.4 (identical to `str.isspace()`). */
    private val pythonSpaceRanges = listOf(
        0x0009..0x000D, 0x001C..0x0020, 0x0085..0x0085, 0x00A0..0x00A0, 0x1680..0x1680,
        0x2000..0x200A, 0x2028..0x2029, 0x202F..0x202F, 0x205F..0x205F, 0x3000..0x3000,
    )

    private fun pythonSpace(cp: Int) = pythonSpaceRanges.any { cp in it }

    @Test
    fun backslashSMatchesExactlyWhatPythonMatchesForEveryCodePoint() {
        val s = pyPattern("\\s")
        val notS = pyPattern("\\S")
        val classOnly = Regex("[$PY_SPACE_CLASS_BODY]")
        var cp = 0
        while (cp <= 0x10FFFF) {
            if (cp !in 0xD800..0xDFFF) {
                val str = String(Character.toChars(cp))
                val py = pythonSpace(cp)
                assertEquals("\\s at U+%04X".format(cp), py, s.matcher(str).matches())
                assertEquals("\\S at U+%04X".format(cp), !py, notS.matcher(str).matches())
                assertEquals("class at U+%04X".format(cp), py, classOnly.matches(str))
            }
            cp++
        }
    }

    @Test
    fun backslashDMatchesPythonDigitsAndNothingElseInTheSamples() {
        val d = pyPattern("\\d")
        // Python: True for these (all category Nd).
        val digits = listOf(0x30, 0x39, 0x0663, 0x06F5, 0x096D, 0x09EA, 0x0E55, 0xFF15)
        // Python: False for these (No, Nl, Mn, letters, underscore, NBSP).
        val nonDigits = listOf(0x00B2, 0x00BD, 0x2167, 0x0301, 0x65, 0x5F, 0xA0, 0x2460)
        for (cp in digits) assertTrue("U+%04X".format(cp), d.matcher(String(Character.toChars(cp))).matches())
        for (cp in nonDigits) assertFalse("U+%04X".format(cp), d.matcher(String(Character.toChars(cp))).matches())
        // \D is the exact complement on the samples.
        val notD = pyPattern("\\D")
        for (cp in digits) assertFalse(notD.matcher(String(Character.toChars(cp))).matches())
        for (cp in nonDigits) assertTrue(notD.matcher(String(Character.toChars(cp))).matches())
    }

    // NEGATIVE: a combining mark is neither \s nor \d (Python: d=False s=False; and not \w either),
    // so it must not be swallowed by the explicit classes.
    @Test
    fun combiningMarkIsNeitherSpaceNorDigit() {
        val mark = "\u0301"
        assertFalse(pyPattern("\\s").matcher(mark).matches())
        assertFalse(pyPattern("\\d").matcher(mark).matches())
        // Python: re.sub(r"[^a-z0-9\s]", "", "caf\u00e9\u0301 1\u0663") == "caf 1"
        assertEquals("caf 1", pyPattern("[^a-z0-9\\s]").matcher("caf\u00e9\u0301 1\u0663").replaceAll(""))
        // Python: re.split(r"\s+", "a\x1fb") == ["a", "b"]
        assertEquals(listOf("a", "b"), pyPattern("\\s+").split("a\u001Fb").toList())
        // Python: "\u00a012:30\u202fPM".strip() keeps the narrow no-break space inside.
        assertEquals("a b", pyPattern("\\s+").matcher("a\u202F\u00A0 b").replaceAll(" "))
    }

    private data class Row(val ts: String, val sender: String, val body: String, val attach: String?)

    /** Texts fed to the real Python parser; the expected rows are what it returned. */
    private val CASES: Map<String, Pair<String, List<Row>>> = mapOf(
        "nnbsp_pm" to ("21/10/2025, 9:05\u202fpm - Meera Iyer: hello\n21/10/2025, 9:06\u202fpm - Rohan Mehta: hi\n" to listOf(
            Row("2025-10-21T21:05:00", "Meera Iyer", "hello", null), Row("2025-10-21T21:06:00", "Rohan Mehta", "hi", null))),
        "nbsp_pm" to ("21/10/2025, 9:05\u00a0pm - Meera Iyer: hello\n21/10/2025, 9:06\u00a0pm - Rohan Mehta: hi\n" to listOf(
            Row("2025-10-21T21:05:00", "Meera Iyer", "hello", null), Row("2025-10-21T21:06:00", "Rohan Mehta", "hi", null))),
        "arabic_indic" to ("\u0662\u0661/\u0661\u0660/\u0662\u0660\u0662\u0665, \u0661\u0660:\u0660\u0665 - Meera Iyer: hello\n" +
            "\u0662\u0661/\u0661\u0660/\u0662\u0660\u0662\u0665, \u0661\u0660:\u0660\u0666 - Rohan Mehta: hi\n" to listOf(
            Row("2025-10-21T10:05:00", "Meera Iyer", "hello", null), Row("2025-10-21T10:06:00", "Rohan Mehta", "hi", null))),
        "devanagari" to ("\u0968\u0967/\u0967\u0966/\u0968\u0966\u0968\u096b, \u0967\u0966:\u0966\u096b - Meera Iyer: hello\n" +
            "\u0968\u0967/\u0967\u0966/\u0968\u0966\u0968\u096b, \u0967\u0966:\u0966\u096c - Rohan Mehta: hi\n" to listOf(
            Row("2025-10-21T10:05:00", "Meera Iyer", "hello", null), Row("2025-10-21T10:06:00", "Rohan Mehta", "hi", null))),
        // U+001F is \s for Python but NOT a line boundary for str.splitlines: it stays inside the body.
        "us_1f_body" to ("10/21/25, 9:05 PM - Meera Iyer: hi\u001fthere\n10/21/25, 9:06 PM - Rohan Mehta: ok\n" to listOf(
            Row("2025-10-21T21:05:00", "Meera Iyer", "hi\u001Fthere", null), Row("2025-10-21T21:06:00", "Rohan Mehta", "ok", null))),
        // NEGATIVE: U+001F where the literal " - " must be does NOT make a message line (Python drops it).
        "us_1f_before_dash" to ("10/21/25, 9:05 PM\u001f- Meera Iyer: hi\n10/21/25, 9:06 PM - Rohan Mehta: ok\n" to listOf(
            Row("2025-10-21T21:06:00", "Rohan Mehta", "ok", null))),
        // U+001C and U+0085 ARE splitlines boundaries: the second half is a continuation line.
        "fs_1c_split" to ("10/21/25, 9:05 PM - Meera Iyer: a\u001cb\n10/21/25, 9:06 PM - Rohan Mehta: ok\n" to listOf(
            Row("2025-10-21T21:05:00", "Meera Iyer", "a\nb", null), Row("2025-10-21T21:06:00", "Rohan Mehta", "ok", null))),
        "nel_split" to ("10/21/25, 9:05 PM - Meera Iyer: a\u0085b\n10/21/25, 9:06 PM - Rohan Mehta: ok\n" to listOf(
            Row("2025-10-21T21:05:00", "Meera Iyer", "a\nb", null), Row("2025-10-21T21:06:00", "Rohan Mehta", "ok", null))),
        "combining_sender" to ("10/21/25, 9:05 PM - Mi\u0301a Iyer: caf\u00e9\u0301 1\u0663\n10/21/25, 9:06 PM - Rohan Mehta: ok\n" to listOf(
            Row("2025-10-21T21:05:00", "Mi\u0301a Iyer", "caf\u00E9\u0301 1\u0663", null), Row("2025-10-21T21:06:00", "Rohan Mehta", "ok", null))),
        "attach_nbsp" to ("10/21/25, 9:05 PM - Meera Iyer: IMG-1.jpg\u00a0(file attached)\n10/21/25, 9:06 PM - Rohan Mehta: ok\n" to listOf(
            Row("2025-10-21T21:05:00", "Meera Iyer", "IMG-1.jpg\u00A0(file attached)", "IMG-1.jpg"), Row("2025-10-21T21:06:00", "Rohan Mehta", "ok", null))),
    )

    @Test
    fun parserGivesWhatThePythonParserGaveOnEveryCase() {
        for ((name, case) in CASES) {
            val (text, expected) = case
            val f = tmp.newFile("$name.txt")
            f.writeBytes(text.toByteArray(Charsets.UTF_8))
            val got = parseFile(f, "chat").toList().map {
                Row(it.timestamp.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")), it.sender, it.body, it.attachmentFilename)
            }
            assertEquals("case $name", expected, got)
        }
    }

    @Test
    fun extractChatInfoGivesWhatThePythonParserGave() {
        val expected = listOf(
            "WhatsApp Chat with Mi\u0301a Iyer.zip" to ChatInfo("mia_iyer", "Mi\u0301a Iyer"),
            "WhatsApp Chat with Meera  Iyer (2).txt" to ChatInfo("meera_iyer", "Meera  Iyer"),
            "WhatsApp Chat with Caf\u00E9\u0301 1\u0663.txt" to ChatInfo("caf_1", "Caf\u00E9\u0301 1\u0663"),
            "WhatsApp Chat with Meera\u00A0Iyer.txt" to ChatInfo("meera_iyer", "Meera\u00A0Iyer"),
            "WhatsApp Chat with Meera\u202FIyer (3).txt" to ChatInfo("meera_iyer", "Meera\u202FIyer"),
            "WhatsApp Chat with Meera\u001FIyer.txt" to ChatInfo("meera_iyer", "Meera\u001FIyer"),
        )
        for ((fn, info) in expected) assertEquals(fn, info, extractChatInfo(fn))
    }
}
