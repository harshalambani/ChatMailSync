package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CUT-03 guard: the app no longer embeds a Python runtime. The sync engine is Kotlin, and the
 * embedded interpreter cost ~9 MB and carried four extra licence obligations for nothing.
 *
 * This reads the Gradle build files as text and fails if the Chaquopy plugin, its Maven
 * repository or a chaquopy { } block ever comes back. The APK-side twin is the "Fail if the APK
 * embeds a Python runtime" step in .github/workflows/codeql.yml.
 */
class NoEmbeddedPythonGuardTest {

    private val gradleFiles = listOf(
        "build.gradle.kts",
        "settings.gradle.kts",
        "app/build.gradle.kts",
        "core/build.gradle.kts",
    )

    private fun androidDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("could not locate android/settings.gradle.kts from ${File("").absolutePath}")
    }

    /** Returns one message per forbidden thing found in [text]; empty when clean. */
    internal fun violations(name: String, text: String): List<String> {
        val found = mutableListOf<String>()
        if (text.contains("com.chaquo.python")) found += "$name applies or declares the com.chaquo.python plugin"
        if (text.contains("chaquo.com/maven")) found += "$name adds the chaquo.com Maven repository"
        if (Regex("""chaquopy\s*\{""").containsMatchIn(text)) found += "$name has a chaquopy { } block"
        return found
    }

    @Test
    fun `no Gradle file brings the embedded Python runtime back`() {
        val base = androidDir()
        val all = gradleFiles.flatMap { name ->
            val f = File(base, name)
            assertTrue("expected build file is missing: $f", f.isFile)
            violations(name, f.readText())
        }
        assertEquals("the embedded Python runtime is back in the build: $all", emptyList<String>(), all)
    }

    @Test
    fun `the guard itself catches each way the runtime could return`() {
        // Negative test: prove the matcher is not vacuous.
        assertEquals(1, violations("x", """id("com.chaquo.python")""").size)
        assertEquals(1, violations("x", """maven("https://chaquo.com/maven")""").size)
        assertEquals(1, violations("x", "chaquopy {\n    defaultConfig { }\n}").size)
        assertEquals(0, violations("x", """id("com.android.application")""").size)
    }
}
