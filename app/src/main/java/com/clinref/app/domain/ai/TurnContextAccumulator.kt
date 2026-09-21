package com.clinref.app.domain.ai

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

class TurnContextAccumulator(
    private val topicTitleResolver: ((String) -> String?)? = null,
    private val sectionTitleResolver: ((topicId: String, sectionId: String) -> String?)? = null
) {

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
    private val graphicToTopic = ConcurrentHashMap<String, String>() // graphicId -> topicId
    private var userQuestion: String = ""

    private val pendingToolArgs = ConcurrentHashMap<String, String>()
    private val executedToolCalls = CopyOnWriteArrayList<Pair<String, String>>() // toolName, argsJson
    private val json = AiJsonUtils.json
    private var lastTopicId: String = ""

    fun reset() {
        toolCalls.clear()
        toolResults.clear()
        fetchedSections.clear()
        graphicIds.clear()
        graphicTitles.clear()
        topicTitles.clear()
        outlineSections.clear()
        graphicToTopic.clear()
        pendingToolArgs.clear()
        executedToolCalls.clear()
        userQuestion = ""
        lastTopicId = ""
    }

    /**
     * Prepares this accumulator for a self-healing remediation turn by preserving
     * all accumulated evidence (sections, graphics, titles).
     */
    fun prepareForCorrection() {
        // Evidence is preserved; next turn's answer text will be validated directly
    }

    fun setUserQuestion(question: String) {
        userQuestion = question
    }

    fun getToolCalls(): List<SafetyValidator.ToolCallRecord> = toolCalls.toList()

    fun onToolCallStarting(toolCallId: String, args: String) {
        if (toolCallId.isNotBlank()) {
            pendingToolArgs[toolCallId] = args
        }
        pendingToolArgs["_last_"] = args
    }

    fun onToolCallCompleted(
        toolCallId: String,
        toolName: String,
        result: String,
        success: Boolean,
        toolArgs: String? = null
    ) {
        val canonicalName = AiJsonUtils.normalizeToolName(toolName)
        val args = if (!toolArgs.isNullOrBlank() && toolArgs != "{}") {
            toolArgs
        } else {
            pendingToolArgs.remove(toolCallId) ?: pendingToolArgs.remove("_last_") ?: ""
        }

        // Deduplication: if same tool+args already executed, skip
        val argsKey = args.trim()
        val alreadyExecuted = executedToolCalls.any { (name, cachedArgs) ->
            name == canonicalName && cachedArgs == argsKey
        }
        if (alreadyExecuted) return

        executedToolCalls.add(canonicalName to argsKey)

        val parsedArgs = AiJsonUtils.normalizeArgs(parseArguments(args))

        val logicalSuccess = success && !isLogicalFailure(result)

        toolCalls.add(
            SafetyValidator.ToolCallRecord(
                toolName = canonicalName,
                arguments = parsedArgs,
                result = result,
                success = logicalSuccess
            )
        )
        toolResults.add(result)

        if (logicalSuccess) {
            when (canonicalName) {
                "searchTopics" -> parseSearchResults(result)
                "getRelatedTopics" -> parseRelatedTopicsResult(result)
                "getTopicOutline" -> parseOutlineResult(parsedArgs, result)
                "getTopicSectionsText" -> parseBatchSectionResult(parsedArgs, result)
                "getGraphicContent" -> parseGraphicResult(parsedArgs, result)
            }
        }
    }

    fun buildTurnContext(answer: String): SafetyValidator.TurnContext {
        val rawAnswer = answer

        // Populate topicTitle and sectionTitle on fetched sections from accumulated state or resolvers
        val sectionsWithTitle = fetchedSections.map { sec ->
            val resolvedTitle = topicTitles[sec.topicId]?.takeIf { AiJsonUtils.isValidTopicTitle(it) }
                ?: sec.topicTitle.takeIf { AiJsonUtils.isValidTopicTitle(it) }
                ?: topicTitleResolver?.invoke(sec.topicId)?.takeIf { AiJsonUtils.isValidTopicTitle(it) }
                ?: sec.topicId
            val resolvedSectionTitle = sec.sectionTitle.takeIf { it.isNotBlank() }
                ?: outlineSections[sec.topicId]?.get(sec.sectionId)
                ?: sectionTitleResolver?.invoke(sec.topicId, sec.sectionId)
                ?: ""
            sec.copy(topicTitle = resolvedTitle, sectionTitle = resolvedSectionTitle)
        }

        // Auto-populate refs from fetched sections and graphic IDs
        val validSections = sectionsWithTitle.filter { it.sectionTitle.isNotEmpty() }
        val candidateSections = if (validSections.isNotEmpty()) validSections else sectionsWithTitle
        val autoTopicRefs = candidateSections
            .distinctBy { it.topicId to it.sectionId }
            .map { sec ->
                val topicTitle = topicTitles[sec.topicId]?.takeIf { AiJsonUtils.isValidTopicTitle(it) }
                    ?: sec.topicTitle.takeIf { AiJsonUtils.isValidTopicTitle(it) }
                    ?: topicTitleResolver?.invoke(sec.topicId)?.takeIf { AiJsonUtils.isValidTopicTitle(it) }
                    ?: sec.topicId
                val secTitle = sec.sectionTitle.takeIf { it.isNotBlank() }
                    ?: outlineSections[sec.topicId]?.get(sec.sectionId)
                    ?: sectionTitleResolver?.invoke(sec.topicId, sec.sectionId)
                    ?: ""
                val label = secTitle.ifBlank { topicTitle }
                val cleanSectionId = if (sec.sectionId.equals("FULL", ignoreCase = true)) "" else sec.sectionId
                SafetyValidator.TopicRef(
                    topicId = sec.topicId,
                    sectionId = cleanSectionId,
                    label = label,
                    topicTitle = topicTitle
                )
            }.toMutableList()

        // Ensure parent topics for graphic tables are attributed in finalTopicRefs
        val finalTopicRefs = autoTopicRefs.toMutableList()
        for (gid in graphicIds) {
            val cleanGid = AiJsonUtils.cleanGraphicId(gid)
            val parentTopicId = graphicToTopic[gid] ?: graphicToTopic[cleanGid]
            if (!parentTopicId.isNullOrBlank() && finalTopicRefs.none { it.topicId == parentTopicId }) {
                val resolvedTitle = topicTitles[parentTopicId]?.takeIf { AiJsonUtils.isValidTopicTitle(it) }
                    ?: topicTitleResolver?.invoke(parentTopicId)?.takeIf { AiJsonUtils.isValidTopicTitle(it) }
                    ?: parentTopicId
                finalTopicRefs.add(
                    SafetyValidator.TopicRef(
                        topicId = parentTopicId,
                        sectionId = "",
                        label = resolvedTitle,
                        topicTitle = resolvedTitle
                    )
                )
            }
        }

        val autoGraphicRefs = graphicIds.map { gid ->
            val cleanGid = AiJsonUtils.cleanGraphicId(gid)
            val title = graphicTitles[gid] ?: graphicTitles[cleanGid] ?: "Graphic $gid"
            val parentTopicId = graphicToTopic[gid] ?: graphicToTopic[cleanGid]
            SafetyValidator.GraphicRef(gid, title, parentTopicId)
        }

        return SafetyValidator.TurnContext(
            toolCalls = toolCalls.toList(),
            answer = rawAnswer,
            toolResults = toolResults.toList(),
            fetchedSections = sectionsWithTitle,
            graphicIds = graphicIds.toSet(),
            userQuestion = userQuestion,
            topicRefs = finalTopicRefs,
            graphicRefs = autoGraphicRefs
        )
    }

    /**
     * Create a snapshot for a correction turn: preserves fetched evidence
     * (sections, graphics, titles) but resets answer state and tool tracking
     * so the correction turn doesn't accumulate duplicate tool calls.
     */
    fun snapshotForCorrection(): TurnContextAccumulator {
        val snapshot = TurnContextAccumulator(topicTitleResolver, sectionTitleResolver)
        snapshot.userQuestion = this.userQuestion
        snapshot.fetchedSections.addAll(this.fetchedSections)
        snapshot.graphicIds.addAll(this.graphicIds)
        snapshot.graphicTitles.putAll(this.graphicTitles)
        snapshot.topicTitles.putAll(this.topicTitles)
        snapshot.outlineSections.putAll(this.outlineSections)
        snapshot.graphicToTopic.putAll(this.graphicToTopic)
        snapshot.lastTopicId = this.lastTopicId
        // Copy tool calls and results as evidence (read-only, won't re-execute)
        snapshot.toolCalls.addAll(this.toolCalls)
        snapshot.toolResults.addAll(this.toolResults)
        return snapshot
    }

    private fun isLogicalFailure(result: String): Boolean {
        val trimmed = AiJsonUtils.extractJsonString(result).trim()
        if (trimmed.startsWith("Topic not found", ignoreCase = true)) return true
        if (trimmed.equals("Section not found.", ignoreCase = true)) return true

        // JSON envelopes: {"error": "..."} from failed fetches, or batch section
        // responses where every requested ID was invalid (empty markdown).
        val obj = AiJsonUtils.parseAsJsonObject(trimmed)
        if (obj != null) {
            val error = obj["error"]?.jsonPrimitive?.contentOrNull
            if (!error.isNullOrBlank()) return true
            val markdown = obj["markdown"]?.jsonPrimitive?.contentOrNull
            val invalid = obj["invalidSections"] as? kotlinx.serialization.json.JsonArray
            return markdown != null && markdown.isEmpty() && invalid != null && invalid.isNotEmpty()
        }
        return false
    }

    private fun parseArguments(argsStr: String): Map<String, String> {
        if (argsStr.isBlank() || argsStr == "{}") return emptyMap()
        return try {
            val obj = AiJsonUtils.parseAsJsonObject(argsStr) ?: return emptyMap()
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

    private fun parseSearchResults(result: String) {
        try {
            val obj = AiJsonUtils.parseAsJsonObject(result) ?: return
            val results = obj["results"] as? kotlinx.serialization.json.JsonArray ?: return
            for (item in results) {
                val itemObj = item as? JsonObject ?: continue
                val id = itemObj["id"]?.jsonPrimitive?.content ?: continue
                val title = itemObj["title"]?.jsonPrimitive?.content ?: continue
                if (AiJsonUtils.isValidTopicTitle(title)) {
                    topicTitles[id] = title
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseSearchResults: ${e.message}")
        }
    }

    private fun parseRelatedTopicsResult(result: String) {
        try {
            val obj = AiJsonUtils.parseAsJsonObject(result) ?: return
            val related = obj["relatedTopics"] as? kotlinx.serialization.json.JsonArray ?: return
            for (item in related) {
                val itemObj = item as? JsonObject ?: continue
                val id = itemObj["id"]?.jsonPrimitive?.content ?: continue
                val title = itemObj["title"]?.jsonPrimitive?.content ?: continue
                if (AiJsonUtils.isValidTopicTitle(title)) {
                    topicTitles[id] = title
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseRelatedTopicsResult: ${e.message}")
        }
    }

    private fun parseOutlineResult(args: Map<String, String>, result: String) {
        try {
            val obj = AiJsonUtils.parseAsJsonObject(result) ?: return
            val topicId = obj["topicId"]?.jsonPrimitive?.content
                ?: args["topicId"] ?: args["topic_id"] ?: return
            lastTopicId = topicId
            val title = obj["title"]?.jsonPrimitive?.content ?: ""
            if (AiJsonUtils.isValidTopicTitle(title)) {
                topicTitles[topicId] = title
            }
            // Store full section ID → title map
            val sections = obj["sections"] as? kotlinx.serialization.json.JsonArray
            if (sections != null) {
                val sectionMap = mutableMapOf<String, String>()
                for (item in sections) {
                    val sectionObj = item as? JsonObject ?: continue
                    val id = sectionObj["id"]?.jsonPrimitive?.content ?: continue
                    val sectionTitle = sectionObj["title"]?.jsonPrimitive?.content ?: ""
                    sectionMap[id] = AiJsonUtils.cleanSectionTitle(sectionTitle)
                }
                if (sectionMap.isNotEmpty()) {
                    outlineSections[topicId] = sectionMap
                }
            }
            // Map graphics to topic
            val graphics = obj["graphics"] as? kotlinx.serialization.json.JsonArray
            if (graphics != null) {
                for (item in graphics) {
                    val graphicObj = item as? JsonObject ?: continue
                    val gid = graphicObj["id"]?.jsonPrimitive?.content ?: continue
                    val cleanGid = AiJsonUtils.cleanGraphicId(gid)
                    graphicToTopic[gid] = topicId
                    graphicToTopic[cleanGid] = topicId
                    val gTitle = graphicObj["title"]?.jsonPrimitive?.content ?: ""
                    if (gTitle.isNotBlank()) {
                        graphicTitles[gid] = gTitle
                        graphicTitles[cleanGid] = gTitle
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "parseOutlineResult: ${e.message}") }
    }

    private fun parseBatchSectionResult(args: Map<String, String>, result: String) {
        var topicId = args["topicId"] ?: args["topic_id"] ?: lastTopicId
        val sectionIds = mutableListOf<String>()

        val singleSectionId = args["sectionId"] ?: args["section_id"]
        if (!singleSectionId.isNullOrBlank()) {
            sectionIds.add(singleSectionId.trim())
        }

        val sectionIdsRaw = args["sectionIds"] ?: args["section_ids"] ?: ""
        if (sectionIdsRaw.isNotBlank()) {
            try {
                val element = json.parseToJsonElement(sectionIdsRaw)
                (element as? kotlinx.serialization.json.JsonArray)?.forEach {
                    val content = it.jsonPrimitive.content.trim()
                    if (content.isNotEmpty() && content !in sectionIds) {
                        sectionIds.add(content)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "parseBatchSectionResult sectionIds: ${e.message}")
            }
        }

        // Parse structured JSON result from batch tool
        var sectionMap = outlineSections[topicId]?.toMutableMap() ?: mutableMapOf()
        var respTopicTitle = ""
        try {
            val obj = AiJsonUtils.parseAsJsonObject(result)
            if (obj != null) {
                val respTopicId = obj["topicId"]?.jsonPrimitive?.content ?: ""
                if (respTopicId.isNotBlank()) {
                    topicId = respTopicId
                    lastTopicId = respTopicId
                }
                respTopicTitle = obj["topicTitle"]?.jsonPrimitive?.content ?: ""
                if (AiJsonUtils.isValidTopicTitle(respTopicTitle)) {
                    if (topicId.isEmpty()) {
                        topicId = topicTitles.entries.firstOrNull { it.value == respTopicTitle }?.key ?: lastTopicId
                    }
                    if (topicId.isNotEmpty()) {
                        topicTitles[topicId] = respTopicTitle
                        lastTopicId = topicId
                    }
                }
                if (topicId.isNotEmpty() && !AiJsonUtils.isValidTopicTitle(topicTitles[topicId])) {
                    val resolved = topicTitleResolver?.invoke(topicId)?.takeIf { AiJsonUtils.isValidTopicTitle(it) }
                    if (resolved != null) {
                        topicTitles[topicId] = resolved
                        lastTopicId = topicId
                    }
                }
                val sectionTitles = obj["sectionTitles"] as? JsonObject
                if (sectionTitles != null) {
                    for ((k, v) in sectionTitles) {
                        val titleStr = AiJsonUtils.cleanSectionTitle(v.jsonPrimitive.content)
                        if (titleStr.isNotBlank()) {
                            sectionMap[k] = titleStr
                        }
                    }
                    if (topicId.isNotEmpty()) {
                        outlineSections[topicId] = sectionMap
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "parseBatchSectionResult: ${e.message}") }

        // Fallback: If sectionIds was empty from args, use all sectionTitles from the result JSON
        if (sectionIds.isEmpty() && sectionMap.isNotEmpty()) {
            sectionIds.addAll(sectionMap.keys)
        }
        val unquotedResult = AiJsonUtils.extractJsonString(result)
        if (sectionIds.isEmpty() && (unquotedResult.contains("=== Section: FULL ===") || unquotedResult.contains("=== Calculator:"))) {
            sectionIds.add("FULL")
        }

        val finalTopicTitle = topicTitles[topicId]?.takeIf { AiJsonUtils.isValidTopicTitle(it) }
            ?: respTopicTitle.takeIf { AiJsonUtils.isValidTopicTitle(it) }
            ?: topicTitleResolver?.invoke(topicId)?.takeIf { AiJsonUtils.isValidTopicTitle(it) }
            ?: topicId

        for (sectionId in sectionIds) {
            if (sectionId.isNotEmpty()) {
                val resolvedTitle = AiJsonUtils.cleanSectionTitle(
                    sectionMap[sectionId]
                        ?: outlineSections[topicId]?.get(sectionId)
                        ?: sectionTitleResolver?.invoke(topicId, sectionId)
                        ?: args["sectionTitle"]
                        ?: args["section_title"]
                        ?: ""
                )
                fetchedSections.add(
                    SafetyValidator.FetchedSection(
                        topicId = topicId,
                        topicTitle = finalTopicTitle,
                        sectionId = sectionId,
                        sectionTitle = resolvedTitle,
                        contentSnippet = unquotedResult.take(2000)
                    )
                )
            }
        }
    }

    private fun parseGraphicResult(args: Map<String, String>, result: String) {
        val graphicId = args["graphicId"] ?: args["graphic_id"] ?: ""
        if (graphicId.isNotEmpty()) {
            val cleanId = AiJsonUtils.cleanGraphicId(graphicId)
            graphicIds.add(cleanId)
            // Extract title from result: "### Graphic Table: {title}\n\n{markdown}"
            val unquoted = AiJsonUtils.extractJsonString(result)
            val match = AiJsonUtils.GRAPHIC_TABLE_REGEX.find(unquoted)
            if (match != null) {
                val title = match.groupValues[1].trim()
                graphicTitles[graphicId] = title
                graphicTitles[cleanId] = title
            }
        }
    }
}

