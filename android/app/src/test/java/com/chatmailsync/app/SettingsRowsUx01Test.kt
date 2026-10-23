package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX-01: Settings and Advanced became list rows, with dialogs, a Help
 * sub-screen and a labelled back on "Run setup again". There is no Compose
 * test rig here, so the behaviour is pinned the way RunSetupAgainTest does it:
 * pure functions directly, and the wiring by reading the source.
 */
class SettingsRowsUx01Test {

    private fun source(name: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java/com/chatmailsync/app/$name")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $name from ${File("").absolutePath}")
    }

    private fun functionBody(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val next = text.indexOf("\n@Composable", start + signature.length)
        return if (next < 0) text.substring(start) else text.substring(start, next)
    }

    // -- Advanced: the three sections ----------------------------------------

    @Test
    fun `advanced has the three headers in order with the rows the brief names`() {
        assertEquals(
            listOf("Automatic import", "What gets sent", "Testing & tools"),
            ADVANCED_SECTIONS.map { it.header },
        )
        assertEquals(
            listOf("watched_folder", "auto_import", "watch_interval", "after_import", "check_now"),
            ADVANCED_SECTIONS[0].rows.map { it.id },
        )
        assertEquals(listOf("cutoff_date", "chunk_size"), ADVANCED_SECTIONS[1].rows.map { it.id })
        assertEquals(
            listOf("test_run", "test_connection", "sync_log", "run_setup_again"),
            ADVANCED_SECTIONS[2].rows.map { it.id },
        )
    }

    @Test
    fun `Test run is not under What gets sent`() {
        val sent = ADVANCED_SECTIONS.first { it.id == "what_gets_sent" }.rows.map { it.id }
        assertFalse("test_run must not be under What gets sent", sent.contains("test_run"))
        val testing = ADVANCED_SECTIONS.first { it.id == "testing_tools" }.rows.map { it.id }
        assertTrue(testing.contains("test_run"))
    }

    @Test
    fun `advanced still holds every row it held before UX-01`() {
        val before = listOf(
            "watched_folder", "auto_import", "watch_interval", "after_import",
            "cutoff_date", "test_run", "sync_log", "chunk_size", "test_connection",
        )
        val now = ADVANCED_SETTINGS_ROWS.map { it.id }
        for (id in before) assertTrue("$id was dropped from Advanced", now.contains(id))
        assertEquals("a row id appears twice", now.size, now.toSet().size)
    }

    // -- No setting key renamed or dropped -----------------------------------

    @Test
    fun `no preference key was renamed or dropped`() {
        val prefs = source("AppPrefs.kt")
        for (key in listOf(
            "watched_folder_uri", "auto_watch_enabled", "theme_mode", "watch_interval_minutes",
            "synced_file_policy", "chunk_size", "dry_run_default", "cutoff_date", "first_run_done",
        )) {
            assertTrue("AppPrefs key \"$key\" is gone", prefs.contains("\"$key\""))
        }
        assertTrue(prefs.contains("\"chatmailsync_prefs\""))
    }

    @Test
    fun `the Advanced screen still writes through the same setters`() {
        val main = source("MainActivity.kt")
        for (call in listOf(
            "AppPrefs.setCutoffDate(context, it)",
            "AppPrefs.setDryRunDefault(context, it)",
            "AppPrefs.setChunkSize(context, it)",
            "setWatchInterval(it)",
            "setSyncedFilePolicy(it)",
            "setAutoWatch(it)",
        )) {
            assertTrue("MainActivity no longer calls $call", main.contains(call))
        }
        // The screen keeps every parameter MainActivity feeds it.
        val screen = source("AdvancedSettingsScreen.kt")
        for (param in listOf(
            "onCutoffDateChange", "onDryRunDefaultChange", "onChunkSizeChange",
            "onWatchIntervalChange", "onSyncedFilePolicyChange", "onAutoWatchChange",
            "onCheckNow", "onTestConnection", "onRunSetupAgain", "onOpenSyncLog",
        )) {
            assertTrue("AdvancedSettingsScreen lost $param", screen.contains(param))
        }
    }

    // -- Dialogs: Cancel leaves the stored value unchanged -------------------

    @Test
    fun `cancel in an option dialog keeps the stored value`() {
        // Theme, check interval, after import and chunk size all go through
        // committedChoice via OptionDialog.
        assertEquals("system", committedChoice("system", "dark", confirmed = false))
        assertEquals(60L, committedChoice(60L, 1440L, confirmed = false))
        assertEquals("leave", committedChoice("leave", "delete", confirmed = false))
        assertEquals("day", committedChoice("day", "week", confirmed = false))
    }

    @Test
    fun `OK in an option dialog commits the pick`() {
        assertEquals("dark", committedChoice("system", "dark", confirmed = true))
    }

    @Test
    fun `cancel in the cutoff dialog keeps the stored date`() {
        assertEquals("2026-01-01", committedCutoff("2026-01-01", "2026-06-30", confirmed = false))
        assertEquals("", committedCutoff("", "2026-06-30", confirmed = false))
    }

    @Test
    fun `an unreadable cutoff is never committed even on OK`() {
        assertEquals("2026-01-01", committedCutoff("2026-01-01", "2026-0", confirmed = true))
        assertEquals("2026-01-01", committedCutoff("2026-01-01", "not a date", confirmed = true))
    }

    @Test
    fun `OK with a readable cutoff commits it trimmed, blank clears it`() {
        assertEquals("2026-06-30", committedCutoff("", " 2026-06-30 ", confirmed = true))
        assertEquals("", committedCutoff("2026-06-30", "", confirmed = true))
    }

    @Test
    fun `every dialog has a Cancel and the old dropdowns are gone`() {
        val rows = source("SettingsRows.kt")
        assertEquals(
            "OptionDialog and CutoffDialog must each offer Cancel",
            2,
            Regex("""Text\("Cancel"\)""").findAll(rows).count(),
        )
        assertFalse(source("SettingsScreen.kt").contains("DropdownMenu"))
        assertFalse(source("AdvancedSettingsScreen.kt").contains("DropdownMenu"))
    }

    // -- Settings rows -------------------------------------------------------

    @Test
    fun `Settings has one Help row and the old Help block is gone`() {
        assertEquals(1, BASIC_SETTINGS_ROWS.count { it.id == "help_about" })
        val screen = source("SettingsScreen.kt")
        assertFalse(screen.contains("Privacy policy"))
        assertFalse(screen.contains("Open-source licences"))
        assertFalse(screen.contains("BuildConfig"))
        assertEquals(
            listOf("help_faq", "privacy_policy", "licences", "app_version"),
            HELP_ROWS.map { it.id },
        )
    }

    @Test
    fun `the Advanced row subtitle is the wording the brief gives`() {
        assertEquals("Automatic import, cut-off date, testing & tools", ADVANCED_ROW_SUBTITLE)
    }

    @Test
    fun `cutoff row says None when blank and the date otherwise`() {
        assertEquals("None: everything is sent", cutoffSubtitle(""))
        assertEquals("None: everything is sent", cutoffSubtitle("  "))
        assertEquals("2026-04-01", cutoffSubtitle("2026-04-01"))
    }

    @Test
    fun `app version text carries name, code and the debug marker only on debug`() {
        assertEquals("Chat Mail Sync 3.0.0 (46)", appVersionText("3.0.0", 46, debug = false))
        assertTrue(appVersionText("3.0.0", 46, debug = true).endsWith("debug build"))
        assertFalse(appVersionText("3.0.0", 46, debug = false).contains("debug"))
    }

    // -- Colour key ----------------------------------------------------------

    @Test
    fun `the colour key maps rows to value, contents or plain`() {
        assertEquals(SubtitleKind.VALUE, BASIC_SUBTITLE_KINDS["mail_account"])
        assertEquals(SubtitleKind.VALUE, BASIC_SUBTITLE_KINDS["me"])
        assertEquals(SubtitleKind.VALUE, BASIC_SUBTITLE_KINDS["theme"])
        assertEquals(SubtitleKind.CONTENTS, BASIC_SUBTITLE_KINDS["advanced"])
        assertEquals(SubtitleKind.CONTENTS, BASIC_SUBTITLE_KINDS["help_about"])
        assertEquals(SubtitleKind.PLAIN, BASIC_SUBTITLE_KINDS["backup_restore"])
        for (row in BASIC_SETTINGS_ROWS) {
            assertTrue("${row.id} has no colour kind", BASIC_SUBTITLE_KINDS.containsKey(row.id))
        }
    }

    @Test
    fun `colours come from theme roles, never hex literals`() {
        val hex = Regex("""Color\(0x""")
        for (name in listOf("SettingsRows.kt", "AdvancedSettingsScreen.kt", "HelpAboutScreen.kt")) {
            assertFalse("$name contains a hex colour literal", hex.containsMatchIn(source(name)))
        }
        // SettingsScreen.kt keeps only the pre-existing amber pill pair (4 values).
        assertEquals(4, hex.findAll(source("SettingsScreen.kt")).count())
        val rows = source("SettingsRows.kt")
        assertTrue(rows.contains("colorScheme.primary"))
        assertTrue(rows.contains("colorScheme.tertiary"))
        assertTrue(rows.contains("colorScheme.onSurfaceVariant"))
        assertTrue(rows.contains("FontWeight.Medium"))
    }

    @Test
    fun `section headers are onSurface, not primary`() {
        val header = functionBody(source("SettingsRows.kt"), "internal fun SettingsSectionHeader")
        assertTrue(header.contains("colorScheme.onSurface"))
        assertFalse(header.contains("colorScheme.primary"))
        assertTrue(header.contains("uppercase()"))
        assertTrue(header.contains("letterSpacing"))
    }

    // -- Back navigation -----------------------------------------------------

    @Test
    fun `the Help sub-screen has a route, a back label and lights the Settings tab`() {
        assertEquals("Help", backLabelForRoute("helpAbout"))
        assertEquals("settings", tabForRoute("helpAbout"))
        assertTrue(source("MainActivity.kt").contains("composable(\"helpAbout\")"))
    }

    @Test
    fun `every route reachable from Settings resolves to a real back label`() {
        val main = source("MainActivity.kt")
        for (route in SETTINGS_REACHABLE_ROUTES) {
            assertNotEquals("$route falls through to a bare Back", "Back", backLabelForRoute(route))
            assertTrue(
                "$route has no composable in MainActivity",
                main.contains("composable(\"$route\")"),
            )
        }
    }

    @Test
    fun `screens opened from Help say Help`() {
        assertEquals("Help", backLabelForRoute("helpAbout"))
        val main = source("MainActivity.kt")
        for (route in listOf("help", "privacy", "licenses")) {
            val block = main.substringAfter("composable(\"$route\")").substringBefore("composable(\"")
            assertTrue(
                "$route does not derive its back label from the stack",
                block.contains("backLabelForRoute(from)"),
            )
        }
    }

    @Test
    fun `the welcome step has no back on a fresh install`() {
        assertNull(welcomeBackLabel(null))
        // And the screen only draws a back when it was given a label.
        val screen = source("FirstRunScreen.kt")
        assertTrue(screen.contains("onBack = if (backLabel != null) onBack else null"))
        assertTrue(source("MainActivity.kt").contains("backLabel = welcomeBackLabel(cameFrom)"))
    }

    @Test
    fun `Run setup again shows a back to Advanced`() {
        assertEquals("Advanced", welcomeBackLabel("advancedSettings"))
        assertNotEquals("Advanced", welcomeBackLabel(null))
    }
}
