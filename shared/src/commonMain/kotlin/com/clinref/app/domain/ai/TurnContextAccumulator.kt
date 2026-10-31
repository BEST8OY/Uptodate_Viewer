package com.clinref.app.domain.ai

import com.clinref.shared.platform.PlatformLogger as Log
import com.clinref.app.data.GraphicContentResponse
import com.clinref.app.data.SearchTopicSectionsResponse
import com.clinref.app.data.SearchTopicsResponse
import com.clinref.app.data.TopicOverviewResponse
import com.clinref.app.data.TopicSectionsResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private var patientProfile: PatientProfile? = null

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
        patientProfile = null
        lastTopicId = ""
    }

    fun setUserQuestion(question: String) {
        userQuestion = question
    }

    fun setPatientProfile(profile: PatientProfile?) {
        patientProfile = profile
    }

    fun getPatientProfile(): PatientProfile? = patientProfile

    val fetchedSectionsCount: Int get() = fetchedSections.size
    val graphicCount: Int get() = graphicIds.size
    val hasEvidence: Boolean get() = fetchedSections.isNotEmpty() || graphicIds.isNotEmpty()

    fun getToolCalls(): List<SafetyValidator.ToolCallRecord> = toolCalls.toList()
    fun getFetchedSections(): List<SafetyValidator.FetchedSection> = fetchedSections.toList()

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
                "getTopicOverview" -> parseTopicOverviewResult(parsedArgs, result)
                "searchTopics" -> parseSearchResults(result)
                "searchTopicSections" -> parseSearchTopicSectionsResult(parsedArgs, result)
                "getTopicSectionsText" -> parseBatchSectionResult(parsedArgs, result)
                "getGraphicContent" -> parseGraphicResult(parsedArgs, result)
            }
        }
    }

    /** The question that opened this turn, as supplied by the caller. */
    val question: String get() = userQuestion

    fun buildTurnContext(answer: String): SafetyValidator.TurnContext {
        val cleanAnswer = ReasoningTokenSanitizer.stripReasoning(answer)

        // Populate topicTitle and sectionTitle on fetched sections from accumulated state or resolvers
        val sectionsWithTitle = fetchedSections.map { sec ->
            val resolvedTitle = resolveTopicTitle(sec.topicId, sec.topicTitle)
            val resolvedSectionTitle = resolveSectionTitle(sec.topicId, sec.sectionId, sec.sectionTitle)
            sec.copy(topicTitle = resolvedTitle, sectionTitle = resolvedSectionTitle)
        }

        // Auto-populate refs from fetched sections and graphic IDs
        val validSections = sectionsWithTitle.filter { it.sectionTitle.isNotEmpty() }
        val candidateSections = if (validSections.isNotEmpty()) validSections else sectionsWithTitle
        val autoTopicRefs = candidateSections
            .distinctBy { it.topicId to it.sectionId }
            .map { sec ->
                val label = sec.sectionTitle.ifBlank { sec.topicTitle }
                val cleanSectionId = if (sec.sectionId.equals("FULL", ignoreCase = true)) "" else sec.sectionId
                SafetyValidator.TopicRef(
                    topicId = sec.topicId,
                    sectionId = cleanSectionId,
                    label = label,
                    topicTitle = sec.topicTitle
                )
            }.toMutableList()

        // Ensure parent topics for graphic tables are attributed in finalTopicRefs
        val finalTopicRefs = autoTopicRefs.toMutableList()
        for (gid in graphicIds) {
            val cleanGid = AiJsonUtils.cleanGraphicId(gid)
            val parentTopicId = graphicToTopic[gid] ?: graphicToTopic[cleanGid]
            if (!parentTopicId.isNullOrBlank() && finalTopicRefs.none { it.topicId == parentTopicId }) {
                val resolvedTitle = resolveTopicTitle(parentTopicId)
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
            answer = cleanAnswer,
            toolResults = toolResults.toList(),
            fetchedSections = sectionsWithTitle,
            graphicIds = graphicIds.toSet(),
            userQuestion = userQuestion,
            topicRefs = finalTopicRefs,
            graphicRefs = autoGraphicRefs,
            patientProfile = patientProfile
        )
    }

    private fun resolveTopicTitle(topicId: String, candidateTitle: String? = null): String {
        return topicTitles[topicId]?.takeIf { AiJsonUtils.isValidTopicTitle(it) }
            ?: candidateTitle?.takeIf { AiJsonUtils.isValidTopicTitle(it) }
            ?: topicTitleResolver?.invoke(topicId)?.takeIf { AiJsonUtils.isValidTopicTitle(it) }
            ?: topicId
    }

    private fun resolveSectionTitle(topicId: String, sectionId: String, candidateTitle: String? = null): String {
        return candidateTitle?.takeIf { it.isNotBlank() }
            ?: outlineSections[topicId]?.get(sectionId)
            ?: sectionTitleResolver?.invoke(topicId, sectionId)
            ?: ""
    }

    private fun isLogicalFailure(result: String): Boolean {
        val trimmed = AiJsonUtils.extractJsonString(result)
        if (trimmed.isEmpty()) return true
        if (trimmed.startsWith("Topic not found", ignoreCase = true)) return true
        if (trimmed.equals("Section not found.", ignoreCase = true)) return true

        return try {
            val element = json.parseToJsonElement(trimmed)
            if (element is JsonObject) {
                val error = element["error"]?.jsonPrimitive?.contentOrNull
                if (!error.isNullOrBlank()) return true
                val markdown = element["markdown"]?.jsonPrimitive?.contentOrNull
                val invalid = element["invalidSections"] as? kotlinx.serialization.json.JsonArray
                markdown != null && markdown.isEmpty() && invalid != null && invalid.isNotEmpty()
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun parseArguments(argsStr: String): Map<String, String> {
        if (argsStr.isBlank() || argsStr == "{}") return emptyMap()
        return try {
            val element = json.parseToJsonElement(argsStr)
            val obj = element as? JsonObject ?: return emptyMap()
            obj.entries.associate { (key, value) ->
                key to when (value) {
                    is JsonPrimitive -> value.content
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
            val unquoted = AiJsonUtils.extractJsonString(result)
            val res = json.decodeFromString<SearchTopicsResponse>(unquoted)
            for (item in res.results) {
                if (AiJsonUtils.isValidTopicTitle(item.title)) {
                    topicTitles[item.id] = item.title
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseSearchResults: ${e.message}")
        }
    }

    private fun parseSearchTopicSectionsResult(args: Map<String, String>, result: String) {
        try {
            val unquoted = AiJsonUtils.extractJsonString(result)
            val res = json.decodeFromString<SearchTopicSectionsResponse>(unquoted)
            val topicId = res.topicId.ifBlank { args["topicId"] ?: args["topic_id"] ?: return }
            lastTopicId = topicId
            val sectionMap = outlineSections[topicId]?.toMutableMap() ?: mutableMapOf()
            for (item in res.matches) {
                val cleanTitle = AiJsonUtils.cleanSectionTitle(item.sectionTitle)
                if (cleanTitle.isNotBlank()) {
                    sectionMap[item.sectionId] = cleanTitle
                }
            }
            if (sectionMap.isNotEmpty()) {
                outlineSections[topicId] = sectionMap
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseSearchTopicSectionsResult: ${e.message}")
        }
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
        var fullMarkdown = ""
        try {
            val unquoted = AiJsonUtils.extractJsonString(result)
            val res = json.decodeFromString<TopicSectionsResponse>(unquoted)
            if (res.topicId.isNotBlank()) {
                topicId = res.topicId
                lastTopicId = res.topicId
            }
            respTopicTitle = res.topicTitle
            fullMarkdown = res.markdown
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
            for ((k, v) in res.sectionTitles) {
                val titleStr = AiJsonUtils.cleanSectionTitle(v)
                if (titleStr.isNotBlank()) {
                    sectionMap[k] = titleStr
                }
            }
            if (topicId.isNotEmpty() && sectionMap.isNotEmpty()) {
                outlineSections[topicId] = sectionMap
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseBatchSectionResult: ${e.message}")
        }

        // Fallback: If sectionIds was empty from args, use all sectionTitles from the result JSON
        if (sectionIds.isEmpty() && sectionMap.isNotEmpty()) {
            sectionIds.addAll(sectionMap.keys)
        }
        if (sectionIds.isEmpty() && (fullMarkdown.contains("=== Section: FULL ===") || fullMarkdown.contains("=== Calculator:"))) {
            sectionIds.add("FULL")
        }

        val finalTopicTitle = resolveTopicTitle(topicId, respTopicTitle)

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
                        contentSnippet = extractSectionText(fullMarkdown, sectionId)
                    )
                )
            }
        }
    }

    private fun extractSectionText(fullMarkdown: String, sectionId: String): String {
        if (sectionId.equals("FULL", ignoreCase = true)) return fullMarkdown.trim()
        val headerPattern = "=== Section: $sectionId ==="
        val startIndex = fullMarkdown.indexOf(headerPattern)
        if (startIndex == -1) return fullMarkdown.trim()
        val textStart = startIndex + headerPattern.length
        val nextHeaderIndex = fullMarkdown.indexOf("=== Section: ", textStart)
        return if (nextHeaderIndex != -1) {
            fullMarkdown.substring(textStart, nextHeaderIndex).trim()
        } else {
            fullMarkdown.substring(textStart).trim()
        }
    }

    private fun parseGraphicResult(args: Map<String, String>, result: String) {
        val graphicIdFromArgs = args["graphicId"] ?: args["graphic_id"] ?: ""
        val unquoted = AiJsonUtils.extractJsonString(result)
        try {
            val res = json.decodeFromString<GraphicContentResponse>(unquoted)
            val graphicId = res.graphicId.ifBlank { graphicIdFromArgs }
            if (graphicId.isNotEmpty()) {
                val cleanId = AiJsonUtils.cleanGraphicId(graphicId)
                graphicIds.add(cleanId)
                val title = res.title.trim().ifEmpty {
                    AiJsonUtils.GRAPHIC_TABLE_REGEX.find(res.markdown)?.groupValues?.get(1)?.trim().orEmpty()
                }
                if (title.isNotEmpty()) {
                    graphicTitles[graphicId] = title
                    graphicTitles[cleanId] = title
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseGraphicResult: ${e.message}")
            if (graphicIdFromArgs.isNotEmpty()) {
                val cleanId = AiJsonUtils.cleanGraphicId(graphicIdFromArgs)
                graphicIds.add(cleanId)
                val match = AiJsonUtils.GRAPHIC_TABLE_REGEX.find(unquoted)
                if (match != null) {
                    val title = match.groupValues[1].trim()
                    if (title.isNotEmpty()) {
                        graphicTitles[graphicIdFromArgs] = title
                        graphicTitles[cleanId] = title
                    }
                }
            }
        }
    }

    private fun parseTopicOverviewResult(args: Map<String, String>, result: String) {
        try {
            val unquoted = AiJsonUtils.extractJsonString(result)
            val res = json.decodeFromString<TopicOverviewResponse>(unquoted)
            val topicId = res.topicId.ifBlank { args["topicId"] ?: args["topic_id"] ?: return }
            lastTopicId = topicId

            val title = res.title
            if (AiJsonUtils.isValidTopicTitle(title)) {
                topicTitles[topicId] = title
            }

            // Outline sections
            val sectionMap = outlineSections[topicId]?.toMutableMap() ?: mutableMapOf()
            for (sec in res.sections) {
                val cleanSecTitle = AiJsonUtils.cleanSectionTitle(sec.title)
                if (cleanSecTitle.isNotBlank()) {
                    sectionMap[sec.id] = cleanSecTitle
                }
            }
            if (sectionMap.isNotEmpty()) {
                outlineSections[topicId] = sectionMap
            }

            // Graphics
            for (g in res.graphics) {
                val gid = g.id
                val cleanGid = AiJsonUtils.cleanGraphicId(gid)
                graphicToTopic[gid] = topicId
                graphicToTopic[cleanGid] = topicId
                if (g.title.isNotBlank()) {
                    graphicTitles[gid] = g.title
                    graphicTitles[cleanGid] = g.title
                }
            }

            // Calculator topic evidence
            val finalTopicTitle = resolveTopicTitle(topicId, title)
            if (res.topicType == "calc") {
                fetchedSections.add(
                    SafetyValidator.FetchedSection(
                        topicId = topicId,
                        topicTitle = finalTopicTitle,
                        sectionId = "FULL",
                        sectionTitle = "Calculator Tool Content",
                        contentSnippet = res.markdown.trim()
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseTopicOverviewResult: ${e.message}")
        }
    }
}
