package com.byokai.security

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Validates the Phase 6 security XML configs as committed under
 * `src/main/res/xml`. These run on the JVM via `testDebugUnitTest`
 * (unit-test working directory is the `app/` module directory, so the
 * source-tree paths below resolve both locally and on CI).
 *
 * The point is not to re-test Android's backup machinery — it is to
 * lock in the reviewed backup/network policy decisions so an accidental
 * edit (e.g. dropping the database exclude) fails the build instead of
 * silently weakening user-data protection.
 */
class SecurityConfigTest {

    private fun resXml(name: String): Element {
        val file = File("src/main/res/xml/$name")
        assertTrue("expected security config to exist at ${file.path}", file.isFile)
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        doc.documentElement.normalize()
        return doc.documentElement
    }

    /** Direct child elements of [parent] with the given tag name. */
    private fun children(parent: Element, tag: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = parent.getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.parentNode == parent && node is Element) out += node
        }
        return out
    }

    private fun excludes(parent: Element): List<Pair<String, String>> =
        children(parent, "exclude").map {
            it.getAttribute("domain") to it.getAttribute("path")
        }

    // ------------------------------------------------------------------
    // backup_rules.xml — full-backup-content for API 23-30
    // ------------------------------------------------------------------

    @Test
    fun `backup rules exclude the conversation database`() {
        val excludes = excludes(resXml("backup_rules.xml"))
        assertTrue(
            "backup_rules.xml must exclude byokai.db from device backups: $excludes",
            ("database" to "byokai.db") in excludes,
        )
    }

    @Test
    fun `backup rules keep the credential exclusions`() {
        val excludes = excludes(resXml("backup_rules.xml"))
        assertTrue(
            "encrypted credential DataStore must stay excluded: $excludes",
            ("file" to "datastore/byokai_credentials.preferences_pb") in excludes,
        )
        assertTrue(
            "Tink keyset sharedprefs must stay excluded: $excludes",
            ("sharedpref" to "byokai_tink_keyset.xml") in excludes,
        )
    }

    // ------------------------------------------------------------------
    // data_extraction_rules.xml — API 31+
    // ------------------------------------------------------------------

    private fun dataExtractionRules(): Element {
        val root = resXml("data_extraction_rules.xml")
        assertEquals("data-extraction-rules", root.tagName)
        return root
    }

    private fun section(tag: String): Element {
        val sections = children(dataExtractionRules(), tag)
        assertEquals("expected exactly one <$tag> section", 1, sections.size)
        return sections.single()
    }

    @Test
    fun `cloud backup excludes the conversation database`() {
        val excludes = excludes(section("cloud-backup"))
        assertTrue(
            "cloud-backup must exclude byokai.db (Auto Backup uploads to Drive): $excludes",
            ("database" to "byokai.db") in excludes,
        )
    }

    @Test
    fun `cloud backup keeps the credential exclusions`() {
        val excludes = excludes(section("cloud-backup"))
        assertTrue(
            "cloud-backup must exclude the credential DataStore: $excludes",
            ("file" to "datastore/byokai_credentials.preferences_pb") in excludes,
        )
        assertTrue(
            "cloud-backup must exclude the Tink keyset: $excludes",
            ("sharedpref" to "byokai_tink_keyset.xml") in excludes,
        )
    }

    @Test
    fun `device transfer excludes key material but keeps the database`() {
        val excludes = excludes(section("device-transfer"))
        assertTrue(
            "device-transfer must exclude the credential DataStore: $excludes",
            ("file" to "datastore/byokai_credentials.preferences_pb") in excludes,
        )
        assertTrue(
            "device-transfer must exclude the Tink keyset: $excludes",
            ("sharedpref" to "byokai_tink_keyset.xml") in excludes,
        )
        assertTrue(
            "device-transfer must NOT exclude byokai.db (conversations migrate " +
                "with the user over the direct connection): $excludes",
            excludes.none { (domain, path) -> domain == "database" && path.startsWith("byokai.db") },
        )
    }

    // ------------------------------------------------------------------
    // network_security_config.xml
    // ------------------------------------------------------------------

    @Test
    fun `network security config forbids cleartext traffic`() {
        val root = resXml("network_security_config.xml")
        assertEquals("network-security-config", root.tagName)
        val baseConfigs = children(root, "base-config")
        assertEquals("expected exactly one <base-config>", 1, baseConfigs.size)
        assertEquals(
            "cleartext must be forbidden (defense in depth over EndpointValidator)",
            "false",
            baseConfigs.single().getAttribute("cleartextTrafficPermitted"),
        )
    }
}
