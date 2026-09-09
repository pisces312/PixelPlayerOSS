package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lostf1sh.pixelplayeross.data.ai.provider.AiProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Minimal AI configuration store: which provider to call and how to call it.
 *
 * Deliberately narrow — no sampling parameters (temperature / top_p / ...) are exposed. Each
 * provider keeps its own credentials and model so switching providers never clobbers a working
 * setup.
 */
@Singleton
class AiPreferencesRepository
@Inject
constructor(private val dataStore: DataStore<Preferences>) {
    companion object {
        val DEFAULT_SYSTEM_PROMPT =
            """
            You are 'Vibe-Engine', a professional music curator.
            Analyze the user's request and the supplied library sample to build a playlist.
            Prioritize flow, emotional resonance, and discovery.
            Reply with the song list only, one entry per line, formatted as 'Title - Artist'.
            """
                    .trimIndent()

        /** Cached AI answers are reused for this long before the provider is called again. */
        const val CACHE_TTL_MILLIS = 30 * 60 * 1000L

        /**
         * All keys owned by the AI module, used by backup/export so AI settings can be included
         * or cleared without touching unrelated user preferences.
         */
        fun allAiPreferenceKeyNames(): Set<String> =
            setOf(Keys.AI_PROVIDER.name) +
                    AiProvider.entries.flatMap { provider ->
                        listOfNotNull(
                            Keys.getApiKey(provider).name,
                            Keys.getModel(provider).name,
                            Keys.getThinkingEnabled(provider).name,
                            Keys.getBaseUrl(provider).name.takeIf { provider.hasConfigurableUrl }
                        )
                    }
    }

    private object Keys {
        val AI_PROVIDER = stringPreferencesKey("ai_provider")

        fun getApiKey(provider: AiProvider) = stringPreferencesKey("${provider.keyPrefix}_api_key")

        fun getModel(provider: AiProvider) = stringPreferencesKey("${provider.keyPrefix}_model")

        fun getBaseUrl(provider: AiProvider) =
                stringPreferencesKey("${provider.keyPrefix}_base_url")

        fun getThinkingEnabled(provider: AiProvider) =
                booleanPreferencesKey("${provider.keyPrefix}_thinking_enabled")
    }

    val providerFlow: Flow<AiProvider> =
            dataStore.data.map { preferences ->
                AiProvider.fromName(preferences[Keys.AI_PROVIDER])
            }

    suspend fun getProvider(): AiProvider = providerFlow.first()

    suspend fun setProvider(provider: AiProvider) {
        dataStore.edit { preferences -> preferences[Keys.AI_PROVIDER] = provider.name }
    }

    fun getApiKey(provider: AiProvider): Flow<String> =
            dataStore.data.map { preferences -> preferences[Keys.getApiKey(provider)]?.trim() ?: "" }

    fun getModel(provider: AiProvider): Flow<String> =
            dataStore.data.map { preferences ->
                preferences[Keys.getModel(provider)] ?: provider.defaultModel
            }

    fun getBaseUrl(provider: AiProvider): Flow<String> =
            dataStore.data.map { preferences ->
                preferences[Keys.getBaseUrl(provider)] ?: provider.defaultBaseUrl
            }

    /**
     * Whether the model should emit a chain of thought before answering.
     *
     * Sent as `thinking: {"type": "enabled"|"disabled"}`. Off by default: reasoning tokens cost
     * extra and add latency while a playlist only needs the final list.
     */
    fun getThinkingEnabled(provider: AiProvider): Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[Keys.getThinkingEnabled(provider)] ?: false
            }

    suspend fun setApiKey(provider: AiProvider, apiKey: String) {
        dataStore.edit { preferences -> preferences[Keys.getApiKey(provider)] = apiKey.trim() }
    }

    suspend fun setModel(provider: AiProvider, model: String) {
        dataStore.edit { preferences -> preferences[Keys.getModel(provider)] = model.trim() }
    }

    suspend fun setBaseUrl(provider: AiProvider, baseUrl: String) {
        dataStore.edit { preferences -> preferences[Keys.getBaseUrl(provider)] = baseUrl.trim() }
    }

    suspend fun setThinkingEnabled(provider: AiProvider, enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.getThinkingEnabled(provider)] = enabled }
    }
}
