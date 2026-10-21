package com.byokai.util

/**
 * Pure helpers for parsing and formatting semantic version names
 * (MAJOR.MINOR.PATCH). Used for version bookkeeping across build scripts
 * and release tooling. Has no Android dependencies so it is unit-testable
 * on the JVM.
 */
object VersionInfo {

    /** A parsed semantic version. */
    data class SemanticVersion(val major: Int, val minor: Int, val patch: Int)

    /**
     * Parses [versionName] into a [SemanticVersion], or returns null when the
     * input is not a well-formed "MAJOR.MINOR.PATCH" triple of non-negative
     * integers (e.g. "0.1.0").
     */
    fun parse(versionName: String): SemanticVersion? {
        val parts = versionName.split(".")
        if (parts.size != 3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null
        return SemanticVersion(numbers[0], numbers[1], numbers[2])
    }

    /** Formats [version] back into "MAJOR.MINOR.PATCH" form. */
    fun format(version: SemanticVersion): String =
        "${version.major}.${version.minor}.${version.patch}"
}
