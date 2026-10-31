package com.clinref.app.ui.settings

import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.AiProvider
import com.clinref.app.domain.ai.KoogAgentFactory
import com.clinref.app.domain.ai.ReliabilityManager
import com.clinref.app.domain.ai.providers.AnthropicProvider
import com.clinref.app.domain.ai.providers.GoogleProvider
import com.clinref.app.domain.ai.providers.MistralAIProvider
import com.clinref.app.domain.ai.providers.OllamaProvider
import com.clinref.app.domain.ai.providers.OpenAIProvider
import com.clinref.app.domain.ai.providers.OpenRouterProvider
import com.clinref.app.fakes.FakeSecurePreferences
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val koogAgentFactory: KoogAgentFactory = mockk(relaxed = true)
    private val reliabilityManager: ReliabilityManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `OpenRouter preserves user-typed custom model and does not overwrite with dropdown models`() = runTest {
        val initialConfig = AiConfiguration(
            provider = AiProvider.OPENROUTER,
            model = "nvidia/nemotron-3.5-super-vl:free",
            isConfigured = true
        )
        val securePreferences = FakeSecurePreferences(initialConfig)
        coEvery { koogAgentFactory.getAvailableModels(AiProvider.OPENROUTER, any()) } returns listOf(
            "google/gemini-2.0-flash-exp:free",
            "anthropic/claude-3.5-sonnet",
            "openai/gpt-4o"
        )

        val viewModel = SettingsViewModel(
            securePreferences = securePreferences,
            koogAgentFactory = koogAgentFactory,
            reliabilityManager = reliabilityManager
        )
        advanceUntilIdle()

        assertEquals("nvidia/nemotron-3.5-super-vl:free", viewModel.configuration.value.model)

        // User edits the model to another custom slug
        viewModel.updateModel("anthropic/claude-3.7-sonnet")
        assertEquals("anthropic/claude-3.7-sonnet", viewModel.configuration.value.model)

        // Save persists the user-typed custom model
        viewModel.save()
        advanceUntilIdle()

        assertEquals("anthropic/claude-3.7-sonnet", securePreferences.loadConfiguration().model)
    }

    @Test
    fun `custom model is preserved across all providers without being clobbered by available suggestions`() = runTest {
        val initialConfig = AiConfiguration(
            provider = AiProvider.OPENAI,
            model = "o3-mini-custom-slug",
            isConfigured = true
        )
        val securePreferences = FakeSecurePreferences(initialConfig)
        coEvery { koogAgentFactory.getAvailableModels(AiProvider.OPENAI, any()) } returns listOf("gpt-4o", "gpt-4o-mini")

        val viewModel = SettingsViewModel(
            securePreferences = securePreferences,
            koogAgentFactory = koogAgentFactory,
            reliabilityManager = reliabilityManager
        )
        advanceUntilIdle()

        // Does not force-override to gpt-4o
        assertEquals("o3-mini-custom-slug", viewModel.configuration.value.model)
    }

    @Test
    fun `switching to OpenRouter sets default model when blank`() = runTest {
        val securePreferences = FakeSecurePreferences(AiConfiguration(provider = AiProvider.OPENAI, model = ""))
        coEvery { koogAgentFactory.getAvailableModels(any(), any()) } returns emptyList()

        val viewModel = SettingsViewModel(
            securePreferences = securePreferences,
            koogAgentFactory = koogAgentFactory,
            reliabilityManager = reliabilityManager
        )
        advanceUntilIdle()

        viewModel.updateProvider(AiProvider.OPENROUTER)
        advanceUntilIdle()

        assertEquals(OpenRouterProvider.DEFAULT_MODEL, viewModel.configuration.value.model)
        assertEquals(AiProvider.OPENROUTER, viewModel.configuration.value.provider)
    }

    @Test
    fun `OpenRouterProvider resolves custom user-typed model slug correctly`() {
        val provider = OpenRouterProvider(mockk(relaxed = true))
        val customModelId = "nvidia/nemotron-3.5-super-vl:free"
        val config = AiConfiguration(
            provider = AiProvider.OPENROUTER,
            model = "  $customModelId  "
        )

        val resolved = provider.resolveModel(config)
        assertEquals(customModelId, resolved.id)
        assertEquals(ai.koog.prompt.llm.LLMProvider.OpenRouter, resolved.provider)
        assertTrue(resolved.capabilities?.contains(ai.koog.prompt.llm.LLMCapability.Tools) == true)
        assertEquals(128_000L, resolved.contextLength)
    }

    @Test
    fun `Google, OpenAI, Anthropic, Mistral and Ollama providers resolve custom models cleanly`() {
        val google = GoogleProvider(mockk(relaxed = true))
        val openAi = OpenAIProvider(mockk(relaxed = true))
        val anthropic = AnthropicProvider(mockk(relaxed = true))
        val mistral = MistralAIProvider(mockk(relaxed = true))
        val ollama = OllamaProvider(mockk(relaxed = true))

        val googleModel = google.resolveModel(AiConfiguration(provider = AiProvider.GOOGLE, model = "gemini-3.0-pro-custom"))
        assertEquals("gemini-3.0-pro-custom", googleModel.id)
        assertEquals(ai.koog.prompt.llm.LLMProvider.Google, googleModel.provider)

        val openAiModel = openAi.resolveModel(AiConfiguration(provider = AiProvider.OPENAI, model = "gpt-5-preview"))
        assertEquals("gpt-5-preview", openAiModel.id)
        assertEquals(ai.koog.prompt.llm.LLMProvider.OpenAI, openAiModel.provider)

        val anthropicModel = anthropic.resolveModel(AiConfiguration(provider = AiProvider.ANTHROPIC, model = "claude-4-sonnet"))
        assertEquals("claude-4-sonnet", anthropicModel.id)
        assertEquals(ai.koog.prompt.llm.LLMProvider.Anthropic, anthropicModel.provider)

        val mistralModel = mistral.resolveModel(AiConfiguration(provider = AiProvider.MISTRAL, model = "mistral-next-custom"))
        assertEquals("mistral-next-custom", mistralModel.id)
        assertEquals(ai.koog.prompt.llm.LLMProvider.MistralAI, mistralModel.provider)

        val ollamaModel = ollama.resolveModel(AiConfiguration(provider = AiProvider.OLLAMA, model = "deepseek-coder:33b"))
        assertEquals("deepseek-coder:33b", ollamaModel.id)
        assertEquals(ai.koog.prompt.llm.LLMProvider.Ollama, ollamaModel.provider)
    }
}
