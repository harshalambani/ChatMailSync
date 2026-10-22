package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * LIC-02 guard: junit:junit:4.13.2 is EPL-1.0. It is fine on a test classpath and must never reach
 * a shipped configuration (implementation, api, runtimeOnly, compileOnly, debugImplementation,
 * releaseImplementation ...), because NOTICE and the in-app licence screen would then be wrong
 * and the APK would carry an unlisted EPL component.
 *
 * Reads the app and core build files as text. The CI step "Fail if JUnit reaches a shipped
 * classpath" in tests.yml checks the resolved releaseRuntimeClasspath as well.
 */
class JunitTestOnlyGuardTest {

    private val allowed = setOf("testImplementation", "androidTestImplementation")

    private fun androidDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("could not locate android/settings.gradle.kts from ${File("").absolutePath}")
    }

    /** One message per junit:junit line declared in a configuration that is not test-only. */
    internal fun violations(name: String, text: String): List<String> {
        val declaration = Regex("""^\s*"?([A-Za-z0-9_]+)"?\s*\(\s*"junit:junit""")
        return text.lines().withIndex()
            .filter { (_, line) -> line.contains("junit:junit") && !line.trimStart().startsWith("//") }
            .mapNotNull { (i, line) ->
                val config = declaration.find(line)?.groupValues?.get(1)
                // A line that mentions junit:junit but is not a plain declaration is a violation too:
                // the guard cannot prove it is test-only.
                if (config == null || config !in allowed) "$name:${i + 1} declares junit:junit in '${config ?: "?"}'" else null
            }
    }

    @Test
    fun `junit is declared only in test configurations`() {
        val base = androidDir()
        val found = listOf("app/build.gradle.kts", "core/build.gradle.kts").flatMap { name ->
            val f = File(base, name)
            assertTrue("expected build file is missing: $f", f.isFile)
            violations(name, f.readText())
        }
        assertEquals("junit:junit must stay test-only: $found", emptyList<String>(), found)
    }

    @Test
    fun `the guard catches junit in every shipped configuration and accepts the test ones`() {
        // Negative tests: prove the matcher is not vacuous.
        for (bad in listOf("implementation", "api", "runtimeOnly", "compileOnly", "debugImplementation", "releaseImplementation")) {
            assertEquals(bad, 1, violations("x", """    $bad("junit:junit:4.13.2")""").size)
        }
        assertEquals(1, violations("x", """    "implementation"("junit:junit:4.13.2")""").size)
        assertEquals(1, violations("x", """    implementation(libs.junit) // junit:junit via catalog""").size)
        assertEquals(0, violations("x", """    testImplementation("junit:junit:4.13.2")""").size)
        assertEquals(0, violations("x", """    androidTestImplementation("junit:junit:4.13.2")""").size)
        assertEquals(0, violations("x", """    // implementation("junit:junit:4.13.2")""").size)
    }
}
