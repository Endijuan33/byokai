package com.byokai.domain.provider

import com.byokai.core.network.EndpointValidation
import com.byokai.core.network.EndpointValidator
import com.byokai.domain.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integrity and search tests for the bundled [ProviderCatalog].
 *
 * The catalog is the only place provider base URLs are curated, so its
 * invariants (unique ids, valid endpoint URLs, sane search) are locked
 * in here rather than trusted by inspection.
 */
class ProviderCatalogTest {

    @Test
    fun `catalog ids are unique`() {
        val ids = ProviderCatalog.entries.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `every entry has a non-blank display name and website`() {
        for (entry in ProviderCatalog.entries) {
            assertTrue("${entry.id}: displayName blank", entry.displayName.isNotBlank())
            assertTrue("${entry.id}: website blank", entry.website.isNotBlank())
        }
    }

    @Test
    fun `every default base URL passes endpoint validation`() {
        for (entry in ProviderCatalog.entries) {
            val validation = EndpointValidator.validate(entry.defaultBaseUrl)
            assertTrue(
                "${entry.id}: ${entry.defaultBaseUrl} rejected: " +
                    (validation as? EndpointValidation.Invalid)?.reason,
                validation is EndpointValidation.Valid,
            )
        }
    }

    @Test
    fun `loopback entries are the only cleartext http entries`() {
        for (entry in ProviderCatalog.entries) {
            val validation = EndpointValidator.validate(entry.defaultBaseUrl)
            require(validation is EndpointValidation.Valid)
            if (entry.defaultBaseUrl.startsWith("http://")) {
                assertTrue(
                    "${entry.id}: cleartext http without loopback warning",
                    validation.warnings.contains(
                        EndpointValidator.CLEARTEXT_LOOPBACK_WARNING,
                    ),
                )
            }
        }
    }

    @Test
    fun `every entry maps to a known provider id`() {
        val known = setOf(
            ProviderId.OPENAI,
            ProviderId.ANTHROPIC,
            ProviderId.GEMINI,
            ProviderId.OPENAI_COMPATIBLE,
            ProviderId.ANTHROPIC_COMPATIBLE,
        )
        for (entry in ProviderCatalog.entries) {
            assertTrue("${entry.id}: unknown ${entry.providerId}", entry.providerId in known)
        }
    }

    @Test
    fun `catalog is not empty and covers first-party providers`() {
        assertTrue(ProviderCatalog.entries.isNotEmpty())
        val ids = ProviderCatalog.entries.map { it.providerId }.toSet()
        assertTrue(ids.contains(ProviderId.OPENAI))
        assertTrue(ids.contains(ProviderId.ANTHROPIC))
        assertTrue(ids.contains(ProviderId.GEMINI))
        assertTrue(ids.contains(ProviderId.OPENAI_COMPATIBLE))
    }

    @Test
    fun `blank query returns the full catalog`() {
        assertEquals(ProviderCatalog.entries, ProviderCatalog.search(""))
        assertEquals(ProviderCatalog.entries, ProviderCatalog.search("   "))
    }

    @Test
    fun `search matches by display name case-insensitively`() {
        val results = ProviderCatalog.search("deep")
        assertEquals(listOf("deepseek"), results.map { it.id })
    }

    @Test
    fun `search is case-insensitive`() {
        val results = ProviderCatalog.search("GROQ")
        assertEquals(listOf("groq"), results.map { it.id })
    }

    @Test
    fun `search matches by id`() {
        val results = ProviderCatalog.search("lmstudio")
        assertEquals(listOf("lmstudio"), results.map { it.id })
    }

    @Test
    fun `search with no match returns empty`() {
        assertTrue(ProviderCatalog.search("no-such-provider-xyz").isEmpty())
    }

    @Test
    fun `search trims surrounding whitespace`() {
        val results = ProviderCatalog.search("  ollama  ")
        assertEquals(listOf("ollama"), results.map { it.id })
    }
}
