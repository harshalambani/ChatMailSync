package com.chatmailsync.app

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KT-13 step 1 guard: every route the app registers has a smoke test that opens it, and no smoke test
 * names a route that no longer exists. Reads the source files, so a new composable("...") with no
 * @Covers("...") test fails here, on the JVM, long before an emulator would notice.
 *
 * Step 4 (D242 = T1): routes are registered in MainActivity.kt and in the Nav*.kt builders beside it, all read
 * through appMainSources(). A composable("...") in any other main source file fails here too, so a route cannot
 * hide in a file the guard does not read.
 */
class RouteCoverageGuardTest {
    private val appDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "src") }.first { File(it, "main/java/com/chatmailsync/app/MainActivity.kt").exists() }
        .parentFile

    private fun routes(): Set<String> = composableRoutesIn(appMainSources())

    private fun covered(): Set<String> =
        Regex("""@Covers\("([^"]+)"\)""")
            .findAll(File(appDir, "src/androidTest/java/com/chatmailsync/app/RouteSmokeTest.kt").readText())
            .map { it.groupValues[1] }.toSet()

    @Test
    fun everyRouteHasASmokeTest() {
        assertEquals("routes with no smoke test", emptySet<String>(), routes() - covered())
    }

    @Test
    fun noSmokeTestNamesAMissingRoute() {
        assertEquals("smoke tests for routes that do not exist", emptySet<String>(), covered() - routes())
    }

    @Test
    fun theGuardActuallyFindsRoutes() {
        // A regex that matched nothing would make both checks above pass vacuously.
        assertEquals(19, routes().size)
    }

    @Test
    fun noRouteIsRegisteredInAFileTheGuardDoesNotRead() {
        val read = appMainSourceFiles().map { it.canonicalPath }.toSet()
        val strays = File(appDir, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.canonicalPath !in read }
            .filter { composableRoutesIn(it.readText()).isNotEmpty() }
            .map { it.name }.toList()
        assertEquals("composable(\"...\") registered outside MainActivity.kt and Nav*.kt", emptyList<String>(), strays)
    }

    @Test
    fun theFourBuildersAreRead() {
        val names = appMainSourceFiles().map { it.name }
        for (n in listOf("MainActivity.kt", "NavHomeTab.kt", "NavChatsTab.kt", "NavSettingsTab.kt", "NavOtherRoutes.kt")) {
            assertTrue("$n is not read by appMainSources()", n in names)
        }
    }

    @Test
    fun aRouteInANewNavFileIsPickedUpButOneInAnyOtherFileIsNot() {
        val dir = Files.createTempDirectory("nav-guard").toFile()
        try {
            File(dir, "MainActivity.kt").writeText("// no routes here\n")
            File(dir, "NavZzNewTab.kt").writeText("fun NavGraphBuilder.zz() { composable(\"zz_new\") { } }\n")
            File(dir, "ElsewhereBuilder.kt").writeText("fun NavGraphBuilder.hidden() { composable(\"zz_hidden\") { } }\n")
            val found = composableRoutesIn(appMainSources(dir))
            assertTrue("a route in a new Nav*.kt must be found", "zz_new" in found)
            assertFalse("a route in a non-Nav file is not read, which is why the stray-file test above exists", "zz_hidden" in found)
        } finally {
            dir.deleteRecursively()
        }
    }
}
