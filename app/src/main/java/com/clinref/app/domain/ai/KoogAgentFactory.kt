package com.clinref.app.domain.ai

import android.content.Context
import android.util.Log
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.dsl.prompt
import com.clinref.app.data.ai.RoomChatHistoryProvider
import com.clinref.app.data.MedicalDatabaseTools
import com.clinref.app.data.secure.SecurePreferences
import com.clinref.app.domain.ai.providers.AiProviderFactory
import com.clinref.app.domain.ai.providers.AnthropicProvider
import com.clinref.app.domain.ai.providers.GoogleProvider
import com.clinref.app.domain.ai.providers.OllamaProvider
import com.clinref.app.domain.ai.providers.MistralAIProvider
import com.clinref.app.domain.ai.providers.OpenAIProvider
import com.clinref.app.domain.ai.providers.OpenRouterProvider
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KoogAgent"

/**
 * Creates Koog AIAgent instances for each conversation turn.
 *
 * AIAgent is single-use — calling .run() twice throws. So we create
 * a fresh agent per sendMessage() call.
 */
@Singleton
class KoogAgentFactory @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val securePreferences: SecurePreferences,
    private val medicalDatabaseTools: MedicalDatabaseTools,
    private val safetyValidator: SafetyValidator,
    private val chatHistoryProvider: RoomChatHistoryProvider
) {

    private val httpClientFactory = OkHttpKoogHttpClient.Factory()

    private val providers: Map<AiProvider, AiProviderFactory> by lazy {
        mapOf(
            AiProvider.GOOGLE to GoogleProvider(httpClientFactory),
            AiProvider.OPENAI to OpenAIProvider(httpClientFactory),
            AiProvider.MISTRAL to MistralAIProvider(httpClientFactory),
            AiProvider.ANTHROPIC to AnthropicProvider(httpClientFactory),
            AiProvider.OPENROUTER to OpenRouterProvider(httpClientFactory),
            AiProvider.OLLAMA to OllamaProvider(httpClientFactory)
        )
    }

    private fun getProvider(provider: AiProvider): AiProviderFactory? = providers[provider]

    suspend fun createAgent(
        config: AiConfiguration,
        conversationId: String,
        patientProfile: PatientProfile,
        streamingManager: StreamingManager
    ): AIAgent<String, String>? {
        val apiKey = securePreferences.getApiKey(config.provider)
        if (apiKey.isBlank() && config.provider != AiProvider.OLLAMA) return null

        val providerFactory = getProvider(config.provider) ?: return null
        val executor = providerFactory.createExecutor(config, apiKey) ?: return null
        val model = providerFactory.resolveModel(config)
        val providerParams = providerFactory.createParams(config)

        val toolRegistry = ToolRegistry {
            tools(medicalDatabaseTools)
        }

        val accumulator = TurnContextAccumulator()

        val agentConfig = AIAgentConfig(
            prompt = prompt(
                id = "chat",
                params = providerParams
            ) {
                system(SystemPrompt.build(patientProfile))
            },
            model = model,
            maxAgentIterations = 25
        )

        return AIAgent(
            promptExecutor = executor,
            agentConfig = agentConfig,
            toolRegistry = toolRegistry
        ) {
            install(ChatMemory) {
                chatHistoryProvider = this@KoogAgentFactory.chatHistoryProvider
                windowSize(50)
                filterMessages { msg -> msg is ai.koog.prompt.message.Message.User || msg is ai.koog.prompt.message.Message.Assistant }
            }

            handleEvents {
                onToolCallStarting { eventContext ->
                    val callId = eventContext.toolCallId ?: ""
                    val argsStr = eventContext.toolArgs.toString()
                    Log.d(TAG, "Tool starting: ${eventContext.toolName} args=$argsStr")
                    accumulator.onToolCallStarting(callId, argsStr)
                    streamingManager.onToolCallStarting(eventContext.toolName, argsStr)
                }

                onLLMStreamingFrameReceived { eventContext ->
                    when (val frame = eventContext.streamFrame) {
                        is ai.koog.prompt.streaming.StreamFrame.TextDelta -> {
                            streamingManager.onStreamingTextDelta(frame.text)
                        }
                        else -> {}
                    }
                }

                onToolCallCompleted { eventContext ->
                    val callId = eventContext.toolCallId ?: ""
                    val resultText = eventContext.toolResult?.toString() ?: ""
                    val success = eventContext.toolResult != null
                    Log.d(TAG, "Tool completed: ${eventContext.toolName} success=$success result=${resultText.take(200)}")
                    accumulator.onToolCallCompleted(callId, eventContext.toolName, resultText, success)
                    streamingManager.onToolCallCompleted(eventContext.toolName)
                    streamingManager.onWaitingForLlm()
                }

                onLLMCallCompleted { eventContext ->
                    val metaInfo = eventContext.response?.metaInfo
                    if (metaInfo != null) {
                        streamingManager.onLlmCallCompleted(
                            promptTokens = metaInfo.inputTokensCount ?: 0,
                            completionTokens = metaInfo.outputTokensCount ?: 0,
                            totalTokens = metaInfo.totalTokensCount ?: 0
                        )
                    }
                }

                onAgentCompleted { eventContext ->
                    val result = eventContext.result?.toString() ?: ""
                    val turnContext = accumulator.buildTurnContext(result)
                    val validation = safetyValidator.validate(turnContext)
                    Log.d(TAG, "Agent completed: tools=${turnContext.toolCalls.map { it.toolName }} validation=${validation.blockedReason ?: "OK"}")
                    streamingManager.onCompleted(result, validation)
                    accumulator.reset()
                }

                onAgentExecutionFailed { eventContext ->
                    Log.e(TAG, "Agent failed: ${eventContext.error.message}")
                    streamingManager.onError(eventContext.error.message ?: "Unknown error")
                    accumulator.reset()
                }
            }
        }
    }

    suspend fun getAvailableModels(provider: AiProvider, baseUrl: String = ""): List<String> {
        return getProvider(provider)?.getAvailableModels() ?: emptyList()
    }
}
