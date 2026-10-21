package com.byokai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [VersionInfo]. These run on the JVM via
 * `testDebugUnitTest` with no device or emulator required.
 */
class VersionInfoTest {

    @Test
    fun `parse accepts a well-formed version name`() {
        assertEquals(
            VersionInfo.SemanticVersion(major = 0, minor = 1, patch = 0),
            VersionInfo.parse("0.1.0"),
        )
    }

    @Test
    fun `parse accepts multi-digit components`() {
        assertEquals(
            VersionInfo.SemanticVersion(major = 2, minor = 10, patch = 2),
            VersionInfo.parse("2.10.2"),
        )
    }

    @Test
    fun `parse rejects malformed input`() {
        assertNull(VersionInfo.parse("0.1"))
        assertNull(VersionInfo.parse("1.2.3.4"))
        assertNull(VersionInfo.parse("a.b.c"))
        assertNull(VersionInfo.parse("1.2.x"))
        assertNull(VersionInfo.parse("1.-2.3"))
        assertNull(VersionInfo.parse(""))
    }

    @Test
    fun `format produces canonical version names`() {
        assertEquals(
            "2.10.2",
            VersionInfo.format(VersionInfo.SemanticVersion(2, 10, 2)),
        )
    }

    @Test
    fun `format and parse round-trip`() {
        val version = VersionInfo.SemanticVersion(1, 4, 7)
        assertEquals(version, VersionInfo.parse(VersionInfo.format(version)))
    }
}
