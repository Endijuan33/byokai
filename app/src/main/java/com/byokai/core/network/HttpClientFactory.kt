package com.byokai.core.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Builds the shared [OkHttpClient] used by all provider adapters.
 *
 * Security posture, by design:
 * - `followRedirects(false)` and `followSslRedirects(false)`: a redirect
 *   target never inherits the `Authorization` header, so an API key can
 *   never leak to a third-party host via an HTTP redirect.
 * - **No logging interceptor, by design.** Authorization headers and
 *   request/response bodies are never logged anywhere in this codebase;
 *   adding a body/header logger here would put API keys in logcat.
 *
 * Timeouts: 15s connect, 60s read (streaming responses stay open a
 * while), 15s write. Pure JVM: OkHttp and `java.util.concurrent` only,
 * no Android APIs.
 */
object HttpClientFactory {

    /**
     * Creates a new [OkHttpClient] with the BYOKAI transport defaults.
     *
     * Callers are expected to keep one instance per adapter (or share one
     * process-wide); the client is thread-safe.
     */
    fun create(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
}
