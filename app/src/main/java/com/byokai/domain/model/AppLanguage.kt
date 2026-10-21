package com.byokai.domain.model

import java.util.Locale

/**
 * Pure domain enum for the app language setting.
 *
 * [localeTag] is a BCP-47 tag used for persistence and [Locale] creation.
 * Note: Android resource qualifiers use "in" for Indonesian while the
 * language tag stays "id" — `Locale.forLanguageTag("id")` maps to it.
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
