package com.clinref.app.domain.ai

import com.clinref.app.data.OutlineGraphicItem
import com.clinref.app.data.OutlineSectionItem
import com.clinref.app.data.RelatedTopicItem
import com.clinref.app.data.SearchResultTopicItem
import com.clinref.app.data.TopicOverviewResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
enum class DecisionMode {
    OFF,
    RERANK
}

@Serializable
data class DecisionConfig(
    val enableRetrievalRerank: Boolean = true,
    val autoPrefetch: Boolean = false,
    val sectionScoreThreshold: Double = DEFAULT_SECTION_THRESHOLD,
    val tableScoreThreshold: Double = DEFAULT_TABLE_THRESHOLD,
    val topicScoreThreshold: Double = DEFAULT_TOPIC_THRESHOLD,
    val topKSections: Int = DEFAULT_TOP_K_SECTIONS,
    val topKTables: Int = DEFAULT_TOP_K_TABLES,
    val topKTopics: Int = DEFAULT_TOP_K_TOPICS,
    val searchDrugMonographThreshold: Double = DEFAULT_SEARCH_DRUG_THRESHOLD,
    val overviewDrugMonographThreshold: Double = DEFAULT_OVERVIEW_DRUG_THRESHOLD,
    val tableDataThreshold: Double = DEFAULT_TABLE_DATA_THRESHOLD
) {
    companion object {
        const val MAX_OUTLINE_SECTIONS_TO_SCORE = 70
        const val MAX_TABLE_GRAPHICS_TO_SCORE = 25
        const val MAX_RELATED_TOPICS_TO_SCORE = 25
        const val DEFAULT_SECTION_THRESHOLD = 1.8
        const val DEFAULT_TABLE_THRESHOLD = 1.7
        const val DEFAULT_TOPIC_THRESHOLD = 1.5
        const val DEFAULT_TOP_K_SECTIONS = 8
        const val DEFAULT_TOP_K_TABLES = 4
        const val DEFAULT_TOP_K_TOPICS = 3
        const val DEFAULT_SEARCH_DRUG_THRESHOLD = 0.70
        const val DEFAULT_OVERVIEW_DRUG_THRESHOLD = 0.75
        const val DEFAULT_TABLE_DATA_THRESHOLD = 0.70
    }
}

@Serializable
data class RankedItem(
    val id: String,
    val title: String,
    val score: Double,
    val confidence: Double = 0.0,
    val kind: String = "",
    val topLevel: Int = 0
)

@Serializable
data class SearchRerankResult(
    val rankedTopics: List<RankedItem> = emptyList(),
    val topTopics: List<RankedItem> = emptyList(),
    val requiresDrugMonograph: Boolean = false,
    val drugMonographProb: Double = 0.0,
    val requiresTableData: Boolean = false,
    val tableDataProb: Double = 0.0,
    val ok: Boolean = true,
    val error: String? = null,
    val usage: DecisionUsage = DecisionUsage(),
    val latencyMs: Double = 0.0
)

@Serializable
data class OverviewRerankResult(
    val topicId: String,
    val topicTitle: String,
    val rankedSections: List<RankedItem> = emptyList(),
    val rankedGraphics: List<RankedItem> = emptyList(),
    val rankedRelatedTopics: List<RankedItem> = emptyList(),
    val recommendedSectionIds: List<String> = emptyList(),
    val recommendedGraphicIds: List<String> = emptyList(),
    val recommendedRelatedTopics: List<RankedItem> = emptyList(),
    val needsDrugMonograph: Boolean = false,
    val drugMonographProb: Double = 0.0,
    val ok: Boolean = true,
    val error: String? = null,
    val usage: DecisionUsage = DecisionUsage(),
    val latencyMs: Double = 0.0
)

/**
 * Phase-1 retrieval reranking and triage gates powered by Gemini Decisions (`gemini-decision-1`).
 */
object DecisionGates {

    val RELEVANCE_RUBRIC: List<String> = listOf(
        "Irrelevant: unrelated to the question or patient.",
        "Tangential: background only; does not answer inquiry.",
        "Helpful context: workup, monitoring, or secondary detail.",
        "Essential: direct answer, exact dose, threshold, or first-line rule."
    )

    private val BOILERPLATE_SECTION_TITLES = setOf(
        "references",
        "society guideline links",
        "information for patients",
        "patient information",
        "topic outline",
        "contributors",
        "author and editor disclosures",
        "disclosures"
    )

    fun isBoilerplateSection(title: String): Boolean {
        val clean = title.trim().lowercase()
        return clean in BOILERPLATE_SECTION_TITLES
    }

    /**
     * Eliminates redundant parent/child section pairs from candidate section IDs.
     * When an ancestor or descendant is already chosen, skips duplicate coverage.
     */
    fun filterSubsumedSections(
        candidateSectionIds: List<String>,
        allSections: List<OutlineSectionItem>
    ): List<String> {
        if (candidateSectionIds.size <= 1) return candidateSectionIds
        val parentMap = allSections.associate { it.id to it.parentId }
        val descendantsMap = mutableMapOf<String, MutableSet<String>>()
        for (sec in allSections) {
            var p = sec.parentId
            while (p != null) {
                descendantsMap.getOrPut(p) { mutableSetOf() }.add(sec.id)
                p = parentMap[p]
            }
        }

        val result = mutableListOf<String>()
        val resultSet = mutableSetOf<String>()

        for (sid in candidateSectionIds) {
            var hasAncestor = false
            var p = parentMap[sid]
            while (p != null) {
                if (p in resultSet) {
                    hasAncestor = true
                    break
                }
                p = parentMap[p]
            }
            if (hasAncestor) continue

            val descendants = descendantsMap[sid]
            val hasDescendant = descendants != null && descendants.any { it in resultSet }
            if (hasDescendant) continue

            result.add(sid)
            resultSet.add(sid)
        }
        return result
    }

    suspend fun rankSearchCandidates(
        client: DecisionClient,
        question: String,
        patientProfileText: String,
        query: String,
        candidates: List<SearchResultTopicItem>,
        config: DecisionConfig = DecisionConfig()
    ): SearchRerankResult {
        if (!config.enableRetrievalRerank || candidates.isEmpty()) {
            return SearchRerankResult(ok = true)
        }

        val candidateLines = candidates.take(DecisionClient.MAX_QUESTIONS_PER_REQUEST - 2)
            .joinToString("\n") { "- [${it.id}] ${it.title}" }
        val blocks = makeTextBlocks(
            listOfNotNull(
                "QUESTION:\n${question.trim()}",
                patientProfileText.takeIf { it.isNotBlank() }?.let { "PATIENT PROFILE:\n${it.trim()}" },
                "SEARCH QUERY:\n${query.trim()}",
                "SEARCH CANDIDATES:\n$candidateLines"
            )
        )

        val questions = LinkedHashMap<String, DecisionQuestion>(candidates.size + 2)
        questions["requires_drug_monograph"] = noulQuestion(
            instructions = "Does answering this clinical question accurately require consulting a dedicated drug dosing / Lexidrug monograph topic?",
            trueDescription = "Requires specific drug dosing, renal/hepatic adjustment, formulation, boxed warnings, or drug interaction monograph details.",
            falseDescription = "Can be answered from a disease/management topic or general clinical overview without a separate drug monograph."
        )
        questions["requires_table_data"] = noulQuestion(
            instructions = "Does answering this question require checking structured clinical tables (e.g., risk scores, grading criteria, comparative drug regimens, or diagnostic thresholds)?",
            trueDescription = "Structured comparison tables, dosing tables, classification criteria, or diagnostic scorecards are important.",
            falseDescription = "Narrative section prose alone is sufficient without table lookup."
        )

        val keyToCandidate = ArrayList<Pair<String, SearchResultTopicItem>>(candidates.size)
        val maxCandidateQuestions = DecisionClient.MAX_QUESTIONS_PER_REQUEST - 2
        candidates.take(maxCandidateQuestions).forEachIndexed { idx, candidate ->
            val key = uniqueKey(
                existing = questions,
                raw = "topic_${idx}_${candidate.id}",
                prefix = "topic",
                index = idx
            )
            keyToCandidate.add(key to candidate)
            questions[key] = scoreQuestion(
                instructions = "Rate how relevant candidate topic '${candidate.title}' (id=${candidate.id}, type=${candidate.type}) is to answering the clinician's question for this patient.",
                criteria = RELEVANCE_RUBRIC
            )
        }

        val batch = client.decide(input = blocks, questions = questions)
        if (!batch.ok) {
            return SearchRerankResult(
                ok = false,
                error = batch.error,
                usage = batch.usage,
                latencyMs = batch.latencyMs
            )
        }

        val drugProb = batch.getNoul("requires_drug_monograph")?.noul ?: 0.0
        val tableProb = batch.getNoul("requires_table_data")?.noul ?: 0.0

        val ranked = keyToCandidate.mapNotNull { (key, candidate) ->
            val scoreAns = batch.getScore(key) ?: return@mapNotNull null
            RankedItem(
                id = candidate.id,
                title = candidate.title,
                score = scoreAns.score,
                confidence = scoreAns.confidence,
                kind = candidate.type,
                topLevel = scoreAns.topLevel.first
            )
        }.sortedWith(compareByDescending<RankedItem> { it.score }.thenByDescending { it.confidence })

        val aboveThreshold = ranked
            .filter { it.score >= config.topicScoreThreshold }
            .take(config.topKTopics)
        val topTopics = if (aboveThreshold.isNotEmpty()) {
            aboveThreshold
        } else {
            ranked.take(minOf(2, ranked.size))
        }

        return SearchRerankResult(
            rankedTopics = ranked,
            topTopics = topTopics,
            requiresDrugMonograph = drugProb >= config.searchDrugMonographThreshold,
            drugMonographProb = drugProb,
            requiresTableData = tableProb >= config.tableDataThreshold,
            tableDataProb = tableProb,
            ok = true,
            usage = batch.usage,
            latencyMs = batch.latencyMs
        )
    }

    suspend fun rankOverviewItems(
        client: DecisionClient,
        question: String,
        patientProfileText: String,
        overview: TopicOverviewResponse,
        config: DecisionConfig = DecisionConfig()
    ): OverviewRerankResult {
        if (!config.enableRetrievalRerank ||
            overview.topicType.equals("calc", ignoreCase = true)
        ) {
            return OverviewRerankResult(
                topicId = overview.topicId,
                topicTitle = overview.title,
                ok = true
            )
        }

        val sectionsToScore = overview.sections
            .filter { it.id.isNotBlank() && !isBoilerplateSection(it.title) }
            .take(DecisionConfig.MAX_OUTLINE_SECTIONS_TO_SCORE)
        val tableGraphicsToScore = overview.graphics
            .filter { it.id.isNotBlank() && it.isTable }
            .take(DecisionConfig.MAX_TABLE_GRAPHICS_TO_SCORE)
        val relatedToScore = overview.relatedTopics
            .filter { it.id.isNotBlank() }
            .take(DecisionConfig.MAX_RELATED_TOPICS_TO_SCORE)

        if (sectionsToScore.isEmpty() && tableGraphicsToScore.isEmpty()) {
            return OverviewRerankResult(
                topicId = overview.topicId,
                topicTitle = overview.title,
                ok = true
            )
        }

        val h1Headings = overview.sections.filter { it.level == 1 }.take(15)
        val h1Digest = if (h1Headings.isNotEmpty()) {
            "\n\nMajor Heading Outline:\n" + h1Headings.joinToString("\n") { "- ${it.title}" }
        } else ""

        val overviewDigest = "OPEN TOPIC:\n[${overview.topicId}] ${overview.title} (type=${overview.topicType.ifBlank { "topic" }})$h1Digest"

        val blocks = makeTextBlocks(
            listOfNotNull(
                "QUESTION:\n${question.trim()}",
                patientProfileText.takeIf { it.isNotBlank() }?.let { "PATIENT PROFILE:\n${it.trim()}" },
                overviewDigest
            )
        )

        val questions = LinkedHashMap<String, DecisionQuestion>()
        questions["is_topic_relevant"] = noulQuestion(
            instructions = "Is topic '${overview.title}' relevant to answering the clinical question?",
            trueDescription = "This topic contains clinical information pertinent to the inquiry.",
            falseDescription = "This topic is unrelated or off-target for the clinical question."
        )

        val isDrugTopic = overview.topicType.equals("drug", ignoreCase = true)
        if (!isDrugTopic) {
            questions["needs_drug_monograph"] = noulQuestion(
                instructions = "While inspecting topic '${overview.title}', does the question also require a separate dedicated drug dosing monograph (renal dosing, interactions, formulation, or pediatric weight dosing)?",
                trueDescription = "A dedicated drug monograph is needed in addition to this disease/management topic.",
                falseDescription = "This topic (or its sections/tables) likely contains sufficient information without an external drug monograph."
            )
        }

        val sectionTitlesById = overview.sections.associateBy({ it.id }, { it.title })
        val secKeyToItem = ArrayList<Pair<String, OutlineSectionItem>>(sectionsToScore.size)
        sectionsToScore.forEachIndexed { idx, sec ->
            val key = uniqueKey(questions, "sec_${idx}_${sec.id}", "sec", idx)
            secKeyToItem.add(key to sec)
            val parentTitle = sec.parentId?.let { sectionTitlesById[it] }?.takeIf { it.isNotBlank() }
            val hierarchicalLabel = if (parentTitle != null) "$parentTitle > ${sec.title}" else sec.title
            questions[key] = scoreQuestion(
                instructions = "Rate relevance of section '$hierarchicalLabel' (ID ${sec.id}, H${sec.level}) to the clinical question.",
                criteria = RELEVANCE_RUBRIC
            )
        }

        val gfxKeyToItem = ArrayList<Pair<String, OutlineGraphicItem>>(tableGraphicsToScore.size)
        tableGraphicsToScore.forEachIndexed { idx, gfx ->
            val key = uniqueKey(questions, "gfx_${idx}_${gfx.id}", "gfx", idx)
            gfxKeyToItem.add(key to gfx)
            questions[key] = scoreQuestion(
                instructions = "Rate relevance of clinical table '${gfx.title}' (ID ${gfx.id}) to the clinical question.",
                criteria = RELEVANCE_RUBRIC
            )
        }

        val relKeyToItem = ArrayList<Pair<String, RelatedTopicItem>>(relatedToScore.size)
        relatedToScore.forEachIndexed { idx, rel ->
            val key = uniqueKey(questions, "rel_${idx}_${rel.id}", "rel", idx)
            relKeyToItem.add(key to rel)
            questions[key] = scoreQuestion(
                instructions = "Rate relevance of linked topic '${rel.title}' (ID ${rel.id}) to the clinical question.",
                criteria = RELEVANCE_RUBRIC
            )
        }

        val batch = client.decide(input = blocks, questions = questions)
        if (!batch.ok) {
            return OverviewRerankResult(
                topicId = overview.topicId,
                topicTitle = overview.title,
                ok = false,
                error = batch.error,
                usage = batch.usage,
                latencyMs = batch.latencyMs
            )
        }

        val isTopicRelevant = batch.getNoul("is_topic_relevant")?.noul ?: 1.0

        val drugProb = if (!isDrugTopic) {
            batch.getNoul("needs_drug_monograph")?.noul ?: 0.0
        } else {
            0.0
        }

        val rankedSections = extractRankedItems(secKeyToItem, batch, kind = "section") { it.id to it.title }
        val rankedGraphics = extractRankedItems(gfxKeyToItem, batch, kind = "table") { it.id to it.title }
        val rankedRelated = extractRankedItems(relKeyToItem, batch, kind = "related_topic") { it.id to it.title }

        val sectionsPassing = rankedSections
            .filter { it.score >= config.sectionScoreThreshold }
            .map { it.id }

        val deduplicatedPassing = filterSubsumedSections(sectionsPassing, overview.sections)
            .take(config.topKSections)

        val recommendedSectionIds = if (deduplicatedPassing.isNotEmpty()) {
            deduplicatedPassing
        } else if (isTopicRelevant >= 0.35 && rankedSections.firstOrNull()?.let { it.score >= 1.0 && it.confidence >= 0.50 } == true) {
            val fallbackCandidates = rankedSections.take(minOf(3, rankedSections.size)).map { it.id }
            filterSubsumedSections(fallbackCandidates, overview.sections)
        } else {
            emptyList()
        }

        val recommendedGraphicIds = rankedGraphics
            .filter { it.score >= config.tableScoreThreshold }
            .take(config.topKTables)
            .map { it.id }

        val recommendedRelated = rankedRelated
            .filter { it.score >= config.topicScoreThreshold }
            .take(config.topKTopics)

        return OverviewRerankResult(
            topicId = overview.topicId,
            topicTitle = overview.title,
            rankedSections = rankedSections,
            rankedGraphics = rankedGraphics,
            rankedRelatedTopics = rankedRelated,
            recommendedSectionIds = recommendedSectionIds,
            recommendedGraphicIds = recommendedGraphicIds,
            recommendedRelatedTopics = recommendedRelated,
            needsDrugMonograph = !isDrugTopic && drugProb >= config.overviewDrugMonographThreshold,
            drugMonographProb = drugProb,
            ok = true,
            usage = batch.usage,
            latencyMs = batch.latencyMs
        )
    }

    /**
     * Attaches `decisionRerank` hints to a serialized `searchTopics` JSON payload.
     */
    fun injectSearchRerankIntoJson(
        toolJson: String,
        rerank: SearchRerankResult,
        json: Json = AiJsonUtils.json
    ): String {
        if (!rerank.ok || (rerank.topTopics.isEmpty() && rerank.rankedTopics.isEmpty())) {
            return toolJson
        }
        val obj = parseSuccessObject(toolJson, json) ?: return toolJson

        val topSummary = rerank.topTopics.joinToString(", ") {
            "${it.id} ('${it.title}', score=${formatScore(it.score)})"
        }
        val hintParts = mutableListOf<String>()
        if (topSummary.isNotEmpty()) {
            hintParts.add("Decisions API top topics: $topSummary.")
        }
        if (rerank.requiresDrugMonograph) {
            hintParts.add("Dedicated drug dosing monograph likely needed.")
        }
        if (rerank.requiresTableData) {
            hintParts.add("Structured table data likely needed.")
        }

        val rerankObj = buildJsonObject {
            put(
                "topTopics",
                buildJsonArray {
                    rerank.topTopics.forEach { item ->
                        add(
                            buildJsonObject {
                                put("id", item.id)
                                put("title", item.title)
                                put("score", formatScore(item.score).toDouble())
                            }
                        )
                    }
                }
            )
            put("requiresDrugMonograph", rerank.requiresDrugMonograph)
            put("requiresTableData", rerank.requiresTableData)
            put("hint", hintParts.joinToString(" "))
        }

        return encodeWithRerank(obj, rerankObj, json)
    }

    /**
     * Attaches `decisionRerank` recommendations (and optional prefetched markdown) to a
     * serialized `getTopicOverview` JSON payload.
     */
    fun injectOverviewRerankIntoJson(
        toolJson: String,
        rerank: OverviewRerankResult,
        autoPrefetched: Boolean = false,
        prefetchedSectionsMarkdown: String? = null,
        prefetchedTablesMarkdown: String? = null,
        json: Json = AiJsonUtils.json
    ): String {
        if (!rerank.ok) return toolJson
        val obj = parseSuccessObject(toolJson, json) ?: return toolJson

        val hasAnyRecommendation = rerank.recommendedSectionIds.isNotEmpty() ||
            rerank.recommendedGraphicIds.isNotEmpty() ||
            rerank.recommendedRelatedTopics.isNotEmpty() ||
            rerank.needsDrugMonograph
        if (!hasAnyRecommendation && !autoPrefetched) {
            return toolJson
        }

        val hintParts = mutableListOf<String>()
        if (rerank.recommendedSectionIds.isNotEmpty()) {
            val secPrefix = if (autoPrefetched) {
                "Auto-prefetched top sections"
            } else {
                "Recommended sectionIds for getTopicSectionsText"
            }
            hintParts.add("$secPrefix: ${rerank.recommendedSectionIds.joinToString(", ")}.")
        } else if (rerank.rankedSections.isNotEmpty()) {
            hintParts.add("All sections in this topic scored low relevance (<1.0); check relatedTopics or refine searchTopics.")
        }
        if (rerank.recommendedGraphicIds.isNotEmpty()) {
            val gfxPrefix = if (autoPrefetched) {
                "Auto-prefetched tables"
            } else {
                "Recommended graphicIds for getGraphicContent"
            }
            hintParts.add("$gfxPrefix: ${rerank.recommendedGraphicIds.joinToString(", ")}.")
        }
        if (rerank.recommendedRelatedTopics.isNotEmpty()) {
            val relSummary = rerank.recommendedRelatedTopics.joinToString(", ") {
                "${it.id} (${it.title})"
            }
            hintParts.add("Promising related topics: $relSummary.")
        }
        if (rerank.needsDrugMonograph) {
            hintParts.add("Consider checking a dedicated Lexidrug monograph if exact dosing is required.")
        }
        if (autoPrefetched) {
            hintParts.add("Prefetched sections/tables are already added to retrieved evidence; only fetch additional sections if a gap remains.")
        }

        val rerankObj = buildJsonObject {
            put(
                "recommendedSectionIds",
                buildJsonArray { rerank.recommendedSectionIds.forEach { add(JsonPrimitive(it)) } }
            )
            put(
                "recommendedGraphicIds",
                buildJsonArray { rerank.recommendedGraphicIds.forEach { add(JsonPrimitive(it)) } }
            )
            put(
                "recommendedRelatedTopics",
                buildJsonArray {
                    rerank.recommendedRelatedTopics.forEach { item ->
                        add(
                            buildJsonObject {
                                put("id", item.id)
                                put("title", item.title)
                                put("score", formatScore(item.score).toDouble())
                            }
                        )
                    }
                }
            )
            put("needsDrugMonograph", rerank.needsDrugMonograph)
            if (autoPrefetched) {
                put("autoPrefetched", true)
                prefetchedSectionsMarkdown?.let { put("prefetchedSectionsMarkdown", it) }
                prefetchedTablesMarkdown?.let { put("prefetchedTablesMarkdown", it) }
                put("prefetchedSectionsChars", prefetchedSectionsMarkdown?.length ?: 0)
                put("prefetchedTablesChars", prefetchedTablesMarkdown?.length ?: 0)
            }
            put("hint", hintParts.joinToString(" "))
        }

        return encodeWithRerank(obj, rerankObj, json)
    }

    private fun <T> extractRankedItems(
        keyToItem: List<Pair<String, T>>,
        batch: DecisionBatchResult,
        kind: String,
        identity: (T) -> Pair<String, String>
    ): List<RankedItem> =
        keyToItem.mapNotNull { (key, item) ->
            val scoreAns = batch.getScore(key) ?: return@mapNotNull null
            val (id, title) = identity(item)
            RankedItem(
                id = id,
                title = title,
                score = scoreAns.score,
                confidence = scoreAns.confidence,
                kind = kind,
                topLevel = scoreAns.topLevel.first
            )
        }.sortedWith(compareByDescending<RankedItem> { it.score }.thenByDescending { it.confidence })

    private fun uniqueKey(
        existing: Map<String, DecisionQuestion>,
        raw: String,
        prefix: String,
        index: Int
    ): String {
        val base = sanitizeQuestionKey(raw, prefix = prefix, index = index)
        if (base !in existing) return base
        val suffix = "_$index"
        val candidate = base.take(DecisionClient.MAX_QUESTION_KEY_LEN - suffix.length) + suffix
        if (candidate !in existing) return candidate
        var counter = 0
        while (true) {
            val s = "_$counter"
            val alt = base.take(DecisionClient.MAX_QUESTION_KEY_LEN - s.length) + s
            if (alt !in existing) return alt
            counter++
        }
    }

    private fun parseSuccessObject(toolJson: String, json: Json): JsonObject? {
        return try {
            val obj = json.parseToJsonElement(toolJson) as? JsonObject ?: return null
            val error = obj["error"]?.jsonPrimitive?.contentOrNull
            if (!error.isNullOrBlank()) null else obj
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeWithRerank(
        original: JsonObject,
        rerankObj: JsonObject,
        json: Json
    ): String {
        val updated = buildJsonObject {
            original.forEach { (k, v) ->
                if (k != "decisionRerank") put(k, v)
            }
            put("decisionRerank", rerankObj)
        }
        return json.encodeToString(JsonObject.serializer(), updated)
    }

    private fun formatScore(score: Double): String {
        val rounded = kotlin.math.round(score * 100.0) / 100.0
        return rounded.toString()
    }
}
