package com.byokai.domain.provider

import com.byokai.core.common.Result
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.chat.ChatRequest
import com.byokai.domain.model.ModelInfo
import com.byokai.domain.model.ProviderCapabilities
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for [ProviderRegistry]. These run on the JVM via
 * `testDebugUnitTest` with no device or emulator required.
 */
class ProviderRegistryTest {

    /** Minimal in-test adapter; NOT a shipped implementation. */
    private fun fakeProvider(id: ProviderId): AiProvider = object : AiProvider {
        override val providerId: ProviderId = id
        override val displayName: String = id.name
        override val defaultBaseUrl: String = "https://example.com"
        override val capabilities: ProviderCapabilities = ProviderCapabilities()

        override suspend fun listModels(
            config: ProviderConfig,
            apiKey: String,
        ): Result<List<ModelInfo>> = Result.Success(emptyList())

        override suspend fun testConnection(
            config: ProviderConfig,
            apiKey: String,
        ): Result<Unit> = Result.Success(Unit)

        override fun streamChat(
            config: ProviderConfig,
            apiKey: String,
            request: ChatRequest,
        ): Flow<ChatEvent> = emptyFlow()
    }

    @Test
    fun `register then get returns the same provider`() {
        val registry = ProviderRegistry()
        val provider = fakeProvider(ProviderId.OPENAI)
        registry.register(provider)
        assertSame(provider, registry.get(ProviderId.OPENAI))
    }

    @Test
    fun `all returns every registered provider in registration order`() {
        val registry = ProviderRegistry()
        registry.register(fakeProvider(ProviderId.ANTHROPIC))
        registry.register(fakeProvider(ProviderId.GEMINI))
        assertEquals(
            listOf(ProviderId.ANTHROPIC, ProviderId.GEMINI),
            registry.all().map { it.providerId },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate registration throws`() {
        val registry = ProviderRegistry()
        registry.register(fakeProvider(ProviderId.OPENAI))
        registry.register(fakeProvider(ProviderId.OPENAI))
    }

    @Test
    fun `unknown id returns null`() {
        val registry = ProviderRegistry()
        registry.register(fakeProvider(ProviderId.OPENAI))
        assertNull(registry.get(ProviderId.GEMINI))
    }

    @Test
    fun `get on empty registry returns null`() {
        assertNull(ProviderRegistry().get(ProviderId.OPENAI))
    }
}
