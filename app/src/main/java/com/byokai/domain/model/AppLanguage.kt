package com.byokai.domain.model

import java.util.Locale

/**
 * Pure domain enum for the app language setting.
 *
 * [localeTag] is the BCP-47 tag for the language. Note that [toLocale]
 * deliberately does NOT use `Locale.forLanguageTag(localeTag)`: that
 * mapping is runtime-dependent (`forLanguageTag("id").language` is "id"
 * on some JVMs and "in" on Android), while Android resource qualifiers
 * use the legacy code `values-in`. The explicit construction below
 * matches `values-in` deterministically on every runtime.
 */
enum class AppLanguage(val storageKey: String, val localeTag: String) {
    ENGLISH("en", "en"),
    INDONESIAN("id", "id");

    fun toLocale(): Locale = when (this) {
        ENGLISH -> Locale.ENGLISH
        INDONESIAN -> Locale("in")
    }

    companion object {
        fun fromStorageKey(key: String?): AppLanguage =
            entries.firstOrNull { it.storageKey == key } ?: ENGLISH
    }
}
