package com.chatmailsync.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * KT-13 step 1 guard: every route MainActivity registers has a smoke test that opens it, and no smoke test
 * names a route that no longer exists. Reads the two source files, so a new composable("...") with no
 * @Covers("...") test fails here, on the JVM, long before an emulator would notice.
 */
class RouteCoverageGuardTest {
    private val appDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "src") }.first { File(it, "main/java/com/chatmailsync/app/MainActivity.kt").exists() }
        .parentFile

    private fun routes(): Set<String> =
        Regex("""composable\(\s*(?:route\s*=\s*)?"([^"]+)"""")
            .findAll(File(appDir, "src/main/java/com/chatmailsync/app/MainActivity.kt").readText())
            .map { it.groupValues[1] }.toSet()

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
}
