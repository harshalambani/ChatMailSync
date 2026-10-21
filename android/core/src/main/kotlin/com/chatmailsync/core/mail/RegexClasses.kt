package com.chatmailsync.core.mail

import java.util.regex.Pattern

/*
 * Python-equivalent character classes, spelled out so no regex flag is needed.
 *
 * Why this file exists (CUT-02 step 3, found on a real phone): Python's `re` treats `\s` and `\d`
 * as Unicode in str patterns. The first port got that with the engine's Unicode-character-class flag (or its
 * inline form). Desktop Java accepts it. Android's java.util.regex is backed by ICU and
 * REJECTS it (IllegalArgumentException, "flag not supported"), so every class that compiled such a
 * pattern died on first use on the phone, while all JVM and Robolectric tests stayed green.
 *
 * The fix is to say what the classes mean instead of asking the engine to. Both pieces below use
 * only constructs that desktop Java and ICU treat the same: a bracket class of code points and
 * `\p{Nd}`. CASE_INSENSITIVE with UNICODE_CASE is supported on Android and is still used elsewhere.
 *
 * RULE: no Unicode-class flag, no inline switch form of it, no canonical-equivalence flag, anywhere in core or app. Android's ICU
 * regex rejects them at compile time, and JVM/Robolectric tests cannot see that because they run the
 * desktop engine. `AndroidRegexSafetyTest` fails the build on any of them; add a class here instead.
 */

/**
 * The body of a bracket class holding Python 3's `\s` for str patterns (== `str.isspace()`):
 * U+0009-000D, U+001C-0020, U+0085, U+00A0, U+1680, U+2000-200A, U+2028-2029, U+202F (narrow
 * no-break space, which WhatsApp puts before AM/PM), U+205F, U+3000. The four separators
 * U+001C-001F are what plain Unicode White_Space lacks and Python counts.
 */
internal const val PY_SPACE_CLASS_BODY =
    "\\u0009-\\u000D\\u001C-\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"

/** Python 3's `\d` for str patterns: any Unicode decimal digit (category Nd), e.g. Arabic-Indic, Devanagari. */
internal const val PY_DIGIT = "\\p{Nd}"

/**
 * Rewrites `\d`, `\D`, `\s`, `\S` in [source] to the explicit classes above and leaves every other
 * escape alone, so a pattern written the Python way can be compiled without any flag.
 * Handles escapes inside and outside a bracket class. Not a regex parser: it only supports the
 * patterns this module uses (no nested classes, no `\S`/`\D` inside a class), and says so loudly.
 */
internal fun pyClasses(source: String): String {
    val out = StringBuilder(source.length + 32)
    var inClass = false
    var i = 0
    while (i < source.length) {
        val c = source[i]
        if (c == '\\' && i + 1 < source.length) {
            val n = source[i + 1]
            when (n) {
                'd' -> out.append(PY_DIGIT)
                's' -> out.append(if (inClass) PY_SPACE_CLASS_BODY else "[$PY_SPACE_CLASS_BODY]")
                'S' -> {
                    require(!inClass) { "\\S inside a bracket class is not supported" }
                    out.append("[^$PY_SPACE_CLASS_BODY]")
                }
                'D' -> {
                    require(!inClass) { "\\D inside a bracket class is not supported" }
                    out.append("\\P{Nd}")
                }
                else -> out.append(c).append(n)
            }
            i += 2
            continue
        }
        if (!inClass && c == '[') inClass = true
        else if (inClass && c == ']') inClass = false
        out.append(c)
        i++
    }
    return out.toString()
}

/** Compile a Python-style pattern with Unicode `\d`/`\s` and no flag Android would reject. */
internal fun pyPattern(source: String, flags: Int = 0): Pattern = Pattern.compile(pyClasses(source), flags)
