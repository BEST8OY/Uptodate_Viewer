package com.clinref.app.domain.ai

import com.clinref.shared.platform.PlatformLogger as Log
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.tokenizer.feature.MessageTokenizer
import ai.koog.agents.features.tracing.feature.Tracing
import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.tokenizer.SimpleRegexBasedTokenizer
import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import com.clinref.app.data.ai.RoomChatHistoryProvider
import com.clinref.app.data.MedicalDatabaseTools
import com.clinref.shared.secure.SecurePreferences
import com.clinref.app.domain.ai.providers.AiProviderFactory
import com.clinref.app.domain.ai.providers.AnthropicProvider
import com.clinref.app.domain.ai.providers.GoogleProvider
import com.clinref.app.domain.ai.providers.OllamaProvider
import com.clinref.app.domain.ai.providers.MistralAIProvider
import com.clinref.app.domain.ai.providers.OpenAIProvider
import com.clinref.app.domain.ai.providers.OpenRouterProvider

private const val TAG = "KoogAgent"

class KoogAgentFactory(
    private val securePreferences: SecurePreferences,
    private val medicalDatabaseTools: MedicalDatabaseTools,
    private val safetyValidator: SafetyValidator,
    private val chatHistoryProvider: RoomChatHistoryProvider,
    private val secureLogger: SecureLogger
) {

    private val httpClientFactory: ai.koog.http.client.KoogHttpClient.Factory = SafeKoogHttpClientFactory()
    private val executorCache = java.util.concurrent.ConcurrentHashMap<String, ai.koog.prompt.executor.model.PromptExecutor>()

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
        streamingManager: StreamingManager,
        userMessage: String = "",
        existingAccumulator: TurnContextAccumulator? = null
    ): Pair<AIAgent<String, String>, TurnContextAccumulator>? {
        val apiKey = securePreferences.getApiKey(config.provider)
        if (apiKey.isBlank() && config.provider != AiProvider.OLLAMA) return null

        val providerFactory = getProvider(config.provider) ?: return null
        val cacheKey = "${config.provider}_${apiKey.hashCode()}_${config.baseUrl}"
        val executor = executorCache[cacheKey] ?: run {
            val created = providerFactory.createExecutor(config, apiKey) ?: return null
            executorCache[cacheKey] = created
            created
        }
        val model = providerFactory.resolveModel(config)
        val providerParams = providerFactory.createParams(config)

        val toolRegistry = ToolRegistry {
            tools(medicalDatabaseTools.asToolList())
        }

        val accumulator = existingAccumulator ?: TurnContextAccumulator(
            topicTitleResolver = { tid -> medicalDatabaseTools.getTopicTitle(tid) },
            sectionTitleResolver = { tid, sid -> medicalDatabaseTools.getSectionTitle(tid, sid) }
        )
        // Deliver the patient profile to the accumulator so SafetyValidator Rule 4
        // (deterministic renal & allergy bounds) can engage in-graph — the system prompt alone is advisory.
        accumulator.setPatientProfile(patientProfile)
        if (userMessage.isNotBlank()) {
            accumulator.setUserQuestion(userMessage)
        }

        val isAnthropic = config.provider == AiProvider.ANTHROPIC
        val agentConfig = AIAgentConfig(
            prompt = prompt(
                id = "chat",
                params = providerParams
            ) {
                // Static clinical directives eligible for provider-side KV prompt caching
                val cache = if (isAnthropic) AnthropicCacheControl.Default else null
                system(SystemPrompt.staticClinicalGuidance(config.provider), cache = cache)

                // Dynamic patient profile context
                val patientBlock = SystemPrompt.patientContextBlock(patientProfile)
                if (patientBlock.isNotBlank()) {
                    system(patientBlock)
                }
            },
            model = model,
            // Node-level accounting: every strategy-node transfer consumes one
            // iteration (each tool round-trip ≈ 3 nodes incl. the round boundary).
            // The graph self-terminates via its own rails (MAX_TOOL_ROUNDS=24
            // forced exit + ≤2 premature + ≤2 remediation cycles, worst case
            // ≈ 88 node transfers), so this global cap must stay ABOVE the rail
            // sum — otherwise it cannibalizes chatty-but-legal runs before the
            // forced validation route can fire.
            maxAgentIterations = 100
        )

        val agent = AIAgent(
            promptExecutor = executor,
            agentConfig = agentConfig,
            strategy = ClinicalAgentStrategy.createAutonomous(
                tools = medicalDatabaseTools.asToolList(),
                safetyValidator = safetyValidator,
                accumulator = accumulator,
                maxSafetyRetries = 2,
                historyCompressionThreshold = config.historyCompressionThreshold
            ),
            toolRegistry = toolRegistry
        ) {
            install(ChatMemory) {
                chatHistoryProvider = this@KoogAgentFactory.chatHistoryProvider
                filterMessages { msg -> msg is ai.koog.prompt.message.Message.User || msg is ai.koog.prompt.message.Message.Assistant }
                val windowSize = (config.historyCompressionThreshold / 500).coerceIn(5, 300)
                windowSize(windowSize)
            }

            install(Tracing.Feature) {
                addMessageProcessor(AndroidTraceLogWriter(secureLogger = secureLogger))
            }

            install(MessageTokenizer.Feature) {
                tokenizer = SimpleRegexBasedTokenizer()
                enableCaching = true
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
                        is ai.koog.prompt.streaming.StreamFrame.ReasoningDelta -> {
                            frame.text?.let { streamingManager.onStreamingReasoningDelta(it) }
                        }
                        is ai.koog.prompt.streaming.StreamFrame.ToolCallDelta -> {
                            streamingManager.onStreamingToolCallDelta(
                                callId = frame.id ?: "",
                                content = frame.content ?: "",
                                toolName = frame.name
                            )
                        }
                        is ai.koog.prompt.streaming.StreamFrame.End -> {
                            streamingManager.onStreamingEnd()
                        }
                        else -> {}
                    }
                }

                onToolCallCompleted { eventContext ->
                    val callId = eventContext.toolCallId ?: ""
                    val resultText = eventContext.toolResult?.toString() ?: ""
                    val success = eventContext.toolResult != null
                    val toolArgsStr = eventContext.toolArgs.toString()
                    Log.d(TAG, "Tool completed: ${eventContext.toolName} success=$success result=${resultText.take(200)}")
                    accumulator.onToolCallCompleted(callId, eventContext.toolName, resultText, success, toolArgsStr)
                    streamingManager.onToolCallCompleted(eventContext.toolName, resultText)
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
                    val validation = if (result.startsWith("Clinical Response Verification Blocked:")) {
                        SafetyValidator.ValidationResult(
                            passed = false,
                            warnings = emptyList(),
                            blockedReason = result.removePrefix("Clinical Response Verification Blocked:").trim()
                        )
                    } else {
                        safetyValidator.validate(turnContext)
                    }
                    Log.d(TAG, "Agent completed: tools=${turnContext.toolCalls.map { it.toolName }} validation=${validation.blockedReason ?: "OK"}")
                    streamingManager.onCompleted(result, validation)
                }

                onAgentExecutionFailed { eventContext ->
                    Log.e(TAG, "Agent failed: ${eventContext.error.message}")
                    streamingManager.onError(eventContext.error.message ?: "Unknown error")
                }
            }
        }

        return Pair(agent, accumulator)
    }

    suspend fun getAvailableModels(provider: AiProvider, baseUrl: String = ""): List<String> {
        return getProvider(provider)?.getAvailableModels() ?: emptyList()
    }
}
