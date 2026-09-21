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
    data class Args @OptIn(ExperimentalSerializationApi::class) constructor(
        @property:LLMDescription("Single medical term or core clinical concept (e.g., 'asthma', 'metformin').")
        @JsonNames("search_query", "q", "query_text")
        val query: String
    )

    override suspend fun execute(args: Args): String = databaseTools.searchTopics(args.query)
}

class GetTopicOutlineTool(
    private val databaseTools: MedicalDatabaseTools
) : SimpleTool<GetTopicOutlineTool.Args>(
    argsType = typeToken<Args>(),
    name = "getTopicOutline",
    description = "Retrieve the topic outline containing section IDs, titles, graphic metadata, and related topics. " +
        "ALWAYS call this after searchTopics to obtain sectionId values for getTopicSectionsText."
) {
    @Serializable
    data class Args @OptIn(ExperimentalSerializationApi::class) constructor(
        @property:LLMDescription("The topic ID returned by searchTopics (e.g., '12345')")
        @JsonNames("topic_id")
        val topicId: String
    )

    override suspend fun execute(args: Args): String = databaseTools.getTopicOutline(args.topicId)
}

class GetRelatedTopicsTool(
    private val databaseTools: MedicalDatabaseTools
) : SimpleTool<GetRelatedTopicsTool.Args>(
    argsType = typeToken<Args>(),
    name = "getRelatedTopics",
    description = "Get related topic IDs and titles for a topic. Use this to build a candidate pool before fetching sections."
) {
    @Serializable
    data class Args @OptIn(ExperimentalSerializationApi::class) constructor(
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
    description = "Retrieve multiple sections from the same topic in a single call."
) {
    @Serializable
    data class Args @OptIn(ExperimentalSerializationApi::class) constructor(
        @property:LLMDescription("The topic ID")
        @JsonNames("topic_id")
        val topicId: String,
        @property:LLMDescription("List of section IDs to retrieve from getTopicOutline")
        @JsonNames("section_ids")
        val sectionIds: List<String>
    )

    override suspend fun execute(args: Args): String = databaseTools.getTopicSectionsText(args.topicId, args.sectionIds)
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
    data class Args @OptIn(ExperimentalSerializationApi::class) constructor(
        @property:LLMDescription("The graphic ID from getTopicOutline (e.g., 'Graphic-12345' or '12345')")
        @JsonNames("graphic_id")
        val graphicId: String
    )

    override suspend fun execute(args: Args): String = databaseTools.getGraphicContent(args.graphicId)
}

