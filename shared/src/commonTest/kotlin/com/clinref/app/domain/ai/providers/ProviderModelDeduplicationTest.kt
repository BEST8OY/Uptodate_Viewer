package com.clinref.app.domain.ai.providers

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.executor.clients.google.GoogleParams
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.AiProvider
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun googleProviderCreatesThinkingParamsForLowMediumAndHigh() {
        val provider = GoogleProvider(httpClientFactory)

        // Low
        val lowConfig = AiConfiguration(
            provider = AiProvider.GOOGLE,
            model = "gemini-2.5-flash",
            providerSettings = com.clinref.app.domain.ai.ProviderSettings.Google(
                includeThoughts = true,
                thinkingLevel = "low"
            )
        )
        val lowParams = provider.createParams(lowConfig) as GoogleParams
        assertEquals(ai.koog.prompt.executor.clients.google.models.GoogleThinkingLevel.LOW, lowParams.thinkingConfig?.thinkingLevel)

        // High
        val highConfig = AiConfiguration(
            provider = AiProvider.GOOGLE,
            model = "gemini-2.5-flash",
            providerSettings = com.clinref.app.domain.ai.ProviderSettings.Google(
                includeThoughts = true,
                thinkingLevel = "high"
            )
        )
        val highParams = provider.createParams(highConfig) as GoogleParams
        assertEquals(ai.koog.prompt.executor.clients.google.models.GoogleThinkingLevel.HIGH, highParams.thinkingConfig?.thinkingLevel)

        // Medium (via additionalProperties since Koog enum omits MEDIUM)
        val medConfig = AiConfiguration(
            provider = AiProvider.GOOGLE,
            model = "gemini-2.5-flash",
            providerSettings = com.clinref.app.domain.ai.ProviderSettings.Google(
                includeThoughts = true,
                thinkingLevel = "medium"
            )
        )
        val medParams = provider.createParams(medConfig) as GoogleParams
        val thinkingJson = medParams.additionalProperties?.get("thinkingConfig") as? JsonObject
        assertNotNull("Medium thinkingConfig must be passed via additionalProperties", thinkingJson)
        assertEquals("MEDIUM", (thinkingJson?.get("thinkingLevel") as? JsonPrimitive)?.content)
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

    @Test
    fun openAIProviderEnforcesNullTemperatureForReasoningModelsEvenWithDefaultSettings() {
        val provider = OpenAIProvider(httpClientFactory)

        // o3-mini without explicit reasoningEffort settings must still null temperature
        val o3Config = AiConfiguration(
            provider = AiProvider.OPENAI,
            model = "o3-mini",
            providerSettings = com.clinref.app.domain.ai.ProviderSettings.OpenAI(
                temperature = 0.7f,
                maxTokens = 8192
            )
        )
        val o3Params = provider.createParams(o3Config)
        assertNull("Temperature must be null for o3-mini reasoning model", o3Params.temperature)
        assertEquals(8192, o3Params.maxTokens)

        // Non-reasoning model (gpt-4o) must retain temperature
        val gpt4oConfig = AiConfiguration(
            provider = AiProvider.OPENAI,
            model = "gpt-4o",
            providerSettings = com.clinref.app.domain.ai.ProviderSettings.OpenAI(
                temperature = 0.7f,
                maxTokens = 4096
            )
        )
        val gpt4oParams = provider.createParams(gpt4oConfig)
        assertEquals(0.7, gpt4oParams.temperature!!, 0.01)
        assertEquals(4096, gpt4oParams.maxTokens)
    }

    @Test
    fun anthropicProviderEnforcesExtendedThinkingConstraints() {
        val provider = AnthropicProvider(httpClientFactory)

        // Thinking enabled with small budget (under 1024) and requested maxTokens <= budget
        val config = AiConfiguration(
            provider = AiProvider.ANTHROPIC,
            model = "claude-3-7-sonnet",
            providerSettings = com.clinref.app.domain.ai.ProviderSettings.Anthropic(
                temperature = 0.5f,
                maxTokens = 2048,
                enableThinking = true,
                thinkingBudget = 500 // Under 1024
            )
        )
        val params = provider.createParams(config) as AnthropicParams
        assertEquals(1.0, params.temperature!!, 0.01)
        assertTrue("Thinking must be enabled", params.thinking is AnthropicThinking.Enabled)
        val enabledThinking = params.thinking as AnthropicThinking.Enabled
        assertEquals("Budget must be coerced to at least 1024", 1024, enabledThinking.budgetTokens)
        assertTrue(
            "maxTokens must exceed budgetTokens",
            params.maxTokens!! > enabledThinking.budgetTokens
        )
        assertEquals(1024 + 4096, params.maxTokens)
    }

    @Test
    fun googleProviderMutuallyExcludesBudgetAndLevelForGeminiStability() {
        val provider = GoogleProvider(httpClientFactory)

        // When thinkingBudget is set, thinkingLevel should be omitted to avoid Gemini 2.5 errors
        val configWithBudget = AiConfiguration(
            provider = AiProvider.GOOGLE,
            model = "gemini-2.5-flash",
            providerSettings = com.clinref.app.domain.ai.ProviderSettings.Google(
                includeThoughts = true,
                thinkingBudget = 4096,
                thinkingLevel = "high"
            )
        )
        val paramsWithBudget = provider.createParams(configWithBudget) as GoogleParams
        assertEquals(4096, paramsWithBudget.thinkingConfig?.thinkingBudget)
        assertNull("thinkingLevel must be omitted when thinkingBudget is present", paramsWithBudget.thinkingConfig?.thinkingLevel)
    }
}
