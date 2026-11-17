package com.clinref.app.domain.ai.providers

import ai.koog.http.client.KoogHttpClient
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.AiProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelDeduplicationTest {

    private val httpClientFactory: KoogHttpClient.Factory = mockk(relaxed = true)

    @Test
    fun googleProviderDeduplicatesModelsEvenAfterRepeatedResolutions() {
        val provider = GoogleProvider(httpClientFactory)

        // Resolve predefined models repeatedly
        provider.resolveModel(AiConfiguration(provider = AiProvider.GOOGLE, model = "gemini-2.5-flash"))
        provider.resolveModel(AiConfiguration(provider = AiProvider.GOOGLE, model = "gemini-2.5-flash"))
        provider.resolveModel(AiConfiguration(provider = AiProvider.GOOGLE, model = "gemini-3.5-flash-lite"))
        provider.resolveModel(AiConfiguration(provider = AiProvider.GOOGLE, model = "gemini-3.5-flash-lite"))

        val available = provider.getAvailableModels()
        assertEquals("Google models must have no duplicates", available.distinct().size, available.size)
    }

    @Test
    fun googleProviderEnablesThinkingForCustomModelsAndGemini4() {
        val provider = GoogleProvider(httpClientFactory)

        val gemini4 = provider.resolveModel(
            AiConfiguration(provider = AiProvider.GOOGLE, model = "gemini-4-hydrogen")
        )
        assertTrue("gemini-4-hydrogen must support Thinking capability", gemini4.supports(ai.koog.prompt.llm.LLMCapability.Thinking))
        assertEquals(1_048_576L, gemini4.contextLength)
        assertEquals(65_536L, gemini4.maxOutputTokens)

        val customTuned = provider.resolveModel(
            AiConfiguration(provider = AiProvider.GOOGLE, model = "tunedModels/custom-clinical-expert-v1")
        )
        assertTrue("Custom Google model must support Thinking capability", customTuned.supports(ai.koog.prompt.llm.LLMCapability.Thinking))
        assertEquals(1_048_576L, customTuned.contextLength)

        val available = provider.getAvailableModels()
        assertTrue("Custom model must never pollute available preset models list", "tunedModels/custom-clinical-expert-v1" !in available)
    }

    @Test
    fun anthropicProviderDeduplicatesModelsEvenAfterRepeatedResolutions() {
        val provider = AnthropicProvider(httpClientFactory)

        provider.resolveModel(AiConfiguration(provider = AiProvider.ANTHROPIC, model = "claude-3-5-sonnet-20241022"))
        provider.resolveModel(AiConfiguration(provider = AiProvider.ANTHROPIC, model = "claude-3-5-sonnet-20241022"))

        val available = provider.getAvailableModels()
        assertEquals("Anthropic models must have no duplicates", available.distinct().size, available.size)
    }

    @Test
    fun openAIProviderDeduplicatesModelsEvenAfterRepeatedResolutions() {
        val provider = OpenAIProvider(httpClientFactory)

        provider.resolveModel(AiConfiguration(provider = AiProvider.OPENAI, model = "gpt-4o"))
        provider.resolveModel(AiConfiguration(provider = AiProvider.OPENAI, model = "gpt-4o"))

        val available = provider.getAvailableModels()
        assertEquals("OpenAI models must have no duplicates", available.distinct().size, available.size)
    }

    @Test
    fun openRouterProviderDeduplicatesModels() {
        val provider = OpenRouterProvider(httpClientFactory)

        provider.resolveModel(AiConfiguration(provider = AiProvider.OPENROUTER, model = "meta-llama/llama-3.3-70b-instruct:free"))
        provider.resolveModel(AiConfiguration(provider = AiProvider.OPENROUTER, model = "meta-llama/llama-3.3-70b-instruct:free"))

        val available = provider.getAvailableModels()
        assertEquals("OpenRouter models must have no duplicates", available.distinct().size, available.size)
    }
}
