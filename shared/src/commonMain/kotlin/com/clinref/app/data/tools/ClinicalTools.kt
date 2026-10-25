@file:OptIn(ExperimentalSerializationApi::class)

package com.clinref.app.data.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.clinref.app.data.GraphicContentResponse
import com.clinref.app.data.MedicalDatabaseTools
import com.clinref.app.data.RelatedTopicsResponse
import com.clinref.app.data.SearchTopicSectionsResponse
import com.clinref.app.data.SearchTopicsResponse
import com.clinref.app.data.TopicOverviewResponse
import com.clinref.app.data.TopicSectionsResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Native strongly-typed class-based Koog tools for clinical database access.
 * Zero reflection at runtime; full compile-time type safety for arguments and response schemas.
 * Field names support both camelCase and snake_case aliases via @JsonNames.
 */

class GetTopicOverviewTool(
    private val databaseTools: MedicalDatabaseTools
) : Tool<GetTopicOverviewTool.Args, TopicOverviewResponse>(
    argsType = typeToken<Args>(),
    resultType = typeToken<TopicOverviewResponse>(),
    name = "getTopicOverview",
    description = "Step 2 after searchTopics: Retrieve the complete section outline hierarchy (sectionIds and titles), available graphic_table metadata (graphicIds), and related topics for a topicId. " +
        "Note: Standard clinical topics return outline titles only—you MUST subsequently call getTopicSectionsText to read actual clinical text. " +
        "(Medical calculator topics return full markdown content directly)."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Exact numeric topic ID string returned by searchTopics or getRelatedTopics (e.g., '12345').")
        @JsonNames("topic_id", "topicId")
        val topicId: String
    )

    override suspend fun execute(args: Args): TopicOverviewResponse {
        val cleanTopicId = args.topicId.trim()
        if (cleanTopicId.isEmpty()) {
            return TopicOverviewResponse(
                error = "topicId cannot be blank. First execute searchTopics(query) to find valid candidate topic IDs."
            )
        }
        return databaseTools.getTopicOverview(cleanTopicId)
    }
}

class SearchTopicsTool(
    private val databaseTools: MedicalDatabaseTools
) : Tool<SearchTopicsTool.Args, SearchTopicsResponse>(
    argsType = typeToken<Args>(),
    resultType = typeToken<SearchTopicsResponse>(),
    name = "searchTopics",
    description = "Search the UpToDate index by a single medical condition, syndrome, or drug name (1–3 words max, e.g., 'apixaban', 'acute gout', 'community acquired pneumonia'). " +
        "Returns candidate {id, title} pairs and refine_with keywords. For multi-condition or multi-drug queries, invoke multiple searchTopics calls in parallel. " +
        "Never pass full sentences, patient ages, or lab values."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Core clinical entity or generic drug name (1–3 words, e.g., 'metformin', 'atrial fibrillation'). Do not include boolean operators, patient demographics, or punctuation.")
        @JsonNames("search_query", "q", "query_text")
        val query: String
    )

    override suspend fun execute(args: Args): SearchTopicsResponse =
        databaseTools.searchTopics(args.query)
}

class GetRelatedTopicsTool(
    private val databaseTools: MedicalDatabaseTools
) : Tool<GetRelatedTopicsTool.Args, RelatedTopicsResponse>(
    argsType = typeToken<Args>(),
    resultType = typeToken<RelatedTopicsResponse>(),
    name = "getRelatedTopics",
    description = "Retrieve cross-referenced topic IDs and titles linked to a primary topicId. " +
        "Use when a general disease topic links out to a dedicated drug-dosing or special-population sub-topic. " +
        "Follow with getTopicOverview and getTopicSectionsText on the target topic."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Exact numeric topic ID string from searchTopics or getTopicOverview.")
        @JsonNames("topic_id")
        val topicId: String
    )

    override suspend fun execute(args: Args): RelatedTopicsResponse {
        val cleanTopicId = args.topicId.trim()
        if (cleanTopicId.isEmpty()) {
            return RelatedTopicsResponse(error = "topicId cannot be blank for getRelatedTopics.")
        }
        return databaseTools.getRelatedTopics(cleanTopicId)
    }
}

class GetTopicSectionsTextTool(
    private val databaseTools: MedicalDatabaseTools
) : Tool<GetTopicSectionsTextTool.Args, TopicSectionsResponse>(
    argsType = typeToken<Args>(),
    resultType = typeToken<TopicSectionsResponse>(),
    name = "getTopicSectionsText",
    description = "Step 3 (Mandatory for clinical answers): Retrieve full, unredacted clinical text for up to 10 sectionIds within a single topicId. " +
        "Select sections covering dosing, first-line and alternative regimens (especially if patient allergies exist), and renal/hepatic adjustments. " +
        "Up to 10 sections are returned in full; extras are listed in omittedSections."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Exact numeric topic ID string.")
        @JsonNames("topic_id")
        val topicId: String,
        @property:LLMDescription("Array of 1 to 10 section ID strings copied from getTopicOverview or searchTopicSections (e.g., ['H12', 'H15']).")
        @JsonNames("section_ids")
        val sectionIds: List<String> = emptyList()
    )

    override suspend fun execute(args: Args): TopicSectionsResponse {
        val cleanTopicId = args.topicId.trim()
        if (cleanTopicId.isEmpty()) {
            return TopicSectionsResponse(
                error = "topicId cannot be blank. Provide the topicId obtained from searchTopics or getTopicOverview."
            )
        }
        return databaseTools.getTopicSectionsText(cleanTopicId, args.sectionIds)
    }
}

class GetGraphicContentTool(
    private val databaseTools: MedicalDatabaseTools
) : Tool<GetGraphicContentTool.Args, GraphicContentResponse>(
    argsType = typeToken<Args>(),
    resultType = typeToken<GraphicContentResponse>(),
    name = "getGraphicContent",
    description = "Retrieve full structured Markdown table content (e.g., empirical antibiotic tables, renal dosing adjustment tables, staging criteria) for an item whose type is 'graphic_table' in getTopicOverview. " +
        "Only call for 'graphic_table' items; skip figures, flowcharts, and images."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Exact graphic ID string from getTopicOverview for a 'graphic_table' item (e.g., 'Graphic-12345' or '12345').")
        @JsonNames("graphic_id")
        val graphicId: String
    )

    override suspend fun execute(args: Args): GraphicContentResponse {
        val cleanGraphicId = args.graphicId.trim()
        if (cleanGraphicId.isEmpty()) {
            return GraphicContentResponse(
                error = "graphicId cannot be blank. Inspect table graphics from getTopicOverview."
            )
        }
        return databaseTools.getGraphicContent(cleanGraphicId)
    }
}

class SearchTopicSectionsTool(
    private val databaseTools: MedicalDatabaseTools
) : Tool<SearchTopicSectionsTool.Args, SearchTopicSectionsResponse>(
    argsType = typeToken<Args>(),
    resultType = typeToken<SearchTopicSectionsResponse>(),
    name = "searchTopicSections",
    description = "Perform a targeted keyword search inside a single known topicId to locate specific section IDs and text snippets (e.g., 'renal', 'dosing', 'pregnancy', 'penicillin allergy'). " +
        "Use this on large topics when getTopicOverview has dozens of sections and you need to pinpoint exact sectionIds before calling getTopicSectionsText."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Exact numeric topic ID string.")
        @JsonNames("topic_id")
        val topicId: String,
        @property:LLMDescription("1–2 clinical keywords to locate within the topic (e.g., 'renal dose', 'contraindications').")
        @JsonNames("query", "search_query", "keyword")
        val query: String
    )

    override suspend fun execute(args: Args): SearchTopicSectionsResponse {
        val cleanTopicId = args.topicId.trim()
        val cleanQuery = args.query.trim()
        if (cleanTopicId.isEmpty() || cleanQuery.isEmpty()) {
            return SearchTopicSectionsResponse(
                error = "Both topicId and query are required to search sections within a topic."
            )
        }
        return databaseTools.searchTopicSections(cleanTopicId, cleanQuery)
    }
}
