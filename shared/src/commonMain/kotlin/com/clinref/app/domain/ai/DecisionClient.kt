package com.clinref.app.domain.ai

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import com.clinref.shared.platform.PlatformLogger as Log
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * Typed representation of a single block inside the Decisions API `input` array.
 *
 * Official REST format: `{"type": "text", "text": "..."}`.
 */
@Serializable
data class DecisionInputBlock(
    val type: String = "text",
    val text: String
)

/**
 * Base hierarchy for the three typed Decisions API question kinds (`noul`, `choice`, `score`).
 */
sealed interface DecisionQuestion {
    val instructions: String

    fun toJsonObject(): JsonObject

    /**
     * Binary/truth-value probability question returning a calibrated confidence in `[0.01, 0.99]`.
     */
    data class Noul(
        override val instructions: String,
        val trueDescription: String? = null,
        val falseDescription: String? = null
    ) : DecisionQuestion {
        init {
            require(instructions.isNotBlank()) { "Noul question instructions must be non-blank" }
        }

        override fun toJsonObject(): JsonObject = buildJsonObject {
            put("type", "noul")
            put("instructions", instructions)
            if (trueDescription != null || falseDescription != null) {
                put(
                    "criteria",
                    buildJsonObject {
                        trueDescription?.let { put("true_description", it) }
                        falseDescription?.let { put("false_description", it) }
                    }
                )
            }
        }
    }

    /**
     * Categorical classification question across `2..255` labeled options.
     */
    data class Choice(
        override val instructions: String,
        val criteria: Map<String, String>
    ) : DecisionQuestion {
        init {
            require(instructions.isNotBlank()) { "Choice question instructions must be non-blank" }
            require(criteria.size in 2..DecisionClient.MAX_CHOICE_OPTIONS) {
                "Choice question requires 2..${DecisionClient.MAX_CHOICE_OPTIONS} options, got ${criteria.size}"
            }
            criteria.keys.forEach { key ->
                require(key.length in 1..DecisionClient.MAX_CHOICE_KEY_LEN) {
                    "Choice option key must be 1..${DecisionClient.MAX_CHOICE_KEY_LEN} chars: '$key'"
                }
                require(key == key.trim()) {
                    "Choice option key must not contain leading or trailing whitespace: '$key'"
                }
                require(key.none { it.code in 0..31 || it.code == 127 }) {
                    "Choice option key must not contain control characters: '$key'"
                }
                require(key.encodeToByteArray().size <= DecisionClient.MAX_CHOICE_KEY_BYTES) {
                    "Choice option key exceeds ${DecisionClient.MAX_CHOICE_KEY_BYTES} UTF-8 bytes: '$key'"
                }
            }
        }

        override fun toJsonObject(): JsonObject = buildJsonObject {
            put("type", "choice")
            put("instructions", instructions)
            put(
                "criteria",
                buildJsonObject {
                    criteria.forEach { (key, desc) -> put(key, desc) }
                }
            )
        }
    }

    /**
     * Ordinal expected-value rubric question across `2..255` ordered levels (`0..N-1`).
     */
    data class Score(
        override val instructions: String,
        val criteria: List<String>
    ) : DecisionQuestion {
        init {
            require(instructions.isNotBlank()) { "Score question instructions must be non-blank" }
            require(criteria.size in 2..DecisionClient.MAX_SCORE_LEVELS) {
                "Score question requires 2..${DecisionClient.MAX_SCORE_LEVELS} levels, got ${criteria.size}"
            }
            require(criteria.all { it.isNotBlank() }) {
                "Every score level description must be non-blank"
            }
        }

        override fun toJsonObject(): JsonObject = buildJsonObject {
            put("type", "score")
            put("instructions", instructions)
            put(
                "criteria",
                buildJsonArray {
                    criteria.forEach { level -> add(JsonPrimitive(level)) }
                }
            )
        }
    }
}

sealed interface DecisionAnswer {
    val confidence: Double
}

@Serializable
data class DecisionNoulAnswer(
    val noul: Double,
    override val confidence: Double = 0.0
) : DecisionAnswer {
    val pFalse: Double get() = 1.0 - noul
    fun isTrue(threshold: Double = 0.5): Boolean = noul >= threshold
}

@Serializable
data class DecisionChoiceAnswer(
    val choice: String,
    val probabilities: Map<String, Double> = emptyMap(),
    override val confidence: Double = 0.0
) : DecisionAnswer

@Serializable
data class DecisionScoreAnswer(
    val score: Double,
    val legend: Map<String, String> = emptyMap(),
    val probabilities: Map<String, Double> = emptyMap(),
    override val confidence: Double = 0.0
) : DecisionAnswer {
    val levelCount: Int get() = legend.size
    val maxScore: Int get() = maxOf(legend.size - 1, 1)

    val normalizedScore: Double
        get() = if (legend.size <= 1) 0.0 else (score / (legend.size - 1)).coerceIn(0.0, 1.0)

    val topLevel: Pair<Int, String>
        get() {
            if (probabilities.isNotEmpty()) {
                val bestKey = probabilities.maxByOrNull { it.value }?.key
                val idx = bestKey?.toIntOrNull()
                if (bestKey != null && idx != null) {
                    return idx to (legend[bestKey] ?: "")
                }
            }
            val rounded = score.roundToInt()
            return rounded to (legend[rounded.toString()] ?: "")
        }
}

@Serializable
data class ModalityTokenCount(
    val modality: String = "",
    val tokens: Int = 0
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DecisionUsage(
    @SerialName("total_input_tokens")
    @JsonNames("totalInputTokens")
    val totalInputTokens: Int = 0,
    @SerialName("input_tokens_by_modality")
    @JsonNames("inputTokensByModality")
    val inputTokensByModality: List<ModalityTokenCount> = emptyList(),
    @SerialName("total_output_tokens")
    @JsonNames("totalOutputTokens")
    val totalOutputTokens: Int = 0,
    @SerialName("total_tokens")
    @JsonNames("totalTokens")
    val totalTokens: Int = 0
) {
    val estimatedCostUsd: Double
        get() = (totalInputTokens / 1_000_000.0) * DecisionClient.DECISION_INPUT_COST_PER_M_TOKENS
}

data class DecisionBatchResult(
    val answers: Map<String, DecisionAnswer> = emptyMap(),
    val usage: DecisionUsage = DecisionUsage(),
    val ok: Boolean = true,
    val error: String? = null,
    val latencyMs: Double = 0.0
) {
    fun getNoul(key: String): DecisionNoulAnswer? = answers[key] as? DecisionNoulAnswer
    fun getChoice(key: String): DecisionChoiceAnswer? = answers[key] as? DecisionChoiceAnswer
    fun getScore(key: String): DecisionScoreAnswer? = answers[key] as? DecisionScoreAnswer
}

data class DecisionUsageSummary(
    val totalCalls: Int,
    val failedCalls: Int,
    val totalInputTokens: Int,
    val estimatedCostUsd: Double,
    val lastError: String?
)

/**
 * Raw HTTP transport abstraction for the Gemini Decisions REST endpoint.
 */
fun interface DecisionTransport {
    suspend fun postDecisions(requestJson: String): String
}

/**
 * Default [DecisionTransport] backed by [KoogHttpClient] (`POST /v1beta/decisions`).
 */
class KoogDecisionTransport(
    private val apiKey: String,
    private val httpClientFactory: KoogHttpClient.Factory = SafeKoogHttpClientFactory(),
    private val baseUrl: String = DecisionClient.DECISIONS_BASE_URL,
    private val requestPath: String = DecisionClient.DECISIONS_PATH,
    private val timeoutMs: Long = 30_000L,
    private val json: Json = AiJsonUtils.json
) : DecisionTransport, AutoCloseable {

    private val clientLock = Any()
    private var cachedClient: KoogHttpClient? = null

    private fun getOrCreateClient(): KoogHttpClient {
        val trimmedKey = apiKey.trim()
        require(trimmedKey.isNotEmpty()) { "Gemini API key is required for Decisions API" }
        synchronized(clientLock) {
            cachedClient?.let { return it }
            val created = httpClientFactory.create(
                clientName = "GeminiDecisions",
                baseUrl = baseUrl,
                headers = mapOf(
                    "x-goog-api-key" to trimmedKey,
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                queryParameters = emptyMap(),
                requestTimeoutMillis = timeoutMs,
                connectTimeoutMillis = 15_000L,
                socketTimeoutMillis = timeoutMs,
                json = json
            )
            cachedClient = created
            return created
        }
    }

    override suspend fun postDecisions(requestJson: String): String {
        val client = getOrCreateClient()
        try {
            return client.post(
                path = requestPath,
                requestBody = requestJson,
                requestBodyType = String::class,
                responseType = String::class,
                parameters = emptyMap(),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                )
            )
        } catch (e: KoogHttpClientException) {
            throw IllegalStateException(formatHttpError(e.statusCode, e.errorBody, e.message), e)
        }
    }

    override fun close() {
        synchronized(clientLock) {
            try {
                cachedClient?.close()
            } catch (_: Throwable) {
            } finally {
                cachedClient = null
            }
        }
    }

    private fun formatHttpError(statusCode: Int?, errorBody: String?, fallback: String?): String {
        val codeLabel = statusCode?.toString() ?: "error"
        val rawBody = errorBody?.trim().orEmpty()
        if (rawBody.isNotEmpty()) {
            try {
                val obj = json.parseToJsonElement(rawBody).jsonObject
                val errObj = obj["error"]?.jsonObject
                if (errObj != null) {
                    val errCode = errObj["code"]?.jsonPrimitive?.contentOrNull
                    val errMsg = errObj["message"]?.jsonPrimitive?.contentOrNull
                    if (!errMsg.isNullOrBlank()) {
                        val codePart = if (!errCode.isNullOrBlank()) " ($errCode)" else ""
                        return "Decisions API HTTP $codeLabel$codePart: $errMsg"
                    }
                }
            } catch (_: Throwable) {
            }
            return "Decisions API HTTP $codeLabel: ${rawBody.take(500)}"
        }
        return "Decisions API HTTP $codeLabel: ${fallback ?: "Request failed"}"
    }
}

/**
 * Sanitizes an arbitrary identifier into a valid Decisions API question key
 * matching `[A-Za-z0-9_-]+` with length in `1..64`.
 */
fun sanitizeQuestionKey(raw: String, prefix: String = "q", index: Int = 0): String {
    var cleaned = KEY_INVALID_CHAR_REGEX.replace(raw, "_").trim('_')
    if (cleaned.isEmpty()) {
        cleaned = "${prefix}_$index"
    }
    if (!cleaned.first().isLetterOrDigit()) {
        cleaned = "${prefix}_$cleaned"
    }
    return cleaned.take(DecisionClient.MAX_QUESTION_KEY_LEN)
}

/**
 * Builds Decisions API text blocks with explicit `\n\n` boundary separation.
 * Adjacent text blocks without trailing newlines are concatenated verbatim by the API.
 */
fun makeTextBlocks(blocks: List<String>): List<DecisionInputBlock> {
    val nonEmpty = blocks.filter { it.isNotEmpty() }
    return nonEmpty.mapIndexed { idx, block ->
        val text = if (idx < nonEmpty.lastIndex && !block.endsWith("\n")) {
            "$block\n\n"
        } else {
            block
        }
        DecisionInputBlock(type = "text", text = text)
    }
}

fun noulQuestion(
    instructions: String,
    trueDescription: String? = null,
    falseDescription: String? = null
): DecisionQuestion.Noul = DecisionQuestion.Noul(
    instructions = instructions,
    trueDescription = trueDescription,
    falseDescription = falseDescription
)

fun choiceQuestion(
    instructions: String,
    criteria: Map<String, String>
): DecisionQuestion.Choice = DecisionQuestion.Choice(
    instructions = instructions,
    criteria = criteria
)

fun scoreQuestion(
    instructions: String,
    criteria: List<String>
): DecisionQuestion.Score = DecisionQuestion.Score(
    instructions = instructions,
    criteria = criteria
)

private val KEY_INVALID_CHAR_REGEX = Regex("[^A-Za-z0-9_-]+")
private val KEY_VALID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

/**
 * Kotlin Multiplatform client for Google's Gemini Decisions API (`gemini-decision-1`).
 *
 * Supports batching up to 1,000 questions per HTTP request (with automatic multi-chunk
 * execution when larger), typed `noul`/`choice`/`score` response decoding, token accounting,
 * and configurable fail-open behavior so upstream clinical retrieval never breaks on transient errors.
 */
class DecisionClient(
    private val transport: DecisionTransport,
    val model: String = DECISION_MODEL_ID,
    val failOpen: Boolean = true,
    private val onUsage: ((DecisionUsage, Double) -> Unit)? = null,
    private val json: Json = AiJsonUtils.json
) : AutoCloseable {

    constructor(
        apiKey: String,
        httpClientFactory: KoogHttpClient.Factory = SafeKoogHttpClientFactory(),
        model: String = DECISION_MODEL_ID,
        timeoutMs: Long = 30_000L,
        failOpen: Boolean = true,
        onUsage: ((DecisionUsage, Double) -> Unit)? = null
    ) : this(
        transport = KoogDecisionTransport(
            apiKey = apiKey,
            httpClientFactory = httpClientFactory,
            timeoutMs = timeoutMs
        ),
        model = model,
        failOpen = failOpen,
        onUsage = onUsage
    )

    companion object {
        private const val TAG = "DecisionClient"
        const val DECISION_MODEL_ID = "gemini-decision-1"
        const val DECISIONS_API_VERSION = "v1beta"
        const val DECISIONS_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DECISIONS_PATH = "/v1beta/decisions"
        const val DECISIONS_ENDPOINT = "$DECISIONS_BASE_URL$DECISIONS_PATH"
        const val MAX_QUESTIONS_PER_REQUEST = 1000
        const val MAX_CHOICE_OPTIONS = 255
        const val MAX_SCORE_LEVELS = 255
        const val MAX_QUESTION_KEY_LEN = 64
        const val MAX_CHOICE_KEY_LEN = 128
        const val MAX_CHOICE_KEY_BYTES = 512
        const val DECISION_INPUT_COST_PER_M_TOKENS = 0.10
    }

    private val statsLock = Any()
    private var _totalCalls: Int = 0
    private var _failedCalls: Int = 0
    private var _totalInputTokens: Int = 0
    private var _lastError: String? = null

    val totalCalls: Int get() = synchronized(statsLock) { _totalCalls }
    val failedCalls: Int get() = synchronized(statsLock) { _failedCalls }
    val totalInputTokens: Int get() = synchronized(statsLock) { _totalInputTokens }
    val lastError: String? get() = synchronized(statsLock) { _lastError }

    fun getUsageSummary(): DecisionUsageSummary = synchronized(statsLock) {
        DecisionUsageSummary(
            totalCalls = _totalCalls,
            failedCalls = _failedCalls,
            totalInputTokens = _totalInputTokens,
            estimatedCostUsd = (_totalInputTokens / 1_000_000.0) * DECISION_INPUT_COST_PER_M_TOKENS,
            lastError = _lastError
        )
    }

    suspend fun decide(
        input: String,
        questions: Map<String, DecisionQuestion>,
        model: String = this.model
    ): DecisionBatchResult = decideInternal(
        inputJson = JsonPrimitive(input),
        questions = questions,
        model = model
    )

    suspend fun decide(
        input: List<DecisionInputBlock>,
        questions: Map<String, DecisionQuestion>,
        model: String = this.model
    ): DecisionBatchResult {
        val inputArray = buildJsonArray {
            input.forEach { block ->
                add(
                    buildJsonObject {
                        put("type", block.type)
                        put("text", block.text)
                    }
                )
            }
        }
        return decideInternal(
            inputJson = inputArray,
            questions = questions,
            model = model
        )
    }

    private suspend fun decideInternal(
        inputJson: JsonElement,
        questions: Map<String, DecisionQuestion>,
        model: String
    ): DecisionBatchResult {
        val startMark = TimeSource.Monotonic.markNow()
        val entries = questions.entries.toList()
        val chunks = entries.chunked(MAX_QUESTIONS_PER_REQUEST)

        val mergedAnswers = LinkedHashMap<String, DecisionAnswer>(questions.size)
        var totalInputTokensChunk = 0
        var totalOutputTokensChunk = 0
        var totalTokensChunk = 0
        val mergedModalities = LinkedHashMap<String, Int>()
        var attemptedCalls = 0

        try {
            if (questions.isEmpty()) {
                return DecisionBatchResult(
                    answers = emptyMap(),
                    usage = DecisionUsage(),
                    ok = true,
                    error = null,
                    latencyMs = 0.0
                )
            }

            val isBlankInput = when (inputJson) {
                is JsonPrimitive -> inputJson.content.isBlank()
                is JsonArray -> inputJson.isEmpty() || inputJson.all { element ->
                    (element as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.isNullOrBlank()
                }
                else -> false
            }
            require(!isBlankInput) { "Decisions input cannot be empty" }

            questions.keys.forEach { key ->
                require(KEY_VALID_REGEX.matches(key)) {
                    "Decision question key '$key' must match [A-Za-z0-9_-]{1,64}"
                }
            }

            for (chunk in chunks) {
                val requestObj = buildJsonObject {
                    put("model", model)
                    put("input", inputJson)
                    put(
                        "questions",
                        buildJsonObject {
                            chunk.forEach { (key, question) ->
                                put(key, question.toJsonObject())
                            }
                        }
                    )
                }
                val requestBody = json.encodeToString(JsonObject.serializer(), requestObj)
                attemptedCalls++
                val responseRaw = transport.postDecisions(requestBody)
                val parsed = parseResponsePayload(responseRaw)

                mergedAnswers.putAll(parsed.first)
                val usage = parsed.second
                totalInputTokensChunk += usage.totalInputTokens
                totalOutputTokensChunk += usage.totalOutputTokens
                totalTokensChunk += if (usage.totalTokens > 0) usage.totalTokens else usage.totalInputTokens
                usage.inputTokensByModality.forEach { m ->
                    if (m.modality.isNotEmpty()) {
                        mergedModalities[m.modality] = (mergedModalities[m.modality] ?: 0) + m.tokens
                    }
                }
            }

            val elapsedMs = startMark.elapsedNow().inWholeMicroseconds / 1000.0
            val combinedUsage = DecisionUsage(
                totalInputTokens = totalInputTokensChunk,
                inputTokensByModality = mergedModalities.map { (mod, tokens) ->
                    ModalityTokenCount(modality = mod, tokens = tokens)
                },
                totalOutputTokens = totalOutputTokensChunk,
                totalTokens = if (totalTokensChunk > 0) totalTokensChunk else totalInputTokensChunk
            )

            synchronized(statsLock) {
                _totalCalls += attemptedCalls
                _totalInputTokens += combinedUsage.totalInputTokens
                _lastError = null
            }

            try {
                onUsage?.invoke(combinedUsage, elapsedMs)
            } catch (_: Throwable) {
            }

            return DecisionBatchResult(
                answers = mergedAnswers,
                usage = combinedUsage,
                ok = true,
                error = null,
                latencyMs = elapsedMs
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val elapsedMs = startMark.elapsedNow().inWholeMicroseconds / 1000.0
            val msg = e.message ?: e::class.simpleName ?: "Decision API request failed"
            if (attemptedCalls > 0) {
                synchronized(statsLock) {
                    _totalCalls += attemptedCalls
                    _failedCalls += 1
                    _lastError = msg
                }
            } else {
                synchronized(statsLock) {
                    _lastError = msg
                }
            }
            Log.w(TAG, "Decisions API call failed (failOpen=$failOpen): $msg")
            if (!failOpen) throw e
            return DecisionBatchResult(
                answers = emptyMap(),
                usage = DecisionUsage(),
                ok = false,
                error = msg,
                latencyMs = elapsedMs
            )
        }
    }

    private fun parseResponsePayload(raw: String): Pair<Map<String, DecisionAnswer>, DecisionUsage> {
        val root = json.parseToJsonElement(raw).jsonObject
        root["error"]?.let { errElement ->
            val errObj = errElement as? JsonObject
            val code = errObj?.get("code")?.jsonPrimitive?.contentOrNull
            val message = errObj?.get("message")?.jsonPrimitive?.contentOrNull
            val detail = message ?: errElement.toString()
            val prefix = if (!code.isNullOrBlank()) "Decisions API error ($code): " else "Decisions API error: "
            throw IllegalStateException("$prefix$detail")
        }

        val answersElement = root["answers"] as? JsonObject
            ?: throw IllegalStateException("Decisions API response missing 'answers' object")

        val decodedAnswers = LinkedHashMap<String, DecisionAnswer>(answersElement.size)
        for ((key, value) in answersElement) {
            val ansObj = value as? JsonObject ?: continue
            val type = ansObj["type"]?.jsonPrimitive?.contentOrNull
            val answer: DecisionAnswer? = when (type) {
                "noul" -> json.decodeFromJsonElement(DecisionNoulAnswer.serializer(), ansObj)
                "choice" -> json.decodeFromJsonElement(DecisionChoiceAnswer.serializer(), ansObj)
                "score" -> json.decodeFromJsonElement(DecisionScoreAnswer.serializer(), ansObj)
                else -> when {
                    "noul" in ansObj -> json.decodeFromJsonElement(DecisionNoulAnswer.serializer(), ansObj)
                    "choice" in ansObj -> json.decodeFromJsonElement(DecisionChoiceAnswer.serializer(), ansObj)
                    "score" in ansObj -> json.decodeFromJsonElement(DecisionScoreAnswer.serializer(), ansObj)
                    else -> null
                }
            }
            if (answer != null) {
                decodedAnswers[key] = answer
            }
        }

        val usage = root["usage"]?.let { usageEl ->
            json.decodeFromJsonElement(DecisionUsage.serializer(), usageEl)
        } ?: DecisionUsage()

        return decodedAnswers to usage
    }

    override fun close() {
        (transport as? AutoCloseable)?.close()
    }
}
