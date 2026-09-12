package com.lostf1sh.pixelplayeross.presentation.components

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Prefix used when the prompt is not one of the offered idea chips. */
internal const val AI_MIX_FALLBACK_PREFIX = "AI Mix"

private val MIX_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * Default name for a generated mix: the idea word the user tapped, followed by when it was made.
 *
 * The word only wins when the prompt is exactly an idea chip — as soon as the user types
 * something of their own, the name falls back to the generic prefix instead of presenting a
 * half-quoted description as a playlist title.
 */
internal fun aiMixDefaultName(
    prompt: String,
    ideas: Collection<String>,
    now: LocalDateTime = LocalDateTime.now()
): String {
    val prefix =
            ideas.firstOrNull { it.equals(prompt.trim(), ignoreCase = true) }
                    ?: AI_MIX_FALLBACK_PREFIX
    return "$prefix · ${now.format(MIX_TIMESTAMP_FORMAT)}"
}
