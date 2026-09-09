package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lostf1sh.pixelplayeross.data.ai.AiLibrarySampleMode
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

        /** How many song titles are sent to the model as context for a generation request. */
        const val DEFAULT_LIBRARY_SAMPLE_SIZE = 300

        private const val TYPE_STRING = "string"
        private const val TYPE_INT = "int"
        private const val TYPE_BOOLEAN = "boolean"

        /** Choices offered for [DEFAULT_LIBRARY_SAMPLE_SIZE]; larger means better recall, more tokens. */
        val LIBRARY_SAMPLE_SIZE_OPTIONS: List<Int> = listOf(50, 100, 200, 300, 500)

        /**
         * All keys owned by the AI module, used by backup/export so AI settings can be included
         * or cleared without touching unrelated user preferences.
         */
        fun allAiPreferenceKeyNames(): Set<String> =
            setOf(
                Keys.AI_PROVIDER.name,
                Keys.LIBRARY_SAMPLE_SIZE.name,
                Keys.LIBRARY_SAMPLE_MODE.name
            ) +
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

        val LIBRARY_SAMPLE_SIZE = intPreferencesKey("ai_library_sample_size")

        val LIBRARY_SAMPLE_MODE = stringPreferencesKey("ai_library_sample_mode")

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

    /**
     * The stored url, or an empty string when the user never set one.
     *
     * Deliberately not [AiProvider.defaultBaseUrl]: callers would then be unable to tell "user
     * chose this" from "nobody chose anything", which is exactly what lets
     * [AiProvider.resolveBaseUrl] pick MiMo's endpoint from the key shape. Resolve the effective
     * endpoint through that function instead of reading a default here.
     */
    fun getBaseUrl(provider: AiProvider): Flow<String> =
            dataStore.data.map { preferences -> preferences[Keys.getBaseUrl(provider)] ?: "" }

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

    /**
     * How many song titles are handed to the model when generating a playlist.
     *
     * Global rather than per provider: it is a cost/coverage trade-off, not a provider feature.
     */
    fun getLibrarySampleSize(): Flow<Int> =
            dataStore.data.map { preferences ->
                preferences[Keys.LIBRARY_SAMPLE_SIZE] ?: DEFAULT_LIBRARY_SAMPLE_SIZE
            }

    suspend fun setLibrarySampleSize(size: Int) {
        dataStore.edit { preferences -> preferences[Keys.LIBRARY_SAMPLE_SIZE] = size }
    }

    fun getLibrarySampleMode(): Flow<AiLibrarySampleMode> =
            dataStore.data.map { preferences ->
                AiLibrarySampleMode.fromName(preferences[Keys.LIBRARY_SAMPLE_MODE])
            }

    suspend fun setLibrarySampleMode(mode: AiLibrarySampleMode) {
        dataStore.edit { preferences -> preferences[Keys.LIBRARY_SAMPLE_MODE] = mode.name }
    }

    /**
     * Every stored AI preference, as type-tagged entries for backup.
     *
     * API keys are included verbatim: the backup is a file the user exports and re-imports on
     * their own device, and re-entering a key by hand defeats the point of restoring. Backups
     * can be encrypted from the export screen.
     */
    suspend fun exportForBackup(): List<PreferenceBackupEntry> {
        val aiKeys = allAiPreferenceKeyNames()
        return dataStore.data.first().asMap().mapNotNull { (key, value) ->
            if (key.name !in aiKeys) return@mapNotNull null
            when (value) {
                is String -> PreferenceBackupEntry(key.name, TYPE_STRING, stringValue = value)
                is Int -> PreferenceBackupEntry(key.name, TYPE_INT, intValue = value)
                is Boolean -> PreferenceBackupEntry(key.name, TYPE_BOOLEAN, booleanValue = value)
                else -> null
            }
        }
    }

    /**
     * Applies [entries], optionally dropping the current AI keys first.
     *
     * Keys outside the AI namespace are ignored, so a tampered or foreign payload cannot touch
     * unrelated settings. Clearing and writing happen in one edit, so a failure leaves the old
     * configuration intact rather than half-applied.
     */
    suspend fun importFromBackup(entries: List<PreferenceBackupEntry>, clearExisting: Boolean) {
        val aiKeys = allAiPreferenceKeyNames()
        dataStore.edit { preferences ->
            if (clearExisting) {
                preferences.asMap().keys
                        .filter { it.name in aiKeys }
                        .forEach { key ->
                            @Suppress("UNCHECKED_CAST") preferences.remove(key as Preferences.Key<Any>)
                        }
            }
            entries.forEach { entry ->
                if (entry.key !in aiKeys) return@forEach
                when (entry.type) {
                    TYPE_STRING ->
                            preferences[stringPreferencesKey(entry.key)] = entry.stringValue ?: ""
                    TYPE_INT -> preferences[intPreferencesKey(entry.key)] = entry.intValue ?: 0
                    TYPE_BOOLEAN ->
                            preferences[booleanPreferencesKey(entry.key)] =
                                    entry.booleanValue ?: false
                }
            }
        }
    }

    suspend fun clearAll() {
        val aiKeys = allAiPreferenceKeyNames()
        dataStore.edit { preferences ->
            preferences.asMap().keys
                    .filter { it.name in aiKeys }
                    .forEach { key ->
                        @Suppress("UNCHECKED_CAST") preferences.remove(key as Preferences.Key<Any>)
                    }
        }
    }

}
