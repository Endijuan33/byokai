package com.byokai.ui.common

import com.byokai.R
import com.byokai.core.common.UiText
import com.byokai.domain.provider.ProviderError
import com.byokai.domain.provider.ProviderException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ProviderError.userMessage] and
 * [Throwable.toProviderUiText].
 *
 * These assert resource ids and format args only — no Android
 * [android.content.Context] is needed, so they run on the JVM.
 */
class ErrorTextTest {

    @Test
    fun `Auth maps to the auth message`() {
        assertEquals(
            UiText.Res(R.string.error_provider_auth),
            ProviderError.Auth("bad key").userMessage(),
        )
    }

    @Test
    fun `RateLimited includes retry seconds when present`() {
        assertEquals(
            UiText.Res(R.string.error_provider_rate_limited_retry, listOf(30L)),
            ProviderError.RateLimited("slow", 30L).userMessage(),
        )
    }

    @Test
    fun `RateLimited without retry hint maps to the generic message`() {
        assertEquals(
            UiText.Res(R.string.error_provider_rate_limited),
            ProviderError.RateLimited("slow", null).userMessage(),
        )
    }

    @Test
    fun `Timeout maps to the timeout message`() {
        assertEquals(
            UiText.Res(R.string.error_provider_timeout),
            ProviderError.Timeout.userMessage(),
        )
    }

    @Test
    fun `Network includes the cause message`() {
        assertEquals(
            UiText.Res(R.string.error_provider_network, listOf("connection refused")),
            ProviderError.Network(RuntimeException("connection refused")).userMessage(),
        )
    }

    @Test
    fun `Network falls back to the cause class name`() {
        assertEquals(
            UiText.Res(
                R.string.error_provider_network,
                listOf("RuntimeException"),
            ),
            ProviderError.Network(RuntimeException()).userMessage(),
        )
    }

    @Test
    fun `InvalidModel includes the model id`() {
        assertEquals(
            UiText.Res(R.string.error_provider_invalid_model, listOf("gpt-9")),
            ProviderError.InvalidModel("gpt-9").userMessage(),
        )
    }

    @Test
    fun `InvalidModel with blank id uses the nameless message`() {
        // Regression test for the Groq incident: a 404 with no model id
        // must never render as "Model  is not available with this key."
        assertEquals(
            UiText.Res(R.string.error_provider_invalid_model_unknown),
            ProviderError.InvalidModel("").userMessage(),
        )
        assertEquals(
            UiText.Res(R.string.error_provider_invalid_model_unknown),
            ProviderError.InvalidModel("   ").userMessage(),
        )
    }

    @Test
    fun `Server includes status code and message`() {
        assertEquals(
            UiText.Res(R.string.error_provider_server, listOf(500, "boom")),
            ProviderError.Server(500, "boom").userMessage(),
        )
    }

    @Test
    fun `Parse includes the message`() {
        assertEquals(
            UiText.Res(R.string.error_provider_parse, listOf("bad json")),
            ProviderError.Parse("bad json").userMessage(),
        )
    }

    @Test
    fun `Config includes the message`() {
        assertEquals(
            UiText.Res(R.string.error_provider_config, listOf("no adapter")),
            ProviderError.Config("no adapter").userMessage(),
        )
    }

    @Test
    fun `ProviderException unwraps to the typed message`() {
        assertEquals(
            UiText.Res(R.string.error_provider_auth),
            ProviderException(ProviderError.Auth("bad key")).toProviderUiText(),
        )
    }

    @Test
    fun `unknown throwables map to the generic message`() {
        assertEquals(
            UiText.Res(R.string.error_unknown),
            RuntimeException("unexpected").toProviderUiText(),
        )
    }
}
