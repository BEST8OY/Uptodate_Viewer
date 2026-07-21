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

    // Maps toolCallId to arguments to support thread-safe concurrent tool runs
    private val pendingToolArgs = ConcurrentHashMap<String, String>()

    private val json = Json { ignoreUnknownKeys = true }

    fun reset() {
        toolCalls.clear()
        toolResults.clear()
        fetchedSections.clear()
        graphicIds.clear()
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

        // Compute logical success from the result content, not from "non-null".
        // MedicalDatabaseTools returns sentinel strings for misses ("Section not found.",
        // "Topic not found", {"error":...}) rather than throwing — these are non-null
        // strings that should count as failures for validation purposes.
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
            toolResults = toolResults.toList(),
            fetchedSections = fetchedSections.toList(),
            graphicIds = graphicIds.toSet()
        )
    }

    /**
     * MedicalDatabaseTools never throws for a "not found" — it returns sentinel strings.
     * We match those exactly rather than broad "error" substring searches, since real
     * clinical text can legitimately contain words like "error" (e.g. "medication error").
     */
    private fun isLogicalFailure(result: String): Boolean {
        val trimmed = result.trim()
        if (trimmed.equals("Topic not found", ignoreCase = true)) return true
        if (trimmed.equals("Section not found.", ignoreCase = true)) return true
        if (trimmed.startsWith("{") && trimmed.contains("\"error\"")) return true
        return false
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
        val topicId = args["topicId"] ?: ""
        val sectionId = args["sectionId"] ?: ""
        val sectionTitle = args["sectionTitle"] ?: ""

        if (sectionId.isNotEmpty()) {
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

        // Anchored to line boundaries with MULTILINE so each citation is parsed from
        // its own line. The lookahead was added in the previous commit to prevent lazy
        // evaluation overrun, but without ^...$ anchoring, inline multi-citations on
        // one line (e.g. separated by ;) would still parse incorrectly.
        val citationPattern = Regex(
            """^\s*Topic:\s*(.+?),\s*Section:\s*(.+?)(?:\s*\(ID:\s*([a-zA-Z0-9_-]+)\))?\s*$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
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
