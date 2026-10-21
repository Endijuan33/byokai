package com.byokai.domain.model

/**
 * Identifies a provider family served by an [com.byokai.domain.provider.AiProvider]
 * adapter.
 *
 * - [OPENAI], [ANTHROPIC], [GEMINI]: first-party APIs with their own
 *   request/response shapes.
 * - [OPENAI_COMPATIBLE]: any custom endpoint that speaks the OpenAI chat
 *   API shape; served by the OpenAI adapter with a user-supplied base URL.
 */
enum class ProviderId {
    /** First-party OpenAI API. */
    OPENAI,

    /** First-party Anthropic API. */
    ANTHROPIC,

    /** First-party Google Gemini API. */
    GEMINI,

    /** Custom endpoint served by the OpenAI adapter. */
    OPENAI_COMPATIBLE,
}
