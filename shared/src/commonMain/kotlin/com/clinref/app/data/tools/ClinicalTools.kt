@file:OptIn(ExperimentalSerializationApi::class)

package com.clinref.app.data.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.clinref.app.data.GraphicContentResponse
import com.clinref.app.data.MedicalDatabaseTools
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
        @property:LLMDescription("Exact numeric topic ID string returned by searchTopics (e.g., '12345').")
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
        @JsonNames("search_query", "searchQuery")
        val query: String
    )

    override suspend fun execute(args: Args): SearchTopicsResponse =
        databaseTools.searchTopics(args.query)
}

class GetTopicSectionsTextTool(
    private val databaseTools: MedicalDatabaseTools
) : Tool<GetTopicSectionsTextTool.Args, TopicSectionsResponse>(
    argsType = typeToken<Args>(),
    resultType = typeToken<TopicSectionsResponse>(),
    name = "getTopicSectionsText",
    description = "Step 3 (Mandatory for clinical answers): Retrieve full, unredacted clinical text for up to ${com.clinref.app.data.MAX_SECTIONS_PER_CALL} sectionIds within a single topicId. " +
        "Select sections covering dosing, first-line and alternative regimens (especially if patient allergies exist), and renal/hepatic adjustments. " +
        "Up to ${com.clinref.app.data.MAX_SECTIONS_PER_CALL} sections are returned in full; extras are listed in omittedSections."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Exact numeric topic ID string.")
        @JsonNames("topic_id", "topicId")
        val topicId: String,
        @property:LLMDescription("Array of 1 to ${com.clinref.app.data.MAX_SECTIONS_PER_CALL} section ID strings copied from getTopicOverview (e.g., ['H12', 'H15']).")
        @JsonNames("section_ids", "sectionIds")
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
        @JsonNames("graphic_id", "graphicId")
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
