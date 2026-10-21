package com.byokai.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.byokai.core.common.Result
import com.byokai.core.network.ProviderJson
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.repository.ProviderConfigRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

/**
 * [ProviderConfigRepository] backed by a Preferences DataStore.
 *
 * The whole config list is stored as one JSON string under
 * [KEY_CONFIGS_JSON], encoded with the shared [ProviderJson] instance
 * (unknown keys ignored so a newer app version's fields never break an
 * older payload).
 *
 * Degradation policy: an unreadable preferences file ([IOException]) and
 * an undecodable JSON payload both read back as an empty list — a
 * corrupt file never crashes the app, and the next successful [save]
 * overwrites the damaged payload.
 */
class DataStoreProviderConfigRepository(
    private val dataStore: DataStore<Preferences>,
) : ProviderConfigRepository {

    override fun configs(): Flow<List<ProviderConfig>> = dataStore.data
        .recoverFromIoFailure()
        .map { prefs -> decode(prefs[KEY_CONFIGS_JSON]) }

    override suspend fun get(configId: String): ProviderConfig? =
        configs().first().find { it.id == configId }

    override suspend fun save(config: ProviderConfig): Result<Unit> {
        val validation = config.validate()
        if (validation is Result.Error) return validation
        return try {
            dataStore.edit { prefs ->
                val updated = decode(prefs[KEY_CONFIGS_JSON])
                    .filterNot { it.id == config.id } + config
                prefs[KEY_CONFIGS_JSON] = encode(updated)
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun delete(configId: String): Result<Unit> = try {
        dataStore.edit { prefs ->
            val updated = decode(prefs[KEY_CONFIGS_JSON])
                .filterNot { it.id == configId }
            prefs[KEY_CONFIGS_JSON] = encode(updated)
        }
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Error(e)
    }

    private fun decode(json: String?): List<ProviderConfig> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            ProviderJson.decodeFromString(CONFIGS_SERIALIZER, json)
        } catch (e: Exception) {
            // Corrupt payload: degrade to empty rather than crash.
            emptyList()
        }
    }

    private fun encode(configs: List<ProviderConfig>): String =
        ProviderJson.encodeToString(CONFIGS_SERIALIZER, configs)

    private fun Flow<Preferences>.recoverFromIoFailure(): Flow<Preferences> =
        catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }

    companion object {
        private val CONFIGS_SERIALIZER = ListSerializer(ProviderConfig.serializer())

        internal val KEY_CONFIGS_JSON = stringPreferencesKey("configs_json")
    }
}
