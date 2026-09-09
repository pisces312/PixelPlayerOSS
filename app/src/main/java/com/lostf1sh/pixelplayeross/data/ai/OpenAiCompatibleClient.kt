package com.lostf1sh.pixelplayeross.data.ai

import com.lostf1sh.pixelplayeross.data.ai.provider.AiErrorKind
import com.lostf1sh.pixelplayeross.data.ai.provider.AiProviderException
import com.lostf1sh.pixelplayeross.data.ai.provider.aiErrorKindFor
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Minimal OpenAI-compatible client: chat completions plus model listing.
 *
 * Base URLs are user configurable, so Retrofit's fixed-baseUrl model would need a rebuilt
 * instance per provider — a plain OkHttp call is smaller and keeps every provider on one code
 * path.
 */
@Singleton
class OpenAiCompatibleClient
@Inject
constructor(private val baseClient: OkHttpClient) {

    data class ChatResult(
        val content: String,
        val promptTokens: Int,
        val outputTokens: Int,
        val thoughtTokens: Int
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        baseClient
                .newBuilder()
                .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
    }

    /** `GET {baseUrl}/models` -> model ids, sorted alphabetically. */
    suspend fun listModels(baseUrl: String, apiKey: String): List<String> =
            withContext(Dispatchers.IO) {
                val request =
                        Request.Builder()
                                .url(endpoint(baseUrl, "models"))
                                .applyAuth(apiKey)
                                .get()
                                .build()
                val payload = execute(request)
                val data = payload["data"] as? JsonArray
                data?.mapNotNull { (it as? JsonObject)?.get("id")?.primitiveContent() }?.sorted()
                        ?: emptyList()
            }

    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        thinkingEnabled: Boolean?
    ): ChatResult =
            withContext(Dispatchers.IO) {
                val body =
                        buildJsonObject {
                                    put("model", model)
                                    put("stream", false)
                                    put(
                                            "messages",
                                            buildJsonArray {
                                                add(
                                                        buildJsonObject {
                                                            put("role", "system")
                                                            put("content", systemPrompt)
                                                        }
                                                )
                                                add(
                                                        buildJsonObject {
                                                            put("role", "user")
                                                            put("content", userPrompt)
                                                        }
                                                )
                                            }
                                    )
                                    if (thinkingEnabled != null) {
                                        put(
                                                "thinking",
                                                buildJsonObject {
                                                    put(
                                                            "type",
                                                            if (thinkingEnabled) "enabled"
                                                            else "disabled"
                                                    )
                                                }
                                        )
                                    }
                                }
                                .toString()

                val request =
                        Request.Builder()
                                .url(endpoint(baseUrl, "chat/completions"))
                                .applyAuth(apiKey)
                                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                                .build()

                val payload = execute(request)
                val content =
                        payload["choices"]
                                ?.jsonArrayFirst()
                                ?.let { (it as? JsonObject)?.get("message") as? JsonObject }
                                ?.get("content")
                                ?.primitiveContent()
                                ?.trim()
                if (content.isNullOrEmpty()) {
                    throw AiProviderException(
                            kind = AiErrorKind.RESPONSE_PARSE,
                            statusCode = null,
                            message = "Empty response from model"
                    )
                }

                val usage = payload["usage"] as? JsonObject
                ChatResult(
                        content = content,
                        promptTokens = usage.intField("prompt_tokens"),
                        outputTokens = usage.intField("completion_tokens"),
                        thoughtTokens =
                                usage.nestedIntField(
                                        "completion_tokens_details",
                                        "reasoning_tokens"
                                ) ?: usage.intField("reasoning_tokens")
                )
            }

    private fun Request.Builder.applyAuth(apiKey: String): Request.Builder = apply {
        if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey")
    }

    private fun endpoint(baseUrl: String, path: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("http")) { "Base URL must start with http or https" }
        return "$normalized/$path"
    }

    /** Runs [request] and returns the decoded JSON body, mapping failures to [AiProviderException]. */
    private fun execute(request: Request): JsonObject {
        val response =
                try {
                    client.newCall(request).execute()
                } catch (e: IOException) {
                    throw AiProviderException(
                            kind = AiErrorKind.NETWORK,
                            statusCode = null,
                            message = e.message ?: "Network failure",
                            cause = e
                    )
                }

        response.use {
            val raw = it.body.string()
            if (!it.isSuccessful) {
                throw AiProviderException(
                        kind = aiErrorKindFor(it.code),
                        statusCode = it.code,
                        message = errorMessage(raw) ?: "HTTP ${it.code}"
                )
            }
            return try {
                json.parseToJsonElement(raw) as? JsonObject
                        ?: throw IllegalArgumentException("Response is not a JSON object")
            } catch (e: Exception) {
                throw AiProviderException(
                        kind = AiErrorKind.RESPONSE_PARSE,
                        statusCode = it.code,
                        message = "Malformed response",
                        cause = e
                )
            }
        }
    }

    private fun errorMessage(raw: String): String? =
            try {
                (json.parseToJsonElement(raw) as? JsonObject)
                        ?.get("error")
                        ?.let { it as? JsonObject }
                        ?.get("message")
                        ?.primitiveContent()
            } catch (e: Exception) {
                null
            }

    private fun JsonElement?.primitiveContent(): String? =
            runCatching { this?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonElement?.jsonArrayFirst(): JsonElement? =
            runCatching { (this as? JsonArray)?.firstOrNull() }.getOrNull()

    private fun JsonObject?.intField(name: String): Int =
            runCatching { this?.get(name)?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0

    private fun JsonObject?.nestedIntField(parent: String, name: String): Int? =
            runCatching {
                        ((this?.get(parent) as? JsonObject)?.get(name)?.jsonPrimitive?.intOrNull)
                    }
                    .getOrNull()

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 60L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
