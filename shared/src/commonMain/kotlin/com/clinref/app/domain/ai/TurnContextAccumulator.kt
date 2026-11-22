package com.clinref.app.domain.ai

import com.clinref.shared.platform.PlatformLogger as Log
import com.clinref.app.data.GraphicContentResponse
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

    private val toolCalls = CopyOnWriteArrayList<ToolCallRecord>()
    private val toolResults = CopyOnWriteArrayList<String>()
    private val fetchedSections = CopyOnWriteArrayList<FetchedSection>()
    private val fetchedGraphics = CopyOnWriteArrayList<FetchedGraphic>()
    private val graphicIds = CopyOnWriteArraySet<String>()
    private val graphicTitles = ConcurrentHashMap<String, String>()
    private val topicTitles = ConcurrentHashMap<String, String>()
    private val outlineSections = ConcurrentHashMap<String, Map<String, String>>() // topicId -> {sectionId: title}
    private val graphicToTopic = ConcurrentHashMap<String, String>() // graphicId -> topicId
    private val topicDiscoveryOrder = CopyOnWriteArrayList<String>()
    private val graphicDiscoveryOrder = CopyOnWriteArrayList<String>()
    private val sectionRelevanceScores = ConcurrentHashMap<String, Double>() // "topicId:sectionId" -> score
    private val graphicRelevanceScores = ConcurrentHashMap<String, Double>() // cleanGraphicId -> score
    private var userQuestion: String = ""
    private var patientProfile: PatientProfile? = null

    private val stateLock = Any()
    private val pendingToolArgs = ConcurrentHashMap<String, String>()
    private val executedToolCalls = mutableListOf<Pair<String, String>>() // toolName, argsJson (guarded by stateLock)
    private val json = AiJsonUtils.json
    @Volatile
    private var lastTopicId: String = ""

    fun reset() {
        toolCalls.clear()
        toolResults.clear()
        fetchedSections.clear()
        fetchedGraphics.clear()
        graphicIds.clear()
        graphicTitles.clear()
        topicTitles.clear()
        outlineSections.clear()
        graphicToTopic.clear()
        topicDiscoveryOrder.clear()
        graphicDiscoveryOrder.clear()
        sectionRelevanceScores.clear()
        graphicRelevanceScores.clear()
        pendingToolArgs.clear()
        synchronized(stateLock) {
            executedToolCalls.clear()
        }
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

    fun getToolCalls(): List<ToolCallRecord> = toolCalls.toList()
    fun getFetchedSections(): List<FetchedSection> = fetchedSections.toList()
    fun getFetchedGraphics(): List<FetchedGraphic> = fetchedGraphics.toList()

    fun hasFetchedSection(topicId: String, sectionId: String): Boolean =
        fetchedSections.any {
            it.topicId == topicId &&
                (it.sectionId == sectionId || it.sectionId.equals("FULL", ignoreCase = true))
        }

    fun hasFetchedGraphic(graphicId: String): Boolean =
        graphicIds.contains(AiJsonUtils.cleanGraphicId(graphicId))

    fun hasInspectedTopic(topicId: String): Boolean =
        outlineSections.containsKey(topicId)

    fun recordSectionScore(topicId: String, sectionId: String, score: Double) {
        if (sectionId.isNotBlank()) {
            sectionRelevanceScores["$topicId:$sectionId"] = score
        }
    }

    fun recordGraphicScore(graphicId: String, score: Double) {
        val clean = AiJsonUtils.cleanGraphicId(graphicId)
        if (clean.isNotBlank()) {
            graphicRelevanceScores[clean] = score
        }
    }

    /**
     * Formats a summary of candidate topics, outline sections, and table graphics
     * discovered so far when no section text or table content has been fetched yet.
     */
    fun buildDiscoveredInventorySummary(): String {
        val parts = mutableListOf<String>()
        if (outlineSections.isNotEmpty()) {
            parts.add("Discovered Section Outlines (call getTopicSectionsText with these IDs):")
            val orderedOutlineTopics = topicDiscoveryOrder.filter { outlineSections.containsKey(it) }
                .ifEmpty { outlineSections.keys.toList() }
            orderedOutlineTopics.forEach { tid ->
                val sMap = outlineSections[tid] ?: return@forEach
                val title = topicTitles[tid] ?: tid
                val itemsStr = sMap.entries.take(70).joinToString(", ") { "${it.key} (${it.value})" }
                parts.add("- Topic [$tid] '$title': $itemsStr")
            }
        }
        val uninspectedTopics = topicDiscoveryOrder.filter { !outlineSections.containsKey(it) && topicTitles.containsKey(it) }
            .ifEmpty { topicTitles.keys.filter { !outlineSections.containsKey(it) } }
        if (uninspectedTopics.isNotEmpty()) {
            parts.add("Discovered Candidate Topics (call getTopicOverview with a topicId):")
            uninspectedTopics.take(8).forEach { tid ->
                val title = topicTitles[tid] ?: tid
                parts.add("- [$tid] $title")
            }
        }
        val orderedTables = graphicDiscoveryOrder.filter { graphicTitles.containsKey(it) }
            .ifEmpty {
                graphicTitles.keys.filter { !it.startsWith("Graphic-", ignoreCase = true) }
            }
        if (orderedTables.isNotEmpty()) {
            parts.add("Discovered Tables (call getGraphicContent with a graphicId):")
            orderedTables.take(25).forEach { gid ->
                val title = graphicTitles[gid] ?: "Table $gid"
                parts.add("- [$gid] $title")
            }
        }
        return if (parts.isNotEmpty()) {
            parts.joinToString("\n")
        } else {
            SystemPrompt.NOTHING_RETRIEVED_PLACEHOLDER
        }
    }

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

        // Deduplication: if same tool+args already executed and succeeded, skip
        val parsedArgs = AiJsonUtils.normalizeArgs(parseArguments(args))
        val argsKey = if (parsedArgs.isNotEmpty()) {
            parsedArgs.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
        } else {
            args.trim()
        }
        val logicalSuccess = success && !isLogicalFailure(result)

        synchronized(stateLock) {
            val alreadyExecuted = executedToolCalls.any { (name, cachedArgs) ->
                name == canonicalName && cachedArgs == argsKey
            }
            if (alreadyExecuted) return
            if (logicalSuccess) {
                executedToolCalls.add(canonicalName to argsKey)
            }

            toolCalls.add(
                ToolCallRecord(
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
                    "getTopicSectionsText" -> parseBatchSectionResult(parsedArgs, result)
                    "getGraphicContent" -> parseGraphicResult(parsedArgs, result)
                }
            }
        }
    }

    /** The question that opened this turn, as supplied by the caller. */
    val question: String get() = userQuestion

    fun buildTurnContext(answer: String): TurnContext {
        val cleanAnswer = ReasoningTokenSanitizer.stripReasoning(answer)

        // Populate topicTitle and sectionTitle on fetched sections from accumulated state or resolvers
        val sectionsWithTitle = fetchedSections.map { sec ->
            val resolvedTitle = resolveTopicTitle(sec.topicId, sec.topicTitle)
            val resolvedSectionTitle = resolveSectionTitle(sec.topicId, sec.sectionId, sec.sectionTitle)
            val score = sectionRelevanceScores["${sec.topicId}:${sec.sectionId}"] ?: sec.relevanceScore
            sec.copy(topicTitle = resolvedTitle, sectionTitle = resolvedSectionTitle, relevanceScore = score)
        }

        // Auto-populate refs from fetched sections and graphic IDs
        val validSections = sectionsWithTitle.filter { it.sectionTitle.isNotEmpty() }
        val candidateSections = if (validSections.isNotEmpty()) validSections else sectionsWithTitle
        val autoTopicRefs = candidateSections
            .distinctBy { it.topicId to it.sectionId }
            .map { sec ->
                val label = sec.sectionTitle.ifBlank { sec.topicTitle }
                val cleanSectionId = if (sec.sectionId.equals("FULL", ignoreCase = true)) "" else sec.sectionId
                TopicRef(
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
                    TopicRef(
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
            GraphicRef(gid, title, parentTopicId)
        }

        val resolvedGraphics = fetchedGraphics.map { fg ->
            val resolvedTitle = fg.title.ifBlank { graphicTitles[fg.graphicId] ?: "" }
            val resolvedTopic = fg.topicId.ifBlank { graphicToTopic[fg.graphicId] ?: "" }
            val resolvedTopicTitle = resolveTopicTitle(resolvedTopic)
            val score = graphicRelevanceScores[fg.graphicId] ?: fg.relevanceScore
            fg.copy(title = resolvedTitle, topicId = resolvedTopic, topicTitle = resolvedTopicTitle, relevanceScore = score)
        }

        return TurnContext(
            toolCalls = toolCalls.toList(),
            answer = cleanAnswer,
            toolResults = toolResults.toList(),
            fetchedSections = sectionsWithTitle,
            fetchedGraphics = resolvedGraphics,
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
                    if (!topicDiscoveryOrder.contains(item.id)) {
                        topicDiscoveryOrder.add(item.id)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseSearchResults: ${e.message}")
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
        var respTopicTitle = ""
        var fullMarkdown = result
        try {
            val unquoted = AiJsonUtils.extractJsonString(result)
            val res = json.decodeFromString<TopicSectionsResponse>(unquoted)
            if (res.topicId.isNotBlank()) {
                topicId = res.topicId
                lastTopicId = res.topicId
            }
            if (topicId.isNotBlank() && !topicDiscoveryOrder.contains(topicId)) {
                topicDiscoveryOrder.add(topicId)
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

            val sectionMap = outlineSections[topicId]?.toMutableMap() ?: mutableMapOf()
            for ((k, v) in res.sectionTitles) {
                val titleStr = AiJsonUtils.cleanSectionTitle(v)
                if (titleStr.isNotBlank()) {
                    sectionMap[k] = titleStr
                }
                if (k.isNotBlank() && k !in sectionIds) {
                    sectionIds.add(k)
                }
            }
            if (topicId.isNotEmpty() && sectionMap.isNotEmpty()) {
                outlineSections[topicId] = sectionMap
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseBatchSectionResult: ${e.message}")
        }

        if (sectionIds.isEmpty() && (fullMarkdown.contains("=== Section: FULL ===") || fullMarkdown.contains("=== Calculator:"))) {
            sectionIds.add("FULL")
        }

        val sectionMap = outlineSections[topicId] ?: emptyMap()
        val finalTopicTitle = resolveTopicTitle(topicId, respTopicTitle)

        for (sectionId in sectionIds) {
            if (sectionId.isNotEmpty() && !hasFetchedSection(topicId, sectionId)) {
                val snippet = extractSectionText(fullMarkdown, sectionId)
                if (snippet.isNotEmpty()) {
                    val resolvedTitle = AiJsonUtils.cleanSectionTitle(
                        sectionMap[sectionId]
                            ?: sectionTitleResolver?.invoke(topicId, sectionId)
                            ?: args["sectionTitle"]
                            ?: args["section_title"]
                            ?: ""
                    )
                    val defaultScore = if (sectionId.equals("FULL", ignoreCase = true)) 3.0 else 0.0
                    val score = sectionRelevanceScores["$topicId:$sectionId"] ?: defaultScore
                    fetchedSections.add(
                        FetchedSection(
                            topicId = topicId,
                            topicTitle = finalTopicTitle,
                            sectionId = sectionId,
                            sectionTitle = resolvedTitle,
                            contentSnippet = snippet,
                            relevanceScore = score
                        )
                    )
                }
            }
        }
    }

    private fun extractSectionText(fullMarkdown: String, sectionId: String): String {
        if (sectionId.equals("FULL", ignoreCase = true)) return fullMarkdown.trim()
        val headerPattern = "=== Section: $sectionId ==="
        val startIndex = fullMarkdown.indexOf(headerPattern)
        if (startIndex == -1) {
            return if (fullMarkdown.contains("=== Section: ")) "" else fullMarkdown.trim()
        }
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
                if (cleanId.isNotBlank() && !graphicDiscoveryOrder.contains(cleanId)) {
                    graphicDiscoveryOrder.add(cleanId)
                }
                val title = res.title.trim().ifEmpty {
                    AiJsonUtils.GRAPHIC_TABLE_REGEX.find(res.markdown)?.groupValues?.get(1)?.trim().orEmpty()
                }
                if (title.isNotEmpty()) {
                    graphicTitles[cleanId] = title
                    if (graphicId != cleanId) {
                        graphicTitles[graphicId] = title
                    }
                }
                val markdown = res.markdown.trim()
                if (markdown.isNotEmpty() && fetchedGraphics.none { it.graphicId == cleanId }) {
                    val parentTopicId = graphicToTopic[graphicId] ?: graphicToTopic[cleanId] ?: ""
                    fetchedGraphics.add(
                        FetchedGraphic(
                            graphicId = cleanId,
                            title = title.ifEmpty { graphicTitles[cleanId] ?: "" },
                            markdown = markdown,
                            topicId = parentTopicId
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseGraphicResult: ${e.message}")
            if (graphicIdFromArgs.isNotEmpty()) {
                val cleanId = AiJsonUtils.cleanGraphicId(graphicIdFromArgs)
                graphicIds.add(cleanId)
                if (cleanId.isNotBlank() && !graphicDiscoveryOrder.contains(cleanId)) {
                    graphicDiscoveryOrder.add(cleanId)
                }
                val match = AiJsonUtils.GRAPHIC_TABLE_REGEX.find(unquoted)
                val title = match?.groupValues?.get(1)?.trim().orEmpty()
                if (title.isNotEmpty()) {
                    graphicTitles[cleanId] = title
                    if (graphicIdFromArgs != cleanId) {
                        graphicTitles[graphicIdFromArgs] = title
                    }
                }
                val markdown = unquoted.trim()
                if (markdown.isNotEmpty() && fetchedGraphics.none { it.graphicId == cleanId }) {
                    val parentTopicId = graphicToTopic[graphicIdFromArgs] ?: graphicToTopic[cleanId] ?: ""
                    fetchedGraphics.add(
                        FetchedGraphic(
                            graphicId = cleanId,
                            title = title.ifEmpty { graphicTitles[cleanId] ?: "" },
                            markdown = markdown,
                            topicId = parentTopicId
                        )
                    )
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
            if (topicId.isNotBlank() && !topicDiscoveryOrder.contains(topicId)) {
                topicDiscoveryOrder.add(topicId)
            }

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
                if (!g.isTable) continue
                val gid = g.id
                val cleanGid = AiJsonUtils.cleanGraphicId(gid)
                graphicToTopic[gid] = topicId
                graphicToTopic[cleanGid] = topicId
                if (g.title.isNotBlank()) {
                    graphicTitles[cleanGid] = g.title
                    if (gid != cleanGid) {
                        graphicTitles[gid] = g.title
                    }
                }
                if (!graphicDiscoveryOrder.contains(cleanGid)) {
                    graphicDiscoveryOrder.add(cleanGid)
                }
            }

            // Calculator topic evidence
            val finalTopicTitle = resolveTopicTitle(topicId, title)
            if (res.topicType == "calc" && !hasFetchedSection(topicId, "FULL")) {
                val score = sectionRelevanceScores["$topicId:FULL"] ?: 3.0
                fetchedSections.add(
                    FetchedSection(
                        topicId = topicId,
                        topicTitle = finalTopicTitle,
                        sectionId = "FULL",
                        sectionTitle = "Calculator Tool Content",
                        contentSnippet = res.markdown.trim(),
                        relevanceScore = score
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseTopicOverviewResult: ${e.message}")
        }
    }
}
