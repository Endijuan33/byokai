package com.byokai.core.ui

/**
 * Decides whether a link found in AI-generated markdown is safe to open.
 *
 * Model output is untrusted input: a link like
 * `javascript:…`, `data:…`, or `file:…` must never reach an
 * [android.content.Intent]. Only absolute `http`/`https` URLs pass.
 * Relative links (`/path`, `#anchor`) are rejected too — there is no
 * base URL to resolve them against, and silently dropping them is
 * safer than guessing.
 *
 * Pure Kotlin with no Android dependencies, so the policy is
 * unit-tested on the JVM ([LinkSafetyTest]).
 */
object LinkSafety {

    /**
     * True only for absolute URLs whose scheme is `http` or `https`
     * (case-insensitive) with a non-empty authority.
     */
    fun isSafeUrl(url: String): Boolean {
        val value = url.trim()
        if (value.isEmpty()) return false

        val schemeEnd = value.indexOf(':')
        // No scheme → relative URL; reject.
        if (schemeEnd <= 0) return false

        val scheme = value.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return false

        // Require the `//authority` form; `http:example.com` and
        // `https:` (empty authority) are rejected.
        val afterScheme = value.substring(schemeEnd + 1)
        if (!afterScheme.startsWith("//")) return false
        if (afterScheme.length <= 2) return false

        return true
    }
}
