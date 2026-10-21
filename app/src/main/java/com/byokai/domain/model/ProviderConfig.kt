package com.byokai.domain.model

import com.byokai.core.common.Result
import com.byokai.core.network.EndpointValidation
import com.byokai.core.network.EndpointValidator
import kotlinx.serialization.Serializable

/**
 * User-configured connection to one provider.
 *
 * Holds everything needed to reach a provider endpoint EXCEPT the API
 * key: credentials live in a separate credential store keyed by [id]
 * (Milestone 3B), so a config can be exported, logged, or displayed
 * without ever exposing a secret.
 *
 * @property id stable identifier; also the credential-store key.
 * @property providerId which adapter family serves this config.
 * @property displayName user-visible label (e.g. "Work OpenAI").
 * @property baseUrl provider API base URL.
 * @property modelIds model identifiers this config may send.
 * @property defaultModelId preselected model, or null for none.
 * @property createdAt epoch millis when the config was created.
 * @property updatedAt epoch millis of the last config edit.
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val providerId: ProviderId,
    val displayName: String,
    val baseUrl: String,
    val modelIds: List<String>,
    val defaultModelId: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /**
     * Checks the config for the invariants every adapter relies on:
     * a non-blank [displayName], a [baseUrl] that passes
     * [EndpointValidator], a non-empty [modelIds] list, and a
     * [defaultModelId] (when set) that is one of [modelIds].
     *
     * @return [Result.Success] with [Unit] when all checks pass,
     * [Result.Error] carrying an [IllegalArgumentException] describing
     * the first failed check otherwise.
     */
    fun validate(): Result<Unit> {
        if (displayName.isBlank()) {
            return Result.Error(IllegalArgumentException("displayName must not be blank"))
        }
        when (val endpoint = EndpointValidator.validate(baseUrl)) {
            is EndpointValidation.Invalid ->
                return Result.Error(IllegalArgumentException("baseUrl is invalid: ${endpoint.reason}"))
            is EndpointValidation.Valid -> Unit
        }
        if (modelIds.isEmpty()) {
            return Result.Error(IllegalArgumentException("modelIds must not be empty"))
        }
        if (defaultModelId != null && defaultModelId !in modelIds) {
            return Result.Error(
                IllegalArgumentException("defaultModelId '$defaultModelId' is not in modelIds"),
            )
        }
        return Result.Success(Unit)
    }
}
