package dev.loki.android.core.ui

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.loki.android.core.models.AgentConfig
import dev.loki.android.core.theme.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * AgentConfigRepository persists and observes [AgentConfig] settings using AndroidX DataStore Preferences.
 *
 * Defaults to a global config in v1, while keeping preference keys scoped so a [modelId] can be passed
 * in the future without breaking existing persisted state.
 */
open class AgentConfigRepository(
    private val context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
) {

    private fun configKey(modelId: String? = null): androidx.datastore.preferences.core.Preferences.Key<String> {
        val keyName = if (modelId.isNullOrBlank()) {
            KEY_GLOBAL_AGENT_CONFIG
        } else {
            "agent_config_$modelId"
        }
        return stringPreferencesKey(keyName)
    }

    /**
     * Observes the [AgentConfig] for the given [modelId] (or global default if null).
     */
    open fun getAgentConfigFlow(modelId: String? = null): Flow<AgentConfig> {
        val key = configKey(modelId)
        return context.dataStore.data.map { preferences ->
            val rawJson = preferences[key]
            if (rawJson.isNullOrBlank()) {
                AgentConfig()
            } else {
                try {
                    json.decodeFromString<AgentConfig>(rawJson)
                } catch (_: Exception) {
                    AgentConfig()
                }
            }
        }
    }

    /**
     * Reads the current [AgentConfig] snapshot.
     */
    open suspend fun getAgentConfig(modelId: String? = null): AgentConfig {
        return getAgentConfigFlow(modelId).first()
    }

    /**
     * Persists the given [AgentConfig].
     */
    open suspend fun saveAgentConfig(config: AgentConfig, modelId: String? = null) {
        val key = configKey(modelId)
        val serialized = json.encodeToString(config)
        context.dataStore.edit { preferences ->
            preferences[key] = serialized
        }
    }

    /**
     * Resets configuration to default.
     */
    open suspend fun resetDefaults(modelId: String? = null) {
        val key = configKey(modelId)
        context.dataStore.edit { preferences ->
            preferences.remove(key)
        }
    }

    companion object {
        const val KEY_GLOBAL_AGENT_CONFIG = "global_agent_config"
    }
}
