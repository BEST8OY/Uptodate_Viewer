package com.clinref.app.domain.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Buffers tool call events during a single agent run and builds
 * a [SafetyValidator.TurnContext] when the run completes.
 *
 * Thread-safe implementation supporting concurrent tool executions.
 */
class TurnContextAccumulator {

    private val toolCalls = CopyOnWriteArrayList<SafetyValidator.ToolCallRecord>()
    private val toolResults = CopyOnWriteArrayList<String>()
    private val fetchedSections = CopyOnWriteArrayList<SafetyValidator.FetchedSection>()
    private val graphicIds = CopyOnWriteArraySet<String>()
    private val sectionWarnings = ConcurrentHashMap<String, Boolean>()

    // Maps toolCallId to arguments to support thread-safe concurrent tool runs
    private val pendingToolArgs = ConcurrentHashMap<String, String>()

    private val json = Json { ignoreUnknownKeys = true }

    fun reset() {
        toolCalls.clear()
        toolResults.clear()
        fetchedSections.clear()
        graphicIds.clear()
        sectionWarnings.clear()
        pendingToolArgs.clear()
    }

    fun onToolCallStarting(toolCallId: String, args: String) {
        if (toolCallId.isNotBlank()) {
            pendingToolArgs[toolCallId] = args
        }
    }

    fun onToolCallCompleted(toolCallId: String, toolName: String, result: String, success: Boolean) {
        val args = pendingToolArgs.remove(toolCallId) ?: ""
        val parsedArgs = parseArguments(args)

        toolCalls.add(
            SafetyValidator.ToolCallRecord(
                toolName = toolName,
                arguments = parsedArgs,
                result = result,
                success = success
            )
        )
        toolResults.add(result)

        // Drop mock successful returns that represent lookup failures
        val isLookupFailure = result.startsWith("Section not found", ignoreCase = true) ||
                              result.startsWith("Topic not found", ignoreCase = true) ||
                              result.startsWith("{\"error\"", ignoreCase = true)

        if (!isLookupFailure) {
            when (toolName) {
                "getTopicSectionText" -> parseSectionResult(parsedArgs, result)
                "getGraphicInfo" -> parseGraphicResult(parsedArgs)
            }
        }
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

    private fun parseSectionResult(args: Map<String, String>, result: String) {
        val hasWarning = result.contains("[WARNING", ignoreCase = true)
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

    private fun parseGraphicResult(args: Map<String, String>) {
        val graphicId = args["graphicId"] ?: ""
        if (graphicId.isNotEmpty()) {
            graphicIds.add(graphicId)
        }
    }

    private fun parseCitations(answer: String): List<SafetyValidator.Citation> {
        val citations = mutableListOf<SafetyValidator.Citation>()

        // Lookahead assertion prevents lazy evaluation overrun past other citation headings
        val citationPattern = Regex(
            """Topic:\s*([^,]+),\s*Section:\s*(.+?)(?:\s*\(ID:\s*([a-zA-Z0-9_-]+)\))?(?=\s*(?:Topic:|\n|\Z))""",
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
