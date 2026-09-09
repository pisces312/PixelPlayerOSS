package com.lostf1sh.pixelplayeross.data.ai.provider

/**
 * Providers supported by the AI playlist generator.
 *
 * Every entry speaks the OpenAI-compatible `POST {baseUrl}/chat/completions` contract, so the
 * network layer stays a single implementation.
 */
enum class AiProvider(
    val displayName: String,
    val requiresApiKey: Boolean,
    val hasConfigurableUrl: Boolean,
    val defaultBaseUrl: String,
    val defaultModel: String,
    /** Whether the endpoint understands `thinking: {"type": ...}` on chat requests. */
    val supportsThinkingParam: Boolean
) {
    MIMO(
        displayName = "Xiaomi MiMo",
        requiresApiKey = true,
        hasConfigurableUrl = false,
        defaultBaseUrl = "https://api.xiaomimimo.com/v1",
        defaultModel = "mimo-v2.5",
        supportsThinkingParam = true
    ),
    VOLCANO(
        displayName = "Volcano Engine (Ark)",
        requiresApiKey = true,
        hasConfigurableUrl = false,
        defaultBaseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        defaultModel = "",
        supportsThinkingParam = true
    ),
    CUSTOM(
        displayName = "Custom Provider",
        requiresApiKey = false,
        hasConfigurableUrl = true,
        defaultBaseUrl = "",
        defaultModel = "",
        supportsThinkingParam = false
    );

    /** Lower-cased id used as the DataStore key prefix, e.g. `mimo_model`. */
    val keyPrefix: String
        get() = name.lowercase()

    companion object {
        fun fromName(name: String?): AiProvider = entries.firstOrNull { it.name == name } ?: MIMO
    }
}
