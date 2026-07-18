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

    /**
     * Parse tool args from Koog's toolArgs.toString().
     *
     * Koog's handleEvents gives toolArgs as a JsonObject (kotlinx.serialization).
     * Calling .toString() on a JsonObject produces valid JSON: {"key": "value", ...}
     * We parse this with kotlinx.serialization.
     */
    private fun parseArguments(argsStr: String): Map<String, String> {
        if (argsStr.isBlank() || argsStr == "{}") return emptyMap()

        return try {
            val element = json.parseToJsonElement(argsStr)
            val obj = element as? JsonObject ?: return emptyMap()
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
        val args = currentToolArgs?.let { parseArguments(it) } ?: emptyMap()
        val graphicId = args["graphicId"] ?: ""
        if (graphicId.isNotEmpty()) {
            graphicIds.add(graphicId)
        }
    }

    /**
     * Parse citations from the agent's answer text.
     *
     * The system prompt mandates:
     *   "Topic: <topic title>, Section: <section title> (ID: <section id>)"
     *
     * If the model doesn't follow this format, citations will be empty and
     * SafetyValidator Rule 5 will block the answer. This is intentional.
     */
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

        return citations
    }
}
