package com.chatmailsync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPEN_SOURCE_COMPONENTS is the in-app summary list the "Open-source
 * licences" screen renders above the full NOTICE text. Plain JUnit over the
 * constant, same shape as SettingsRowsTest -- no Compose needed to check a
 * list of data class values.
 */
class OpenSourceLicensesScreenTest {

    @Test
    fun `every shipped component family named in NOTICE has a summary row`() {
        val names = OPEN_SOURCE_COMPONENTS.map { it.name.lowercase() }
        for (expected in listOf("chaquopy", "cpython", "python-dateutil", "six", "androidx", "kotlin", "kotlinx.coroutines")) {
            assertTrue(
                "no OPEN_SOURCE_COMPONENTS row mentions '$expected'",
                names.any { it.contains(expected) },
            )
        }
    }

    @Test
    fun `no summary row is blank and every row names a licence`() {
        for (component in OPEN_SOURCE_COMPONENTS) {
            assertTrue("component name is blank: $component", component.name.isNotBlank())
            assertTrue("licence is blank for ${component.name}", component.licence.isNotBlank())
            assertTrue("upstream is blank for ${component.name}", component.upstream.isNotBlank())
        }
    }

    @Test
    fun `test-only and debug-only artifacts are not in the in-app summary`() {
        // Negative test: the audit that started this work found no notices
        // at all, not extra ones -- but a list that also claimed to ship
        // junit or the debug-only Compose tooling preview would be its own
        // kind of wrong (documenting something as shipped that never is).
        val haystack = OPEN_SOURCE_COMPONENTS.joinToString(" ") { "${it.name} ${it.upstream}" }
            .lowercase()
        assertFalse("junit must not appear in the shipped-component summary", haystack.contains("junit"))
        assertFalse(
            "the Android Gradle Plugin must not appear in the shipped-component summary",
            haystack.contains("android gradle plugin") || haystack.contains("agp"),
        )
    }

    @Test
    fun `GPL is not attributed to any third-party component`() {
        // The app itself is GPL-3.0; none of these third-party components
        // are, and mislabelling one would be worse than the missing-notices
        // problem this screen exists to fix.
        for (component in OPEN_SOURCE_COMPONENTS) {
            assertFalse(
                "${component.name} must not be labelled GPL",
                component.licence.contains("GPL", ignoreCase = true),
            )
        }
    }
}
