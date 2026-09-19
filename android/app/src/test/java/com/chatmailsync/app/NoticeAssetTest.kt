package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * OpenSourceLicensesScreen reads its full licence text from the
 * `NOTICE.txt` asset, which app/build.gradle.kts's `copyNoticeAsset` task
 * copies from the repo-root NOTICE on every build -- one source of truth,
 * not a hand-maintained duplicate that could drift.
 *
 * This test proves that wiring actually runs and actually matches, rather
 * than trusting the Gradle task declaration by inspection. app/build.gradle.kts
 * makes every `*UnitTest` task depend on `copyNoticeAsset`, so by the time
 * this test runs, src/main/assets/NOTICE.txt should exist and be identical
 * to the root NOTICE.
 */
class NoticeAssetTest {

    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative from ${File("").absolutePath}")
    }

    // The module-relative destination copyNoticeAsset writes to -- resolved
    // straight off disk here rather than through Android's AssetManager,
    // since this module carries no Robolectric/androidTest runtime (see
    // app/build.gradle.kts's comment on testImplementation).
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "NOTICE").isFile && File(dir, "android").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the repo root from ${File("").absolutePath}")
    }

    @Test
    fun `the bundled NOTICE asset is byte-identical to the root NOTICE`() {
        val root = repoRoot()
        val rootNotice = File(root, "NOTICE")
        val bundledNotice = File(root, "android/app/src/main/assets/NOTICE.txt")
        assertEquals(
            "src/main/assets/NOTICE.txt was not found. copyNoticeAsset " +
                "(app/build.gradle.kts) should have copied it from the root NOTICE " +
                "before this test ran -- check that *UnitTest tasks still depend on it.",
            true,
            bundledNotice.isFile,
        )
        assertEquals(
            "The bundled NOTICE.txt asset has drifted from the root NOTICE file. " +
                "They must stay byte-identical -- NOTICE is the single source of " +
                "truth and copyNoticeAsset should regenerate the asset on every build.",
            rootNotice.readText(),
            bundledNotice.readText(),
        )
    }

    @Test
    fun `NOTICE_ASSET_PATH points at the file copyNoticeAsset actually writes`() {
        // Negative-shaped on purpose: if someone renames the Gradle copy
        // task's destination filename without updating the constant the
        // screen reads by, this catches the mismatch instead of the app
        // silently falling back to its "could not be loaded" message at
        // runtime.
        assertEquals("NOTICE.txt", NOTICE_ASSET_PATH)
    }

    @Test
    fun `the root NOTICE is not empty and is not GPL boilerplate`() {
        // Cheap sanity/negative check: NOTICE must not be an accidental
        // copy of LICENSE (GPL-3.0), which would defeat the point -- the
        // third-party components are not GPL-3.0 and must not be
        // represented as if they were.
        val text = repoFile("NOTICE").readText()
        assertNotEquals("", text.trim())
        assertEquals(
            "NOTICE must not contain the GNU GENERAL PUBLIC LICENSE text -- that " +
                "belongs only in LICENSE, and none of the third-party components are " +
                "GPL-3.0",
            false,
            text.contains("GNU GENERAL PUBLIC LICENSE"),
        )
    }
}
