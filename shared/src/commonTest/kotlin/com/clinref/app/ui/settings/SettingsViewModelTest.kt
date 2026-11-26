package com.clinref.app.ui.settings

import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.AiProvider
import com.clinref.app.domain.ai.DecisionMode
import com.clinref.app.domain.ai.DecisionSettings
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
import kotlinx.coroutines.test.advanceTimeBy
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

    @Test
    fun `Decisions settings update mode autoPrefetch and save separate Google key when using OpenAI`() = runTest {
        val securePreferences = FakeSecurePreferences(
            AiConfiguration(provider = AiProvider.OPENAI, model = "gpt-4o", isConfigured = true)
        )
        securePreferences.saveApiKey(AiProvider.OPENAI, "sk-openai-key")
        val koogAgentFactory = mockk<KoogAgentFactory>(relaxed = true)
        coEvery { koogAgentFactory.getAvailableModels(any(), any()) } returns listOf("gpt-4o")

        val viewModel = SettingsViewModel(
            securePreferences = securePreferences,
            koogAgentFactory = koogAgentFactory,
            reliabilityManager = ReliabilityManager()
        )
        advanceUntilIdle()

        viewModel.updateDecisionMode(DecisionMode.RERANK)
        viewModel.updateDecisionApiKey("AIza-gemini-decisions-key")
        viewModel.save()
        advanceUntilIdle()

        val saved = securePreferences.loadConfiguration()
        assertEquals(DecisionMode.RERANK, saved.decisionSettings.mode)
        assertEquals("sk-openai-key", securePreferences.getApiKey(AiProvider.OPENAI))
        assertEquals("AIza-gemini-decisions-key", securePreferences.getApiKey(AiProvider.GOOGLE))
    }

    @Test
    fun `KoogAgentFactory resolveDecisionClient respects mode and Google API key presence`() {
        val securePreferences = FakeSecurePreferences(
            AiConfiguration(
                provider = AiProvider.ANTHROPIC,
                decisionSettings = DecisionSettings(mode = DecisionMode.RERANK)
            )
        )
        val factory = KoogAgentFactory(securePreferences = securePreferences)

        // Missing Google key -> returns null gracefully (fail-open)
        assertEquals(null, factory.resolveDecisionClient(securePreferences.loadConfiguration()))

        // Mode OFF even with Google key -> returns null
        securePreferences.saveApiKey(AiProvider.GOOGLE, "AIza-test-key")
        assertEquals(
            null,
            factory.resolveDecisionClient(
                AiConfiguration(
                    provider = AiProvider.ANTHROPIC,
                    decisionSettings = DecisionSettings(mode = DecisionMode.OFF)
                )
            )
        )

        // Mode RERANK + Google key -> returns DecisionClient
        val client = factory.resolveDecisionClient(securePreferences.loadConfiguration())
        assertTrue(client != null)
        client?.close()
    }

    @Test
    fun `reload discards uncommitted in-memory changes and restores persisted state`() = runTest {
        val initialConfig = AiConfiguration(
            provider = AiProvider.GOOGLE,
            model = "gemini-2.5-flash",
            isConfigured = true
        )
        val securePreferences = FakeSecurePreferences(initialConfig)
        securePreferences.saveApiKey(AiProvider.GOOGLE, "AIza-persisted-key")
        coEvery { koogAgentFactory.getAvailableModels(any(), any()) } returns listOf("gemini-2.5-flash")

        val viewModel = SettingsViewModel(
            securePreferences = securePreferences,
            koogAgentFactory = koogAgentFactory,
            reliabilityManager = reliabilityManager
        )
        advanceUntilIdle()

        assertEquals("gemini-2.5-flash", viewModel.configuration.value.model)
        assertEquals("AIza-persisted-key", viewModel.apiKey.value)

        // User makes uncommitted edits
        viewModel.updateModel("gemini-custom-uncommitted")
        viewModel.updateApiKey("AIza-uncommitted-key")
        assertEquals("gemini-custom-uncommitted", viewModel.configuration.value.model)
        assertEquals("AIza-uncommitted-key", viewModel.apiKey.value)

        // User navigates away and re-enters settings screen without saving -> reload() is triggered
        viewModel.reload()
        advanceUntilIdle()

        // Uncommitted edits are discarded and persisted state is restored
        assertEquals("gemini-2.5-flash", viewModel.configuration.value.model)
        assertEquals("AIza-persisted-key", viewModel.apiKey.value)
    }

    @Test
    fun `rapid provider switch cancels prior model fetching and adopts latest provider models`() = runTest {
        val initialConfig = AiConfiguration(provider = AiProvider.GOOGLE, isConfigured = true)
        val securePreferences = FakeSecurePreferences(initialConfig)

        val googleModels = listOf("gemini-2.5-flash", "gemini-2.5-pro")
        val anthropicModels = listOf("claude-3-5-sonnet-latest", "claude-3-opus")

        coEvery { koogAgentFactory.getAvailableModels(AiProvider.GOOGLE, any()) } coAnswers {
            kotlinx.coroutines.delay(500)
            googleModels
        }
        coEvery { koogAgentFactory.getAvailableModels(AiProvider.ANTHROPIC, any()) } coAnswers {
            kotlinx.coroutines.delay(10)
            anthropicModels
        }

        val viewModel = SettingsViewModel(
            securePreferences = securePreferences,
            koogAgentFactory = koogAgentFactory,
            reliabilityManager = reliabilityManager
        )
        advanceUntilIdle()

        viewModel.updateProvider(AiProvider.GOOGLE)
        advanceTimeBy(50)
        viewModel.updateProvider(AiProvider.ANTHROPIC)
        advanceUntilIdle()

        assertEquals(AiProvider.ANTHROPIC, viewModel.configuration.value.provider)
        assertEquals(anthropicModels, viewModel.availableModels.value)
    }

    @Test
    fun `updateProvider cancels in-flight delayed reload`() = runTest {
        val initialConfig = AiConfiguration(provider = AiProvider.GOOGLE, isConfigured = true)
        val securePreferences = FakeSecurePreferences(initialConfig)
        val googleModels = listOf("gemini-2.5-flash")
        val anthropicModels = listOf("claude-3-5-sonnet")

        coEvery { koogAgentFactory.getAvailableModels(AiProvider.GOOGLE, any()) } coAnswers {
            kotlinx.coroutines.delay(500)
            googleModels
        }
        coEvery { koogAgentFactory.getAvailableModels(AiProvider.ANTHROPIC, any()) } coAnswers {
            kotlinx.coroutines.delay(10)
            anthropicModels
        }

        val viewModel = SettingsViewModel(
            securePreferences = securePreferences,
            koogAgentFactory = koogAgentFactory,
            reliabilityManager = reliabilityManager
        )
        advanceUntilIdle()

        // Trigger reload (which triggers model fetching with 500ms delay)
        viewModel.reload()
        advanceTimeBy(50)

        // Immediately switch provider to Anthropic
        viewModel.updateProvider(AiProvider.ANTHROPIC)
        advanceUntilIdle()

        // Verify Anthropic state is retained and delayed reload did not clobber it
        assertEquals(AiProvider.ANTHROPIC, viewModel.configuration.value.provider)
        assertEquals(anthropicModels, viewModel.availableModels.value)
    }
}
