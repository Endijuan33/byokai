package com.byokai.domain.model

import java.util.Locale

/**
 * Pure domain enum for the app language setting.
 *
 * [localeTag] is the canonical BCP-47 tag for the language ("en", "id").
 * [toLocale] builds the locale with [Locale.forLanguageTag], so
 * [Locale.toLanguageTag] is stable ("id"/"en") on every runtime — JVM,
 * Android 15+, and older Android alike.
 *
 * Note on Indonesian: the legacy ISO code "in" is a known quirk. The
 * [Locale] constructor and [Locale.forLanguageTag] normalize it to the
 * modern code, so `getLanguage()` returns "id" on the JVM and on
 * Android 15+ (older Android may still report "in" — see the "Legacy
 * language codes" section of the Android [Locale] reference). That
 * difference does not affect resource loading: the Android framework
 * resolves the documented `values-in` resource qualifier for the
 * Indonesian language on all supported API levels. Because a JVM unit
 * test cannot observe Android resource resolution, [AppLanguageTest]
 * asserts the stable BCP-47 tag instead; on-device locale verification
 * is tracked in the Phase 5 device matrix (docs/ROADMAP.md).
 */
enum class AppLanguage(val storageKey: String, val localeTag: String) {
    ENGLISH("en", "en"),
    INDONESIAN("id", "id");

    fun toLocale(): Locale = Locale.forLanguageTag(localeTag)

    companion object {
        fun fromStorageKey(key: String?): AppLanguage =
            entries.firstOrNull { it.storageKey == key } ?: ENGLISH
    }
}
