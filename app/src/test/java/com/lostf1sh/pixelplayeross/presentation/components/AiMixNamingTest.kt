package com.lostf1sh.pixelplayeross.presentation.components

import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiMixNamingTest {

    private val ideas = listOf("Workout", "Deep focus", "Slow Sunday morning")
    private val now = LocalDateTime.of(2026, 9, 12, 12, 17)

    @Test
    fun `name starts with the idea word when the prompt is one`() {
        assertEquals("Workout · 09-12 12:17", aiMixDefaultName("Workout", ideas, now))
    }

    @Test
    fun `idea match ignores case and surrounding whitespace`() {
        assertEquals("Deep focus · 09-12 12:17", aiMixDefaultName("  deep FOCUS ", ideas, now))
    }

    @Test
    fun `a prompt edited past the idea falls back to the generic prefix`() {
        assertEquals("AI Mix · 09-12 12:17", aiMixDefaultName("Workout but slower", ideas, now))
    }

    @Test
    fun `a blank prompt falls back to the generic prefix`() {
        assertEquals("AI Mix · 09-12 12:17", aiMixDefaultName("   ", ideas, now))
    }
}
