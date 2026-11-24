package com.chatmailsync.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA-04: the test JVM's java.io.tmpdir is inside this module's build folder, never in the OS temp
 * folder, so a local test run leaves nothing behind in %TEMP%. Set by the module's build.gradle.kts.
 */
class JvmTmpDirGuardTest {
    private fun canon(f: File): File = f.canonicalFile

    @Test
    fun theTestJvmTempFolderIsInsideThisModulesBuildFolder() {
        val tmp = canon(File(System.getProperty("java.io.tmpdir")))
        val build = canon(File("build"))
        assertEquals("the folder build.gradle.kts sets", canon(File(build, "tmp/jvm-tests")), tmp)
        assertTrue("tmpdir $tmp is not inside $build", tmp.path.startsWith(build.path + File.separator))
    }

    @Test
    fun theTestJvmTempFolderIsNotTheOsTempFolder() {
        val tmp = canon(File(System.getProperty("java.io.tmpdir")))
        val build = canon(File("build"))
        val os = listOf(System.getenv("TEMP"), System.getenv("TMP"), System.getenv("TMPDIR"), "/tmp")
            .filterNotNull().filter { it.isNotBlank() }.map { canon(File(it)) }
        for (o in os) {
            // A checkout that itself sits under the OS temp folder cannot tell the two apart.
            if (build.path.startsWith(o.path + File.separator)) continue
            assertFalse("tmpdir $tmp is inside the OS temp folder $o", tmp.path == o.path || tmp.path.startsWith(o.path + File.separator))
        }
    }

    @Test
    fun aTempFileLandsInTheBuildFolder() {
        val f = File.createTempFile("qa04guard", ".tmp")
        try {
            assertEquals(canon(File(System.getProperty("java.io.tmpdir"))), canon(f.parentFile))
        } finally {
            f.delete()
        }
    }
}
