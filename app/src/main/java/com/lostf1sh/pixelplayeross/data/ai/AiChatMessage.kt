/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: shared message model for OpenAI-compatible chat requests.
 */
package com.lostf1sh.pixelplayeross.data.ai

/** One message in an OpenAI-compatible chat completion request. */
data class AiChatMessage(
    val role: Role,
    val content: String
) {
    enum class Role(val apiValue: String) {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant")
    }
}
