package com.lostf1sh.pixelplayeross.data.ai

import com.lostf1sh.pixelplayeross.data.ai.provider.AiProvider
import com.lostf1sh.pixelplayeross.data.database.AiCacheDao
import com.lostf1sh.pixelplayeross.data.database.AiCacheEntity
import com.lostf1sh.pixelplayeross.data.database.AiUsageDao
import com.lostf1sh.pixelplayeross.data.database.AiUsageEntity
import com.lostf1sh.pixelplayeross.data.preferences.AiPreferencesRepository
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Single entry point for AI requests: resolves the active provider, applies the response cache,
 * records token usage, and normalises failures.
 */
@Singleton
class AiHandler
@Inject
constructor(
    private val client: OpenAiCompatibleClient,
    private val preferences: AiPreferencesRepository,
    private val cacheDao: AiCacheDao,
    private val usageDao: AiUsageDao,
    private val requestLogStore: AiRequestLogStore
) {

    /** Model ids offered by the active provider, or null when the call failed. */
    suspend fun fetchModels(): List<String> {
        val (provider, apiKey, baseUrl) = credentials()
        return client.listModels(baseUrl, apiKey)
    }

    /** Sends a minimal request to verify key / url / model are usable. */
    suspend fun testConnection() {
        val (provider, apiKey, baseUrl) = credentials()
        val model = preferences.getModel(provider).first()
        client.chat(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                systemPrompt = "You are a connectivity probe.",
                userPrompt = "Reply with the single word: OK",
                thinkingEnabled = null
        )
    }

    /**
     * Runs [request] against the active provider and returns the raw model response.
     *
     * Results are cached per (model, prompt) so repeating a request costs nothing.
     */
    suspend fun generate(request: String, librarySample: String): String {
        val (provider, apiKey, baseUrl) = credentials()
        val model = preferences.getModel(provider).first()
        val thinking =
                if (provider.supportsThinkingParam) preferences.getThinkingEnabled(provider).first()
                else null

        val userPrompt = AiSystemPromptEngine.userPrompt(request, librarySample)
        val hash = sha256("$model|$userPrompt")

        val cached = cacheDao.getCache(hash)
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MILLIS) {
            requestLogStore.write(
                AiRequestLog(
                    timestamp = System.currentTimeMillis(),
                    status = AiRequestLog.STATUS_FROM_CACHE,
                    promptType = PROMPT_TYPE_PLAYLIST,
                    provider = provider.displayName,
                    model = model,
                    endpoint = AiRequestLogStore.redactUrl("${baseUrl.trimEnd('/')}/chat/completions"),
                    systemPrompt = AiSystemPromptEngine.systemPrompt(),
                    userPrompt = userPrompt,
                    responseText = cached.responseJson
                )
            )
            return cached.responseJson
        }

        val requestStart = System.currentTimeMillis()
        val result =
            try {
                client.chat(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        systemPrompt = AiSystemPromptEngine.systemPrompt(),
                        userPrompt = userPrompt,
                        thinkingEnabled = thinking
                )
            } catch (t: Throwable) {
                requestLogStore.write(
                    AiRequestLog(
                        timestamp = System.currentTimeMillis(),
                        status = AiRequestLog.STATUS_FAILED,
                        promptType = PROMPT_TYPE_PLAYLIST,
                        provider = provider.displayName,
                        model = model,
                        endpoint = AiRequestLogStore.redactUrl("${baseUrl.trimEnd('/')}/chat/completions"),
                        durationMs = System.currentTimeMillis() - requestStart,
                        systemPrompt = AiSystemPromptEngine.systemPrompt(),
                        userPrompt = userPrompt,
                        errorMessage = t.message ?: t.javaClass.simpleName
                    )
                )
                throw t
            }

        val now = System.currentTimeMillis()

        // The library sample is reshuffled per request, so prompts rarely repeat and stale rows
        // would otherwise pile up forever.
        cacheDao.clearOldCache(now - CACHE_TTL_MILLIS)
        cacheDao.insert(
                AiCacheEntity(
                        promptHash = hash,
                        responseJson = result.content,
                        timestamp = now
                )
        )
        usageDao.insertUsage(
                AiUsageEntity(
                        timestamp = now,
                        provider = provider.name,
                        model = model,
                        promptType = PROMPT_TYPE_PLAYLIST,
                        promptTokens = result.promptTokens,
                        outputTokens = result.outputTokens,
                        thoughtTokens = result.thoughtTokens
                )
        )
        requestLogStore.write(
                AiRequestLog(
                        timestamp = now,
                        status = AiRequestLog.STATUS_SUCCESS,
                        promptType = PROMPT_TYPE_PLAYLIST,
                        provider = provider.displayName,
                        model = model,
                        endpoint = AiRequestLogStore.redactUrl("${baseUrl.trimEnd('/')}/chat/completions"),
                        durationMs = now - requestStart,
                        systemPrompt = AiSystemPromptEngine.systemPrompt(),
                        userPrompt = userPrompt,
                        responseText = result.content,
                        promptTokens = result.promptTokens,
                        outputTokens = result.outputTokens,
                        thoughtTokens = result.thoughtTokens
                )
        )

        return result.content
    }

    private suspend fun credentials(): Triple<AiProvider, String, String> {
        val provider = preferences.getProvider()
        val apiKey = preferences.getApiKey(provider).first()
        val baseUrl =
                preferences.getBaseUrl(provider).first().ifBlank {
                    provider.resolveBaseUrl(apiKey)
                }
        return Triple(provider, apiKey, baseUrl)
    }

    private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                    .digest(value.toByteArray())
                    .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PROMPT_TYPE_PLAYLIST = "playlist"
        const val CACHE_TTL_MILLIS = AiPreferencesRepository.CACHE_TTL_MILLIS
    }
}
