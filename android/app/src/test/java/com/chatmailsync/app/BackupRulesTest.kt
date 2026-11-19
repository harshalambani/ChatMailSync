package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * F-04: cloud backup carries nothing; only a device-to-device transfer carries the dedup record.
 * An empty or exclude-light <cloud-backup> means "everything", so the guard checks that no
 * <include> exists there and that every storage domain is excluded outright.
 */
class BackupRulesTest {

    private val allDomains = setOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )

    private fun res(name: String): File =
        listOf("src/main/res/xml/$name", "app/src/main/res/xml/$name").map { File(it) }.first { it.isFile }

    private fun parse(f: File) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement

    private fun children(e: Element, tag: String): List<Element> {
        val out = mutableListOf<Element>()
        val n = e.getElementsByTagName(tag)
        for (i in 0 until n.length) out.add(n.item(i) as Element)
        return out
    }

    @Test
    fun `cloud backup includes nothing and excludes every domain`() {
        val root = parse(res("data_extraction_rules.xml"))
        val cloud = children(root, "cloud-backup").single()
        assertTrue("no include may appear under cloud-backup", children(cloud, "include").isEmpty())
        val excluded = children(cloud, "exclude").filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }.toSet()
        assertEquals(allDomains, excluded)
    }

    @Test
    fun `device transfer still carries the state db and the prefs, and nothing else`() {
        val root = parse(res("data_extraction_rules.xml"))
        val dt = children(root, "device-transfer").single()
        val inc = children(dt, "include").map { it.getAttribute("domain") + ":" + it.getAttribute("path") }.toSet()
        assertEquals(setOf("file:chatmailsync/data/sync_state.db", "sharedpref:chatmailsync_prefs.xml"), inc)
        assertFalse(inc.any { it.contains("inbox") || it.contains("processed") })
    }

    @Test
    fun `the pre-Android-12 rules include nothing and exclude every domain`() {
        val root = parse(res("full_backup_content.xml"))
        assertTrue(children(root, "include").isEmpty())
        val excluded = children(root, "exclude").filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }.toSet()
        assertEquals(allDomains, excluded)
    }
}
