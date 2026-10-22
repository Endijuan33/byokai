package com.byokai.domain.provider

import com.byokai.domain.model.ProviderId

/**
 * One entry of the bundled provider catalog.
 *
 * The catalog is shipped inside the app (no network fetch): it works
 * offline and a provider list can never be tampered with in transit.
 * Selecting an entry only pre-fills the add-provider form — every field
 * stays editable before saving.
 *
 * @property id stable unique identifier (lowercase, no spaces).
 * @property displayName human-readable provider name.
 * @property providerId which adapter family serves this provider.
 * @property defaultBaseUrl base URL prefilled into the form.
 * @property suggestedModels model ids prefilled into the form. These are
 *   SUGGESTIONS, not a verified list: model ids change over time and the
 *   user can (and should) edit them. Endpoints that implement model
 *   listing can refresh the real list after saving.
 * @property website provider homepage, shown for reference.
 */
data class CatalogEntry(
    val id: String,
    val displayName: String,
    val providerId: ProviderId,
    val defaultBaseUrl: String,
    val suggestedModels: List<String>,
    val website: String,
)

/**
 * Curated catalog of well-known AI providers.
 *
 * Inclusion rule: an entry is listed only when its base URL and protocol
 * are known with confidence. Suggested models are best-effort hints from
 * public documentation — they are labeled "suggested" in the UI and docs
 * and are never presented as verified.
 *
 * Pure Kotlin, no Android dependencies, so the catalog (and its search)
 * is unit-testable on the JVM.
 */
object ProviderCatalog {

    /**
     * All catalog entries in display order: first-party providers first,
     * then third-party OpenAI-compatible providers, then local servers.
     */
    val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "openai",
            displayName = "OpenAI",
            providerId = ProviderId.OPENAI,
            defaultBaseUrl = "https://api.openai.com",
            suggestedModels = listOf("gpt-4o", "gpt-4o-mini"),
            website = "https://openai.com",
        ),
        CatalogEntry(
            id = "anthropic",
            displayName = "Anthropic",
            providerId = ProviderId.ANTHROPIC,
            defaultBaseUrl = "https://api.anthropic.com",
            suggestedModels = listOf("claude-sonnet-4-6", "claude-opus-4-6"),
            website = "https://anthropic.com",
        ),
        CatalogEntry(
            id = "gemini",
            displayName = "Google Gemini",
            providerId = ProviderId.GEMINI,
            defaultBaseUrl = "https://generativelanguage.googleapis.com",
            suggestedModels = listOf("gemini-3.6-flash"),
            website = "https://ai.google.dev",
        ),
        CatalogEntry(
            id = "deepseek",
            displayName = "DeepSeek",
            providerId = ProviderId.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://api.deepseek.com",
            suggestedModels = listOf("deepseek-chat", "deepseek-reasoner"),
            website = "https://deepseek.com",
        ),
        CatalogEntry(
            id = "xai",
            displayName = "xAI",
            providerId = ProviderId.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://api.x.ai",
            suggestedModels = listOf("grok-4-1-fast-non-reasoning", "grok-3-mini"),
            website = "https://x.ai",
        ),
        CatalogEntry(
            id = "openrouter",
            displayName = "OpenRouter",
            providerId = ProviderId.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://openrouter.ai/api/v1",
            suggestedModels = listOf("openai/gpt-4o", "anthropic/claude-sonnet-4-6"),
            website = "https://openrouter.ai",
        ),
        CatalogEntry(
            id = "groq",
            displayName = "Groq",
            providerId = ProviderId.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://api.groq.com/openai/v1",
            suggestedModels = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
            website = "https://groq.com",
        ),
        CatalogEntry(
            id = "together",
            displayName = "Together AI",
            providerId = ProviderId.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://api.together.xyz/v1",
            suggestedModels = listOf(
                "meta-llama/Llama-3.3-70B-Instruct-Turbo",
                "deepseek-ai/DeepSeek-V3",
            ),
            website = "https://together.ai",
        ),
        CatalogEntry(
            id = "fireworks",
            displayName = "Fireworks AI",
            providerId = ProviderId.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://api.fireworks.ai/inference/v1",
            suggestedModels = listOf("accounts/fireworks/models/llama-v3p3-70b-instruct"),
            website = "https://fireworks.ai",
        ),
        CatalogEntry(
            id = "mistral",
            displayName = "Mistral AI",
            providerId = ProviderId.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://api.mistral.ai/v1",
            suggestedModels = listOf("mistral-large-latest", "mistral-small-latest"),
            website = "https://mistral.ai",
        ),
        CatalogEntry(
            id = "perplexity",
            displayName = "Perplexity",
            providerId = ProviderId.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://api.perplexity.ai",
            suggestedModels = listOf("sonar", "sonar-pro"),
            website = "https://perplexity.ai",
        ),
        CatalogEntry(
            id = "ollama",
            displayName = "Ollama",
            providerId = ProviderId.OPENAI_COMPATIBLE,
            defaultBaseUrl = "http://localhost:11434",
            suggestedModels = listOf("llama3.3", "qwen3"),
            website = "https://ollama.com",
        ),
        CatalogEntry(
            id = "lmstudio",
            displayName = "LM Studio",
            providerId = ProviderId.OPENAI_COMPATIBLE,
            defaultBaseUrl = "http://localhost:1234/v1",
            suggestedModels = emptyList(),
            website = "https://lmstudio.ai",
        ),
    )

    /**
     * Filters [entries] by display name or id, case-insensitive.
     * A blank query returns the full catalog.
     */
    fun search(query: String): List<CatalogEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries
        return entries.filter {
            it.displayName.lowercase().contains(q) || it.id.contains(q)
        }
    }
}
