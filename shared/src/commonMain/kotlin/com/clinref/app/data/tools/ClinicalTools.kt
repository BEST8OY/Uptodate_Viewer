@file:OptIn(ExperimentalSerializationApi::class)

package com.clinref.app.data.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.clinref.app.data.MedicalDatabaseTools
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Native class-based Koog tools for clinical database access.
 * Zero reflection at runtime; full compile-time type safety for arguments and schemas.
 * Field names support both camelCase and snake_case aliases via @JsonNames.
 */

class GetTopicOverviewTool(
    private val databaseTools: MedicalDatabaseTools
) : SimpleTool<GetTopicOverviewTool.Args>(
    argsType = typeToken<Args>(),
    name = "getTopicOverview",
    description = "Retrieve the complete topic outline hierarchy and graphic/related topic metadata (plus full markdown content for calculators). " +
        "For standard clinical articles, section text is not pre-fetched in the overview and must be retrieved on demand via getTopicSectionsText(topicId, sectionIds). " +
        "ALWAYS call this after searchTopics to obtain the topic outline and candidate section IDs."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The topic ID returned by searchTopics (e.g., '12345')")
        @JsonNames("topic_id", "topicId")
        val topicId: String
    )

    override suspend fun execute(args: Args): String = databaseTools.getTopicOverview(args.topicId)
}

class SearchTopicsTool(
    private val databaseTools: MedicalDatabaseTools
) : SimpleTool<SearchTopicsTool.Args>(
    argsType = typeToken<Args>(),
    name = "searchTopics",
    description = "Search medical topics by focused core keywords (e.g., 'apixaban', 'asthma', 'gout'). " +
        "Returns matching candidate topics ({id, title}) and 'refine_with' suggestions. " +
        "For multi-concept questions, execute separate searches per concept. " +
        "Avoid searching full patient sentences or lab measurements."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Single medical term or core clinical concept (e.g., 'asthma', 'metformin').")
        @JsonNames("search_query", "q", "query_text")
        val query: String
    )

    override suspend fun execute(args: Args): String = databaseTools.searchTopics(args.query)
}

class GetRelatedTopicsTool(
    private val databaseTools: MedicalDatabaseTools
) : SimpleTool<GetRelatedTopicsTool.Args>(
    argsType = typeToken<Args>(),
    name = "getRelatedTopics",
    description = "Get related topic IDs and titles for a topic. Use this to build a candidate pool before fetching sections."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The topic ID from searchTopics")
        @JsonNames("topic_id")
        val topicId: String
    )

    override suspend fun execute(args: Args): String = databaseTools.getRelatedTopics(args.topicId)
}

class GetTopicSectionsTextTool(
    private val databaseTools: MedicalDatabaseTools
) : SimpleTool<GetTopicSectionsTextTool.Args>(
    argsType = typeToken<Args>(),
    name = "getTopicSectionsText",
    description = "Retrieve multiple sections from the same topic in a single call, bounded by a token budget (default 4000 tokens)."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The topic ID")
        @JsonNames("topic_id")
        val topicId: String,
        @property:LLMDescription("List of section IDs to retrieve from getTopicOverview")
        @JsonNames("section_ids")
        val sectionIds: List<String> = emptyList(),
        @property:LLMDescription("Maximum tokens of section text to return (default 4000 tokens / ~16000 characters)")
        @JsonNames("max_tokens", "maxTokens")
        val maxTokens: Int = 4000
    )

    override suspend fun execute(args: Args): String = databaseTools.getTopicSectionsText(args.topicId, args.sectionIds, args.maxTokens)
}

class GetGraphicContentTool(
    private val databaseTools: MedicalDatabaseTools
) : SimpleTool<GetGraphicContentTool.Args>(
    argsType = typeToken<Args>(),
    name = "getGraphicContent",
    description = "Retrieve graphic content for a graphic of type 'graphic_table' as formatted Markdown. " +
        "Do NOT call for non-table graphics (figures, images, algorithms) as visual details cannot be analyzed."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The graphic ID from getTopicOverview (e.g., 'Graphic-12345' or '12345')")
        @JsonNames("graphic_id")
        val graphicId: String
    )

    override suspend fun execute(args: Args): String = databaseTools.getGraphicContent(args.graphicId)
}

class SearchTopicSectionsTool(
    private val databaseTools: MedicalDatabaseTools
) : SimpleTool<SearchTopicSectionsTool.Args>(
    argsType = typeToken<Args>(),
    name = "searchTopicSections",
    description = "Search sections within a specific topic by keywords (e.g., 'dosing', 'pregnancy', 'renal'). " +
        "Returns matching section IDs, titles, hierarchy level, and content snippets. " +
        "Use this for long or complex topics to pinpoint the exact section IDs before fetching full text."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The topic ID from searchTopics (e.g., '12345')")
        @JsonNames("topic_id")
        val topicId: String,
        @property:LLMDescription("Clinical keywords to search for within the topic (e.g., 'renal failure', 'dosing')")
        @JsonNames("query", "search_query", "keyword")
        val query: String
    )

    override suspend fun execute(args: Args): String = databaseTools.searchTopicSections(args.topicId, args.query)
}

