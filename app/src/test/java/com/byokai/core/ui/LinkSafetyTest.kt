package com.byokai.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LinkSafety.isSafeUrl] policy tests. Model output is untrusted, so
 * the allowlist is deliberately narrow: absolute http/https URLs only.
 */
class LinkSafetyTest {

    @Test
    fun `http and https urls are safe`() {
        assertTrue(LinkSafety.isSafeUrl("https://example.com"))
        assertTrue(LinkSafety.isSafeUrl("http://example.com/path?q=1#frag"))
        assertTrue(LinkSafety.isSafeUrl("https://sub.example.com:8080/a/b"))
    }

    @Test
    fun `scheme matching is case-insensitive and trims whitespace`() {
        assertTrue(LinkSafety.isSafeUrl("HTTPS://example.com"))
        assertTrue(LinkSafety.isSafeUrl("  https://example.com  "))
    }

    @Test
    fun `dangerous schemes are rejected`() {
        assertFalse(LinkSafety.isSafeUrl("javascript:alert(1)"))
        assertFalse(LinkSafety.isSafeUrl("JaVaScRiPt:alert(1)"))
        assertFalse(LinkSafety.isSafeUrl("data:text/html,<script>alert(1)</script>"))
        assertFalse(LinkSafety.isSafeUrl("file:///etc/passwd"))
        assertFalse(LinkSafety.isSafeUrl("ftp://example.com/file"))
        assertFalse(LinkSafety.isSafeUrl("intent://example.com#Intent;end"))
        assertFalse(LinkSafety.isSafeUrl("market://details?id=com.example"))
    }

    @Test
    fun `relative urls are rejected`() {
        assertFalse(LinkSafety.isSafeUrl("/relative/path"))
        assertFalse(LinkSafety.isSafeUrl("#anchor"))
        assertFalse(LinkSafety.isSafeUrl("example.com/no-scheme"))
        assertFalse(LinkSafety.isSafeUrl("www.example.com"))
    }

    @Test
    fun `malformed urls are rejected`() {
        assertFalse(LinkSafety.isSafeUrl(""))
        assertFalse(LinkSafety.isSafeUrl("   "))
        assertFalse(LinkSafety.isSafeUrl("https:"))
        assertFalse(LinkSafety.isSafeUrl("https://"))
        assertFalse(LinkSafety.isSafeUrl("http:example.com"))
        assertFalse(LinkSafety.isSafeUrl(":"))
    }
}
