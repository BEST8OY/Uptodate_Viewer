package com.clinref.app.domain.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Buffers tool call events during a single agent run and builds
 * a [SafetyValidator.TurnContext] when the run completes.
 *
 * Lifecycle: reset() -> onToolCallStarting x N -> onToolCallCompleted x N -> buildTurnContext()
 */
class TurnContextAccumulator {

    private val toolCalls = mutableListOf<SafetyValidator.ToolCallRecord>()
    private val toolResults = mutableListOf<String>()
    private val fetchedSections = mutableListOf<SafetyValidator.FetchedSection>()
    private val graphicIds = mutableSetOf<String>()
    private val sectionWarnings = mutableMapOf<String, Boolean>()

    private var currentToolName: String? = null
    private var currentToolArgs: String? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun reset() {
        toolCalls.clear()
        toolResults.clear()
        fetchedSections.clear()
        graphicIds.clear()
        sectionWarnings.clear()
        currentToolName = null
        currentToolArgs = null
    }

    fun onToolCallStarting(toolName: String, args: String) {
        currentToolName = toolName
        currentToolArgs = args
    }

    fun onToolCallCompleted(toolName: String, result: String, success: Boolean) {
        val args = currentToolArgs ?: ""
        toolCalls.add(
            SafetyValidator.ToolCallRecord(
                toolName = toolName,
                arguments = parseArguments(args),
                result = result,
                success = success
            )
        )
        toolResults.add(result)

        when (toolName) {
            "getTopicSectionText" -> parseSectionResult(result)
            "getGraphicInfo" -> parseGraphicResult(result)
        }

        currentToolName = null
        currentToolArgs = null
    }

    fun buildTurnContext(answer: String): SafetyValidator.TurnContext {
        return SafetyValidator.TurnContext(
            toolCalls = toolCalls.toList(),
            answer = answer,
            citations = parseCitations(answer),
            sectionWarnings = sectionWarnings.toMap(),
            toolResults = toolResults.toList(),
            fetchedSections = fetchedSections.toList(),
            graphicIds = graphicIds.toSet()
        )
    }

    private fun parseArguments(argsJson: String): Map<String, String> {
        return try {
            val obj = json.parseToJsonElement(argsJson) as? JsonObject ?: return emptyMap()
            obj.entries.associate { (key, value) ->
                key to value.jsonPrimitive.content
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parseSectionResult(result: String) {
        val hasWarning = result.contains("[WARNING", ignoreCase = true)
        val args = currentToolArgs?.let { parseArguments(it) } ?: emptyMap()
        val topicId = args["topicId"] ?: ""
        val sectionId = args["sectionId"] ?: ""
        val sectionTitle = args["sectionTitle"] ?: ""

        if (sectionId.isNotEmpty()) {
            sectionWarnings[sectionId] = hasWarning
            fetchedSections.add(
                SafetyValidator.FetchedSection(
                    topicId = topicId,
                    topicTitle = "",
                    sectionId = sectionId,
                    sectionTitle = sectionTitle
                )
            )
        }
    }

    private fun parseGraphicResult(result: String) {
        try {
            val obj = json.parseToJsonElement(result) as? JsonObject ?: return
            val id = obj["id"]?.jsonPrimitive?.content
            if (!id.isNullOrBlank()) {
                graphicIds.add(id)
            }
        } catch (_: Exception) {
            val args = currentToolArgs?.let { parseArguments(it) } ?: emptyMap()
            val graphicId = args["graphicId"] ?: ""
            if (graphicId.isNotEmpty()) {
                graphicIds.add(graphicId)
            }
        }
    }

    private fun parseCitations(answer: String): List<SafetyValidator.Citation> {
        val citations = mutableListOf<SafetyValidator.Citation>()

        val citationPattern = Regex(
            """Topic:\s*(.+?),\s*Section:\s*(.+?)(?:\s*\(ID:\s*(.+?)\))?""",
            RegexOption.IGNORE_CASE
        )
        for (match in citationPattern.findAll(answer)) {
            citations.add(
                SafetyValidator.Citation(
                    topicTitle = match.groupValues[1].trim(),
                    sectionTitle = match.groupValues[2].trim(),
                    sectionId = match.groupValues[3].trim().ifEmpty { "unknown" },
                    topicId = ""
                )
            )
        }

        val sourcePattern = Regex(
            """Source:\s*(.+?)\s*>\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        for (match in sourcePattern.findAll(answer)) {
            citations.add(
                SafetyValidator.Citation(
                    topicTitle = match.groupValues[1].trim(),
                    sectionTitle = match.groupValues[2].trim(),
                    sectionId = "unknown",
                    topicId = ""
                )
            )
        }

        return citations
    }
}
