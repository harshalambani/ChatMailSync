package com.chatmailsync.core.mail

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Guards against invisible/control Unicode characters typed literally into
 * Kotlin sources -- e.g. the left-to-right mark U+200E, NBSP U+00A0, the
 * narrow no-break space U+202F, or a stray BOM U+FEFF -- instead of the
 * equivalent `\uXXXX` escape. Such characters are visually indistinguishable
 * (or nearly so) from ordinary whitespace, can be silently dropped or
 * mangled by editors/diff tools/copy-paste, and have already slipped into
 * this codebase once: MimeBuilder.kt, SelfSender.kt, and SelfSenderTest.kt
 * each carried one or more of them on `main` until they were escaped. This
 * test walks every `.kt`/`.kts` file under `:core`'s `main`, `test`, and
 * `harness` source sets and fails loudly if any of them reappear.
 *
 * The scanned set mirrors the audit that produced this test:
 * control characters (U+00-U+08, U+0B, U+0C, U+0E-U+1F, U+85), the
 * zero-width/format family (U+200B-U+200F, U+2028, U+2029, U+202F,
 * U+2060-U+2064), the BOM (U+FEFF), and NBSP (U+00A0).
 */
class SourceHygieneTest {

    @Test
    fun noKotlinSourceContainsRawInvisibleOrControlCharacters() {
        val srcDir = resolveCoreSrcDir()
        val violations = mutableListOf<String>()

        srcDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
            .sortedBy { it.path }
            .forEach { file ->
                val text = file.readText(Charsets.UTF_8)
                var line = 1
                var col = 0
                for (ch in text) {
                    if (ch == '\n') {
                        line++
                        col = 0
                        continue
                    }
                    col++
                    if (isGuardedInvisibleOrControlChar(ch)) {
                        val rel = file.relativeTo(srcDir).path
                        violations.add("$rel:$line:${"U+%04X".format(ch.code)} (col $col)")
                    }
                }
            }

        if (violations.isNotEmpty()) {
            fail(
                "Found ${violations.size} raw invisible/control character(s) in Kotlin " +
                    "sources under $srcDir -- replace each with its \\uXXXX escape (or, if it " +
                    "sits in a comment, a readable \"U+XXXX\" note):\n" +
                    violations.joinToString("\n"),
            )
        }
    }

    // NEGATIVE: proves the detector actually flags a guarded character,
    // rather than the main test vacuously passing because it never finds
    // anything. Built with an escape -- never a literal -- so this file
    // itself stays clean of the very characters the guard forbids.
    @Test
    fun detectorFlagsASyntheticStringContainingAGuardedCharacter() {
        val synthetic = "before\u200Eafter"
        assertTrue(synthetic.any { isGuardedInvisibleOrControlChar(it) })

        val clean = "before after"
        assertTrue(clean.none { isGuardedInvisibleOrControlChar(it) })
    }

    /**
     * Finds `:core`'s `src` directory robustly, without hardcoding an
     * absolute path. Gradle's `Test` task defaults `workingDir` to the
     * module's project directory, so `user.dir` is normally already
     * `.../android/core` when this runs under `:core:test`. To stay
     * correct if that ever changes (a different invocation, a differently
     * configured working directory, IDE "run test" from the repo root,
     * etc.), this also checks `user.dir` itself, `user.dir/core`, and
     * `user.dir/android/core` at each level while walking up the directory
     * tree, stopping at the first one that actually looks like the `:core`
     * module (i.e. has `src/main/kotlin/com/chatmailsync/core/mail`).
     */
    private fun resolveCoreSrcDir(): File {
        val marker = "src/main/kotlin/com/chatmailsync/core/mail"
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            val candidates = listOf(dir, File(dir, "core"), File(dir, "android/core"))
            for (candidate in candidates) {
                val markerDir = File(candidate, marker)
                if (markerDir.isDirectory) {
                    return File(candidate, "src")
                }
            }
            dir = dir.parentFile
            depth++
        }
        throw IllegalStateException(
            "Could not resolve the :core module's src/ directory from " +
                "user.dir=${System.getProperty("user.dir")}",
        )
    }
}

/**
 * The guarded character set: control characters, zero-width/format
 * characters, the BOM, NBSP, and the word-joiner/invisible-operator block.
 * Mirrors `[\x00-\x08\x0b\x0c\x0e-\x1f\x85\u200b-\u200f\u2028\u2029\u202f\ufeff]`
 * plus U+00A0 and U+2060-U+2064.
 */
private fun isGuardedInvisibleOrControlChar(ch: Char): Boolean {
    val code = ch.code
    return code in 0x00..0x08 ||
        code == 0x0B ||
        code == 0x0C ||
        code in 0x0E..0x1F ||
        code == 0x85 ||
        code in 0x200B..0x200F ||
        code == 0x2028 ||
        code == 0x2029 ||
        code == 0x202F ||
        code == 0xFEFF ||
        code == 0x00A0 ||
        code in 0x2060..0x2064
}
