package com.clinref.app.domain.ai

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
     * Parse tool args from Koog's toolArgs.toString() format.
     * Koog's handleEvents gives toolArgs as a Map, which .toString() renders as
     * "{key1=value1, key2=value2}" — NOT valid JSON. We parse this key=value format.
     */
    private fun parseArguments(argsStr: String): Map<String, String> {
        if (argsStr.isBlank() || argsStr == "{}") return emptyMap()

        val inner = argsStr.trim().removePrefix("{").removeSuffix("}")
        if (inner.isBlank()) return emptyMap()

        val result = mutableMapOf<String, String>()
        // Split on ", " then split each on first "="
        for (pair in inner.split(", ")) {
            val eqIdx = pair.indexOf('=')
            if (eqIdx > 0) {
                val key = pair.substring(0, eqIdx).trim()
                val value = pair.substring(eqIdx + 1).trim()
                result[key] = value
            }
        }
        return result
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
        // Result may be JSON or plain text. Extract graphic ID from args as primary source.
        val args = currentToolArgs?.let { parseArguments(it) } ?: emptyMap()
        val graphicId = args["graphicId"] ?: ""
        if (graphicId.isNotEmpty()) {
            graphicIds.add(graphicId)
        }
    }

    /**
     * Parse citations from the agent's answer text.
     *
     * IMPORTANT: This only works if the system prompt mandates a specific citation format.
     * The current system prompt says:
     *   "When citing sources, use this exact format:
     *    Topic: <topic title>, Section: <section title> (ID: <section id>)"
     *
     * If the model doesn't follow this format, citations will be empty and
     * SafetyValidator Rule 5 will block the answer. This is intentional —
     * we WANT to block answers that don't cite properly.
     */
    private fun parseCitations(answer: String): List<SafetyValidator.Citation> {
        val citations = mutableListOf<SafetyValidator.Citation>()

        // Primary pattern: matches the mandated format from system prompt
        // "Topic: X, Section: Y (ID: Z)"
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
