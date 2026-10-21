package com.byokai.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ThemeMode] storage-key mapping. Runs on the JVM.
 */
class ThemeModeTest {

    @Test
    fun `storage keys round-trip to the same enum value`() {
        for (mode in ThemeMode.entries) {
            assertEquals(mode, ThemeMode.fromStorageKey(mode.storageKey))
        }
    }

    @Test
    fun `unknown or missing key falls back to SYSTEM`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageKey("sepia"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageKey(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageKey(null))
    }

    @Test
    fun `storage keys are distinct`() {
        val keys = ThemeMode.entries.map { it.storageKey }
        assertEquals(keys.size, keys.toSet().size)
    }
}
