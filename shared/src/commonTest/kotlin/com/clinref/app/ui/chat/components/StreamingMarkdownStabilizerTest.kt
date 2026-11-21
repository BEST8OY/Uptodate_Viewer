package com.clinref.app.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingMarkdownStabilizerTest {

    @Test
    fun `inserts closing bold before trailing whitespace`() {
        // Trailing space after word
        assertEquals("**First-line** ", StreamingMarkdownStabilizer.stabilize("**First-line "))

        // Multiple words streaming
        assertEquals("**First-line treatment** ", StreamingMarkdownStabilizer.stabilize("**First-line treatment "))

        // Bold closed normally
        assertEquals("**First-line treatment**", StreamingMarkdownStabilizer.stabilize("**First-line treatment**"))

        // Bold just opened with no text yet
        assertEquals("", StreamingMarkdownStabilizer.stabilize("**"))
    }

    @Test
    fun `preserves clinical bracketed lab values and ranges`() {
        assertEquals("[3.5-5.0 mEq/L", StreamingMarkdownStabilizer.stabilize("[3.5-5.0 mEq/L"))
        assertEquals("Serum potassium [3.5-5.0 mEq/L]", StreamingMarkdownStabilizer.stabilize("Serum potassium [3.5-5.0 mEq/L]"))
    }

    @Test
    fun `closes unclosed code blocks and inline backticks`() {
        assertEquals("```kotlin\nval x = 1\n```", StreamingMarkdownStabilizer.stabilize("```kotlin\nval x = 1"))
        assertEquals("Use `amoxicillin`", StreamingMarkdownStabilizer.stabilize("Use `amoxicillin"))
    }
}
