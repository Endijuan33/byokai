package com.byokai.domain.provider

import com.byokai.domain.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the v0.8.1 base-URL convention (OpenAI SDK style): the base
 * URL carries the version prefix exactly as the provider documents it,
 * and adapters append only the endpoint path (`/chat/completions`,
 * `/models`, `/messages`). A doubled `/v1` (e.g. Groq's
 * `https://api.groq.com/openai/v1` + `/v1/chat/completions`) 404s on
 * real providers — this test fails the build if any catalog entry
 * would produce one.
 *
 * Mirrors the adapter URL construction
 * (`baseUrl.trimEnd('/') + path`) per adapter family; it does not
 * perform network calls.
 */
class BaseUrlConventionTest {

    private fun chatPath(entry: CatalogEntry): String = when (entry.providerId) {
        ProviderId.ANTHROPIC, ProviderId.ANTHROPIC_COMPATIBLE -> "/messages"
        ProviderId.GEMINI -> "/v1beta/models" // untouched by the convention; informational
        else -> "/chat/completions"
    }

    private fun resolvedChatUrl(entry: CatalogEntry): String =
        entry.defaultBaseUrl.trimEnd('/') + chatPath(entry)

    @Test
    fun `no catalog entry produces a doubled version prefix`() {
        for (entry in ProviderCatalog.entries) {
            if (entry.providerId == ProviderId.GEMINI) continue
            val url = resolvedChatUrl(entry)
            assertFalse(
                "${entry.id}: doubled version prefix in $url",
                url.contains("/v1/v1") || url.contains("/v1/openai/v1"),
            )
        }
    }

    @Test
    fun `groq resolves to its documented endpoint path`() {
        val groq = ProviderCatalog.entries.first { it.id == "groq" }
        assertEquals(
            "https://api.groq.com/openai/v1/chat/completions",
            resolvedChatUrl(groq),
        )
    }

    @Test
    fun `perplexity has no version prefix at all`() {
        val perplexity = ProviderCatalog.entries.first { it.id == "perplexity" }
        val url = resolvedChatUrl(perplexity)
        assertEquals("https://api.perplexity.ai/chat/completions", url)
        assertFalse(url.contains("/v1"))
    }

    @Test
    fun `first-party openai and anthropic defaults include v1`() {
        val openai = ProviderCatalog.entries.first { it.id == "openai" }
        val anthropic = ProviderCatalog.entries.first { it.id == "anthropic" }
        assertEquals("https://api.openai.com/v1/chat/completions", resolvedChatUrl(openai))
        assertEquals("https://api.anthropic.com/v1/messages", resolvedChatUrl(anthropic))
    }

    @Test
    fun `local servers keep their documented prefixes`() {
        val ollama = ProviderCatalog.entries.first { it.id == "ollama" }
        val lmstudio = ProviderCatalog.entries.first { it.id == "lmstudio" }
        assertEquals("http://localhost:11434/v1/chat/completions", resolvedChatUrl(ollama))
        assertEquals("http://localhost:1234/v1/chat/completions", resolvedChatUrl(lmstudio))
    }

    @Test
    fun `groq suggests production models, not enterprise-only ones`() {
        val groq = ProviderCatalog.entries.first { it.id == "groq" }
        assertTrue(groq.suggestedModels.contains("openai/gpt-oss-20b"))
        assertTrue(groq.suggestedModels.contains("openai/gpt-oss-120b"))
        assertFalse(
            "llama-3.3-70b-versatile is Enterprise-only per Groq docs",
            groq.suggestedModels.contains("llama-3.3-70b-versatile"),
        )
        assertFalse(
            "llama-3.1-8b-instant is Enterprise-only per Groq docs",
            groq.suggestedModels.contains("llama-3.1-8b-instant"),
        )
    }
}
