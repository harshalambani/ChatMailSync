package com.chatmailsync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A licence audit found the APK shipped no third-party notices at all. NOTICE
 * (repo root) now lists every component that reaches the distributed APK --
 * this test is the guard that keeps it that way: it fails if a runtime
 * `implementation` dependency declared in app/build.gradle.kts, or the
 * Chaquopy pip package, goes missing from NOTICE.
 *
 * It parses the *real* build.gradle.kts and the *real* NOTICE rather than
 * hardcoding an expected list, for the same reason FrozenIdentifiersTest
 * reads source files as text: a name that drifts between the two would pass
 * a hand-maintained expectation right along with it.
 */
class NoticeCoverageTest {

    // Same upward-walk pattern as FrozenIdentifiersTest.source(): Gradle's
    // JVM test working directory is a default, not a promise.
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative from ${File("").absolutePath}")
    }

    private val buildGradle by lazy { repoFile("app/build.gradle.kts").readText() }
    private val notice by lazy { repoFile("NOTICE").readText() }

    // Matches `implementation("group:artifact")` or
    // `implementation("group:artifact:version")` -- NOT debugImplementation
    // or testImplementation, which is the point: those two configurations
    // never reach a distributed APK, so a coverage check that swept them in
    // would demand notices for things that were never shipped.
    private val implementationDependency = Regex("""(?<![a-zA-Z])implementation\("([^"]+)"\)""")

    private fun runtimeDependencyCoordinates(): List<String> =
        implementationDependency.findAll(buildGradle)
            .map { it.groupValues[1] }
            .filterNot { it.startsWith("platform(") } // the compose-bom platform() call itself, not a component
            .toList()

    @Test
    fun `every runtime implementation dependency in build_gradle_kts appears in NOTICE`() {
        val missing = runtimeDependencyCoordinates().filterNot { coordinate ->
            // Compose-BOM-managed coordinates carry no version in Gradle
            // ("androidx.compose.ui:ui"); versioned ones do
            // ("androidx.activity:activity-compose:1.11.0"). NOTICE lists
            // both forms exactly as declared, so a plain substring match is
            // enough and deliberately does not normalise versions away --
            // a version bump with no matching NOTICE update should fail
            // this test too.
            notice.contains(coordinate)
        }
        assertTrue(
            "NOTICE is missing coverage for: $missing. Every `implementation` " +
                "dependency in app/build.gradle.kts must have an entry in the " +
                "repo-root NOTICE file.",
            missing.isEmpty(),
        )
    }

    @Test
    fun `the Chaquopy pip package is covered by NOTICE`() {
        val pipInstall = Regex("""install\("([^"]+)==([^"]+)"\)""").find(buildGradle)
            ?: throw AssertionError(
                "no pip install(...) call found in app/build.gradle.kts's chaquopy block"
            )
        val (packageName, version) = pipInstall.destructured
        assertTrue(
            "NOTICE does not mention the pinned pip package name '$packageName'",
            notice.contains(packageName),
        )
        assertTrue(
            "NOTICE does not mention the pinned pip version '$version' for '$packageName'",
            notice.contains(version),
        )
    }

    @Test
    fun `NOTICE also covers six, the transitive dependency of python-dateutil`() {
        // Not declared anywhere in build.gradle.kts (it rides in via
        // requirements-lock.txt's `# via python-dateutil` line), so the
        // parser above can't discover it on its own -- named directly here
        // instead.
        assertTrue("NOTICE does not mention six", notice.contains("six"))
    }

    @Test
    fun `test-only and debug-only artifacts are not required to be in NOTICE`() {
        // Negative test: junit is declared with testImplementation, never
        // implementation, so the coverage parser above must not have picked
        // it up as something requiring a NOTICE entry.
        assertFalse(
            "testImplementation(\"junit:junit:4.13.2\") was matched as a runtime " +
                "dependency, but junit is test-only and never ships in the APK",
            runtimeDependencyCoordinates().any { it.startsWith("junit:junit") },
        )
        // Likewise debugImplementation("androidx.compose.ui:ui-tooling") --
        // present in the file, stripped from every release build.
        assertFalse(
            "debugImplementation(\"androidx.compose.ui:ui-tooling\") was matched as a " +
                "runtime dependency, but it is debug-only and never ships in a release APK",
            runtimeDependencyCoordinates().any { it == "androidx.compose.ui:ui-tooling" },
        )
        // And NOTICE itself should say so, in its own words, rather than
        // silently listing them as shipped.
        assertTrue(
            "NOTICE no longer documents that junit is excluded as test-only",
            notice.contains("junit"),
        )
    }
}
