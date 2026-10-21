package com.byokai.domain.model

/**
 * Pure domain enum for the app theme setting.
 *
 * The [storageKey] is the only coupling to persistence; user-visible labels
 * live in string resources. Unknown or missing keys fall back to [SYSTEM]
 * so a corrupt preference can never crash the UI.
 */
enum class ThemeMode(val storageKey: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorageKey(key: String?): ThemeMode =
            entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}
