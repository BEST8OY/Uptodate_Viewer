package com.clinref.app.data.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.clinref.app.data.MedicalDatabaseTools
import kotlinx.serialization.Serializable

/**
 * Native class-based Koog tools for clinical database access.
 * Zero reflection at runtime; full compile-time type safety for arguments and schemas.
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
    data class Args(
        @property:LLMDescription("Single medical term or core clinical concept (e.g., 'asthma', 'metformin').")
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
    data class Args(
        @property:LLMDescription("The topic ID returned by searchTopics (e.g., '12345')")
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
    data class Args(
        @property:LLMDescription("The topic ID from searchTopics")
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
    data class Args(
        @property:LLMDescription("The topic ID")
        val topicId: String,
        @property:LLMDescription("List of section IDs to retrieve from getTopicOutline")
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
    data class Args(
        @property:LLMDescription("The graphic ID from getTopicOutline (e.g., 'Graphic-12345' or '12345')")
        val graphicId: String
    )

    override suspend fun execute(args: Args): String = databaseTools.getGraphicContent(args.graphicId)
}

class SubmitClinicalAnswerTool(
    private val databaseTools: MedicalDatabaseTools
) : SimpleTool<SubmitClinicalAnswerTool.Args>(
    argsType = typeToken<Args>(),
    name = "submitClinicalAnswer",
    description = "MUST be called to present your final clinical answer to the user. " +
        "Provide the formatted markdown response text and indicate if data was unavailable. " +
        "References are automatically extracted from your tool calls."
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The formatted markdown response text for the clinician.")
        val answerText: String,
        @property:LLMDescription("Set to true ONLY if the database search yielded no relevant clinical information.")
        val noDataFound: Boolean = false
    )

    override suspend fun execute(args: Args): String = databaseTools.submitClinicalAnswer(args.answerText, args.noDataFound)
}
