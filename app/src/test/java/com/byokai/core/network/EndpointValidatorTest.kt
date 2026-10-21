package com.byokai.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [EndpointValidator]. These run on the JVM via
 * `testDebugUnitTest` with no device or emulator required.
 */
class EndpointValidatorTest {

    private fun assertValid(rawUrl: String): List<String> {
        val result = EndpointValidator.validate(rawUrl)
        assertTrue("expected Valid for '$rawUrl' but got $result", result is EndpointValidation.Valid)
        return (result as EndpointValidation.Valid).warnings
    }

    private fun assertInvalid(rawUrl: String): String {
        val result = EndpointValidator.validate(rawUrl)
        assertTrue(
            "expected Invalid for '$rawUrl' but got $result",
            result is EndpointValidation.Invalid,
        )
        return (result as EndpointValidation.Invalid).reason
    }

    @Test
    fun `https url is valid with no warnings`() {
        assertEquals(emptyList<String>(), assertValid("https://api.example.com/v1"))
    }

    @Test
    fun `https api openai com is valid`() {
        assertEquals(emptyList<String>(), assertValid("https://api.openai.com"))
    }

    @Test
    fun `http loopback is valid with a cleartext warning`() {
        assertEquals(
            listOf("cleartext loopback"),
            assertValid("http://127.0.0.1:8080"),
        )
    }

    @Test
    fun `http localhost is valid with a cleartext warning`() {
        assertEquals(
            listOf("cleartext loopback"),
            assertValid("http://localhost:11434"),
        )
    }

    @Test
    fun `http non-loopback is invalid`() {
        val reason = assertInvalid("http://example.com")
        assertTrue(reason.contains("loopback"))
    }

    @Test
    fun `userinfo is rejected`() {
        assertInvalid("https://user@example.com")
    }

    @Test
    fun `metadata ip is blocked over http`() {
        assertInvalid("http://169.254.169.254/")
    }

    @Test
    fun `metadata ip is blocked over https`() {
        assertInvalid("https://169.254.169.254/")
    }

    @Test
    fun `blank url is invalid`() {
        assertInvalid("")
        assertInvalid("   ")
    }

    @Test
    fun `garbage is invalid`() {
        assertInvalid("not a url")
    }

    @Test
    fun `url without host is invalid`() {
        assertInvalid("https://")
    }

    @Test
    fun `ftp scheme is invalid`() {
        assertInvalid("ftp://example.com")
    }
}
