package com.clinref.app.domain.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

class TurnContextAccumulator {

    private val toolCalls = CopyOnWriteArrayList<SafetyValidator.ToolCallRecord>()
    private val toolResults = CopyOnWriteArrayList<String>()
    private val fetchedSections = CopyOnWriteArrayList<SafetyValidator.FetchedSection>()
    private val graphicIds = CopyOnWriteArraySet<String>()
    private var userQuestion: String = ""

    private val pendingToolArgs = ConcurrentHashMap<String, String>()
    private val json = Json { ignoreUnknownKeys = true }

    fun reset() {
        toolCalls.clear()
        toolResults.clear()
        fetchedSections.clear()
        graphicIds.clear()
        pendingToolArgs.clear()
        userQuestion = ""
    }

    fun setUserQuestion(question: String) {
        userQuestion = question
    }

    fun onToolCallStarting(toolCallId: String, args: String) {
        if (toolCallId.isNotBlank()) {
            pendingToolArgs[toolCallId] = args
        }
    }

    fun onToolCallCompleted(toolCallId: String, toolName: String, result: String, success: Boolean) {
        val args = pendingToolArgs.remove(toolCallId) ?: ""
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
                "getTopicSectionText" -> parseSectionResult(parsedArgs, result)
                "getGraphicContent" -> parseGraphicResult(parsedArgs)
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
            graphicIds = graphicIds.toSet(),
            userQuestion = userQuestion
        )
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

        val citationPattern = Regex(
            """(?:^|\n)\s*(?:[-*]|\d+\.)?\s*(?:\*\*)?Topic:(?:\*\*)?\s*(.+?),\s*(?:\*\*)?Section:(?:\*\*)?\s*(.+?)(?:\s*\(ID:\s*([a-zA-Z0-9_-]+)\))?(?:\*\*)?\s*(?=${'$'}|\n)""",
            RegexOption.IGNORE_CASE
        )

        for (match in citationPattern.findAll(answer)) {
            val topicTitle = match.groupValues[1].trim().removeSurrounding("**")
            val sectionTitle = match.groupValues[2].trim().removeSurrounding("**")
            val rawSectionId = match.groupValues[3].trim()

            citations.add(
                SafetyValidator.Citation(
                    topicTitle = topicTitle,
                    sectionTitle = sectionTitle,
                    sectionId = rawSectionId.ifEmpty { "unknown" },
                    topicId = ""
                )
            )
        }
        return citations
    }
}
