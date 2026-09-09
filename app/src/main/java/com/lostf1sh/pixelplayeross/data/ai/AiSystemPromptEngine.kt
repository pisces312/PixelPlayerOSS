package com.lostf1sh.pixelplayeross.data.ai

import com.lostf1sh.pixelplayeross.data.preferences.AiPreferencesRepository

/**
 * Builds the prompts sent to the provider.
 *
 * The library sample only ever carries song/artist text — no file paths or other device
 * identifiers leave the phone.
 */
object AiSystemPromptEngine {

    fun systemPrompt(): String = AiPreferencesRepository.DEFAULT_SYSTEM_PROMPT

    fun userPrompt(request: String, librarySample: String): String =
            buildString {
                appendLine("User request: $request")
                appendLine()
                appendLine("Library sample (pick from these when possible):")
                appendLine(librarySample.ifBlank { "(sample unavailable)" })
                appendLine()
                appendLine("Return up to 25 songs, one per line, as 'Title - Artist'.")
            }
}
