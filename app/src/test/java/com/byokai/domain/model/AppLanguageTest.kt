package com.byokai.domain.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [AppLanguage] storage-key mapping. Runs on the JVM.
 */
class AppLanguageTest {

    @Test
    fun `storage keys round-trip to the same enum value`() {
        for (language in AppLanguage.entries) {
            assertEquals(language, AppLanguage.fromStorageKey(language.storageKey))
        }
    }

    @Test
    fun `unknown or missing key falls back to ENGLISH`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromStorageKey("fr"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromStorageKey(""))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromStorageKey(null))
    }

    @Test
    fun `locale tags resolve to the expected locales`() {
        assertEquals(Locale.ENGLISH, AppLanguage.ENGLISH.toLocale())
        // BCP-47 "id" maps to Java's historic "in" for Indonesian.
        assertEquals("in", AppLanguage.INDONESIAN.toLocale().language)
    }

    @Test
    fun `storage keys are distinct`() {
        val keys = AppLanguage.entries.map { it.storageKey }
        assertEquals(keys.size, keys.toSet().size)
    }
}
