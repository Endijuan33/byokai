package com.byokai.domain.model

import com.byokai.core.common.Result
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ProviderConfig.validate]. These run on the JVM via
 * `testDebugUnitTest` with no device or emulator required.
 */
class ProviderConfigTest {

    private fun goodConfig() = ProviderConfig(
        id = "cfg-1",
        providerId = ProviderId.OPENAI,
        displayName = "My OpenAI",
        baseUrl = "https://api.openai.com",
        modelIds = listOf("gpt-4o", "gpt-4o-mini"),
        defaultModelId = "gpt-4o",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun failureMessage(result: Result<Unit>): String =
        ((result as Result.Error).cause as IllegalArgumentException).message.orEmpty()

    @Test
    fun `validate accepts a good config`() {
        assertTrue(goodConfig().validate() is Result.Success)
    }

    @Test
    fun `validate accepts a null defaultModelId`() {
        assertTrue(goodConfig().copy(defaultModelId = null).validate() is Result.Success)
    }

    @Test
    fun `validate rejects a blank displayName`() {
        val result = goodConfig().copy(displayName = "   ").validate()
        assertTrue(result is Result.Error)
        assertTrue(failureMessage(result).contains("displayName"))
    }

    @Test
    fun `validate rejects a bad baseUrl`() {
        val result = goodConfig().copy(baseUrl = "http://example.com").validate()
        assertTrue(result is Result.Error)
        assertTrue(failureMessage(result).contains("baseUrl"))
    }

    @Test
    fun `validate rejects empty modelIds`() {
        val result = goodConfig().copy(modelIds = emptyList(), defaultModelId = null).validate()
        assertTrue(result is Result.Error)
        assertTrue(failureMessage(result).contains("modelIds"))
    }

    @Test
    fun `validate rejects a defaultModelId not in modelIds`() {
        val result = goodConfig().copy(defaultModelId = "gpt-5").validate()
        assertTrue(result is Result.Error)
        assertTrue(failureMessage(result).contains("defaultModelId"))
    }
}
