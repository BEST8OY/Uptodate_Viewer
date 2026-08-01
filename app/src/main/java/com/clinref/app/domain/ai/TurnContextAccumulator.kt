package com.clinref.app.domain.ai

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

class TurnContextAccumulator {

    private companion object {
        private const val TAG = "TurnCtxAccum"
    }

    private val toolCalls = CopyOnWriteArrayList<SafetyValidator.ToolCallRecord>()
    private val toolResults = CopyOnWriteArrayList<String>()
    private val fetchedSections = CopyOnWriteArrayList<SafetyValidator.FetchedSection>()
    private val graphicIds = CopyOnWriteArraySet<String>()
    private val graphicTitles = ConcurrentHashMap<String, String>()
    private val topicTitles = ConcurrentHashMap<String, String>()
    private val outlineSections = ConcurrentHashMap<String, Map<String, String>>() // topicId -> {sectionId: title}
    private var userQuestion: String = ""

    private val pendingToolArgs = ConcurrentHashMap<String, String>()
    private val executedToolCalls = CopyOnWriteArrayList<Pair<String, String>>() // toolName, argsJson
    private val cachedResults = ConcurrentHashMap<String, String>() // argsJson -> result
    private val json = Json { ignoreUnknownKeys = true }

    fun reset() {
        toolCalls.clear()
        toolResults.clear()
        fetchedSections.clear()
        graphicIds.clear()
        graphicTitles.clear()
        topicTitles.clear()
        outlineSections.clear()
        pendingToolArgs.clear()
        executedToolCalls.clear()
        cachedResults.clear()
        userQuestion = ""
        submittedAnswerText = ""
        structuredTopicRefs = emptyList()
        structuredGraphicRefs = emptyList()
    }

    fun setUserQuestion(question: String) {
        userQuestion = question
    }

    fun getToolCalls(): List<SafetyValidator.ToolCallRecord> = toolCalls.toList()

    fun getSubmittedAnswer(): String = submittedAnswerText

    fun onToolCallStarting(toolCallId: String, args: String) {
        if (toolCallId.isNotBlank()) {
            pendingToolArgs[toolCallId] = args
        }
    }

    fun onToolCallCompleted(toolCallId: String, toolName: String, result: String, success: Boolean) {
        val args = pendingToolArgs.remove(toolCallId) ?: ""

        // Deduplication: if same tool+args already executed, skip
        val argsKey = args.trim()
        val alreadyExecuted = executedToolCalls.any { (name, cachedArgs) ->
            name == toolName && cachedArgs == argsKey
        }
        if (alreadyExecuted) return

        executedToolCalls.add(toolName to argsKey)
        cachedResults[argsKey] = result

        val parsedArgs = parseArguments(args)

        val logicalSuccess = success && !isLogicalFailure(result)

        toolCalls.add(
            SafetyValidator.ToolCallRecord(
                toolName = toolName,
                arguments = parsedArgs,
                result = result,
                success = logicalSuccess
            )
        )
        toolResults.add(result)

        if (logicalSuccess) {
            when (toolName) {
                "getTopicOutline" -> parseOutlineResult(parsedArgs, result)
                "getTopicSectionsText" -> parseBatchSectionResult(parsedArgs, result)
                "getGraphicContent" -> parseGraphicResult(parsedArgs, result)
                "submitClinicalAnswer" -> {
                    parseSubmittedAnswer(result)
                    parseStructuredRefs(result)
                }
            }
        }
    }

    fun buildTurnContext(answer: String): SafetyValidator.TurnContext {
        val rawAnswer = if (submittedAnswerText.isNotBlank()) submittedAnswerText else answer

        // Populate topicTitle on fetched sections from accumulated topicTitles
        val sectionsWithTitle = fetchedSections.map { sec ->
            if (sec.topicTitle.isEmpty()) {
                sec.copy(topicTitle = topicTitles[sec.topicId] ?: sec.topicId)
            } else sec
        }

        // Auto-populate refs from fetched sections and graphic IDs
        val autoTopicRefs = if (structuredTopicRefs.isNotEmpty()) structuredTopicRefs else {
            sectionsWithTitle
                .filter { it.sectionTitle.isNotEmpty() }
                .distinctBy { it.sectionId }
                .map {
                    val topicTitle = topicTitles[it.topicId] ?: it.topicId
                    SafetyValidator.TopicRef(
                        topicId = it.topicId,
                        sectionId = it.sectionId,
                        label = it.sectionTitle,
                        topicTitle = topicTitle
                    )
                }
        }
        val autoGraphicRefs = if (structuredGraphicRefs.isNotEmpty()) structuredGraphicRefs else {
            graphicIds.map {
                SafetyValidator.GraphicRef(it, graphicTitles[it] ?: "Graphic $it")
            }
        }

        return SafetyValidator.TurnContext(
            toolCalls = toolCalls.toList(),
            answer = rawAnswer,
            toolResults = toolResults.toList(),
            fetchedSections = sectionsWithTitle,
            graphicIds = graphicIds.toSet(),
            userQuestion = userQuestion,
            topicRefs = autoTopicRefs,
            graphicRefs = autoGraphicRefs
        )
    }

    /**
     * Create a snapshot for a correction turn: preserves fetched evidence
     * (sections, graphics, titles) but resets answer state and tool tracking
     * so the correction turn doesn't accumulate duplicate tool calls.
     */
    fun snapshotForCorrection(): TurnContextAccumulator {
        val snapshot = TurnContextAccumulator()
        snapshot.userQuestion = this.userQuestion
        snapshot.fetchedSections.addAll(this.fetchedSections)
        snapshot.graphicIds.addAll(this.graphicIds)
        snapshot.graphicTitles.putAll(this.graphicTitles)
        snapshot.topicTitles.putAll(this.topicTitles)
        snapshot.outlineSections.putAll(this.outlineSections)
        // Copy tool calls and results as evidence (read-only, won't re-execute)
        snapshot.toolCalls.addAll(this.toolCalls)
        snapshot.toolResults.addAll(this.toolResults)
        return snapshot
    }



    private fun parseSubmittedAnswer(result: String) {
        try {
            val element = json.parseToJsonElement(result)
            val obj = element as? JsonObject ?: return
            submittedAnswerText = obj["answer"]?.jsonPrimitive?.content ?: ""
        } catch (e: Exception) { Log.w(TAG, "parseSubmittedAnswer: ${e.message}") }
    }

    private fun parseStructuredRefs(result: String) {
        try {
            val element = json.parseToJsonElement(result)
            val obj = element as? JsonObject ?: return

            val topicRefsArray = obj["topicRefs"] as? kotlinx.serialization.json.JsonArray
            if (topicRefsArray != null) {
                structuredTopicRefs = topicRefsArray.mapNotNull { item ->
                    val refObj = item as? JsonObject ?: return@mapNotNull null
                    SafetyValidator.TopicRef(
                        topicId = refObj["topicId"]?.jsonPrimitive?.content ?: "",
                        sectionId = refObj["sectionId"]?.jsonPrimitive?.content ?: "",
                        label = refObj["label"]?.jsonPrimitive?.content ?: "",
                        topicTitle = refObj["topicTitle"]?.jsonPrimitive?.content ?: ""
                    )
                }
            }

            val graphicRefsArray = obj["graphicRefs"] as? kotlinx.serialization.json.JsonArray
            if (graphicRefsArray != null) {
                structuredGraphicRefs = graphicRefsArray.mapNotNull { item ->
                    val refObj = item as? JsonObject ?: return@mapNotNull null
                    SafetyValidator.GraphicRef(
                        graphicId = refObj["graphicId"]?.jsonPrimitive?.content ?: "",
                        label = refObj["label"]?.jsonPrimitive?.content ?: ""
                    )
                }
            }
        } catch (e: Exception) { Log.w(TAG, "parseStructuredRefs: ${e.message}") }
    }

    private fun isLogicalFailure(result: String): Boolean {
        val trimmed = result.trim()
        if (trimmed.startsWith("Topic not found", ignoreCase = true)) return true
        if (trimmed.equals("Section not found.", ignoreCase = true)) return true
        return false
    }

    private fun parseArguments(argsStr: String): Map<String, String> {
        if (argsStr.isBlank() || argsStr == "{}") return emptyMap()
        return try {
            val element = json.parseToJsonElement(argsStr)
            val obj = element as? JsonObject ?: return emptyMap()
            obj.entries.associate { (key, value) ->
                key to when (value) {
                    is kotlinx.serialization.json.JsonPrimitive -> value.content
                    else -> value.toString()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseArguments: ${e.message}")
            emptyMap()
        }
    }

    private fun parseOutlineResult(args: Map<String, String>, result: String) {
        try {
            val element = json.parseToJsonElement(result)
            val obj = element as? JsonObject ?: return
            val topicId = obj["topicId"]?.jsonPrimitive?.content
                ?: args["topicId"] ?: return
            val title = obj["title"]?.jsonPrimitive?.content ?: ""
            if (title.isNotEmpty()) {
                topicTitles[topicId] = title
            }
            // Store full section ID → title map
            val sections = obj["sections"] as? kotlinx.serialization.json.JsonArray ?: return
            val sectionMap = mutableMapOf<String, String>()
            for (item in sections) {
                val sectionObj = item as? JsonObject ?: continue
                val id = sectionObj["id"]?.jsonPrimitive?.content ?: continue
                val sectionTitle = sectionObj["title"]?.jsonPrimitive?.content ?: ""
                sectionMap[id] = sectionTitle.trimStart('-', '–', '—')
            }
            if (sectionMap.isNotEmpty()) {
                outlineSections[topicId] = sectionMap
            }
        } catch (e: Exception) { Log.w(TAG, "parseOutlineResult: ${e.message}") }
    }

    private fun parseBatchSectionResult(args: Map<String, String>, result: String) {
        val topicId = args["topicId"] ?: ""
        val sectionIdsRaw = args["sectionIds"] ?: ""

        // Parse the sectionIds - it's a JSON array string like ["H1","H2"]
        val sectionIds = try {
            val element = json.parseToJsonElement(sectionIdsRaw)
            (element as? kotlinx.serialization.json.JsonArray)
                ?.map { it.jsonPrimitive.content }
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "parseBatchSectionResult sectionIds: ${e.message}")
            emptyList()
        }

        // Parse structured JSON result from batch tool
        var sectionMap = outlineSections[topicId] ?: emptyMap()
        try {
            val element = json.parseToJsonElement(result)
            val obj = element as? JsonObject
            if (obj != null) {
                val topicTitle = obj["topicTitle"]?.jsonPrimitive?.content ?: ""
                if (topicId.isNotEmpty() && topicTitle.isNotEmpty()) {
                    topicTitles[topicId] = topicTitle
                }
                val sectionTitles = obj["sectionTitles"] as? JsonObject
                if (sectionTitles != null) {
                    val parsed = sectionTitles.entries.associate { (k, v) -> k to v.jsonPrimitive.content.trimStart('-', '–', '—') }
                    if (topicId.isNotEmpty()) {
                        outlineSections[topicId] = parsed
                    }
                    sectionMap = parsed
                }
            }
        } catch (e: Exception) { Log.w(TAG, "parseBatchSectionResult: ${e.message}") }

        for (sectionId in sectionIds) {
            if (sectionId.isNotEmpty()) {
                fetchedSections.add(
                    SafetyValidator.FetchedSection(
                        topicId = topicId,
                        topicTitle = topicTitles[topicId] ?: "",
                        sectionId = sectionId,
                        sectionTitle = sectionMap[sectionId] ?: "",
                        contentSnippet = result.take(2000)
                    )
                )
            }
        }
    }

    private fun parseGraphicResult(args: Map<String, String>, result: String) {
        val graphicId = args["graphicId"] ?: ""
        if (graphicId.isNotEmpty()) {
            graphicIds.add(graphicId)
            // Extract title from result: "### Graphic Table: {title}\n\n{markdown}"
            val match = Regex("""### Graphic Table:\s*(.+)""").find(result)
            if (match != null) {
                graphicTitles[graphicId] = match.groupValues[1].trim()
            }
        }
    }

    private var submittedAnswerText: String = ""
    private var structuredTopicRefs: List<SafetyValidator.TopicRef> = emptyList()
    private var structuredGraphicRefs: List<SafetyValidator.GraphicRef> = emptyList()


}
