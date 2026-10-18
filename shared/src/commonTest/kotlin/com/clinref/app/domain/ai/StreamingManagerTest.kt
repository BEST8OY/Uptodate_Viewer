package com.clinref.app.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamingManagerTest {

    private lateinit var manager: StreamingManager

    @Before
    fun setUp() {
        manager = StreamingManager()
    }

    @Test
    fun `routes native reasoning deltas to reasoningText StateFlow`() {
        manager.onStreamingReasoningDelta("Reasoning step 1: check allergies.")
        manager.onStreamingReasoningDelta(" Step 2: verify CrCl.")
        manager.onStreamingTextDelta("The recommended antibiotic is Ceftriaxone 1 g IV daily.")

        assertEquals("Reasoning step 1: check allergies. Step 2: verify CrCl.", manager.reasoningText.value)
        assertEquals("The recommended antibiotic is Ceftriaxone 1 g IV daily.", manager.streamingText.value)
    }

    @Test
    fun `demuxes inline thought tags across fragmented stream chunks`() {
        // Simulating realistic token chunks from LLM
        val chunks = listOf(
            "<tho",
            "ught>",
            "Checking contraindications for patient with eGFR < 30.",
            " Dose should be reduced.",
            "</tho",
            "ught>",
            "\n\nAmoxicillin ",
            "250 mg ",
            "every 12 hours."
        )

        for (chunk in chunks) {
            manager.onStreamingTextDelta(chunk)
        }
        manager.onStreamingEnd()

        assertEquals(
            "Checking contraindications for patient with eGFR < 30. Dose should be reduced.",
            manager.reasoningText.value
        )
        assertEquals("Amoxicillin 250 mg every 12 hours.", manager.streamingText.value)
    }

    @Test
    fun `preserves clinical mathematical comparisons in streaming text`() {
        manager.onStreamingTextDelta("Hemoglobin < 7 g/dL or platelet count < 50,000/mcL.")
        manager.onStreamingEnd()

        assertEquals("Hemoglobin < 7 g/dL or platelet count < 50,000/mcL.", manager.streamingText.value)
        assertEquals("", manager.reasoningText.value)
    }

    @Test
    fun `clears both streaming and reasoning buffers on tool call start and reset`() {
        manager.onStreamingReasoningDelta("Intermediate thinking...")
        manager.onStreamingTextDelta("Partial answer...")

        manager.onToolCallStarting("getTopicSectionsText", """{"topicId":"100"}""")
        assertEquals("", manager.streamingText.value)
        assertEquals("", manager.reasoningText.value)

        manager.onStreamingTextDelta("Fresh clinical recommendation.")
        manager.reset()
        assertEquals("", manager.streamingText.value)
        assertEquals("", manager.reasoningText.value)
    }
}
