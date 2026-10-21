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
    fun `toLocale returns BCP47-canonical locales`() {
        assertEquals(Locale.ENGLISH, AppLanguage.ENGLISH.toLocale())
        assertEquals("en", AppLanguage.ENGLISH.toLocale().toLanguageTag())
        // "id" is the canonical BCP-47 tag for Indonesian; toLanguageTag()
        // is stable on every runtime, unlike getLanguage() which reports
        // "id" on the JVM / Android 15+ and "in" on older Android (legacy
        // ISO codes — see the Android Locale reference). The framework
        // resolves the values-in qualifier for this language on all
        // supported API levels; on-device verification is tracked in the
        // Phase 5 device matrix.
        assertEquals("id", AppLanguage.INDONESIAN.toLocale().toLanguageTag())
    }

    @Test
    fun `storage keys are distinct`() {
        val keys = AppLanguage.entries.map { it.storageKey }
        assertEquals(keys.size, keys.toSet().size)
    }
}
