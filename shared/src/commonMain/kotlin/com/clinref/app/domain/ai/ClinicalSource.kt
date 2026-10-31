package com.clinref.app.domain.ai

import com.clinref.app.ui.chat.ResolvedGraphicRef
import com.clinref.app.ui.chat.ResolvedTopicRef
import kotlinx.serialization.Serializable

/**
 * Unified domain representation for clinical evidence sources cited in AI responses.
 * Replaces fragmented, ad-hoc reference models with a cohesive, typed hierarchy.
 */
@Serializable
sealed interface ClinicalSource {

    val id: String
    val displayTitle: String

    @Serializable
    data class SectionRef(
        val sectionId: String,
        val sectionTitle: String
    )

    @Serializable
    data class Article(
        val topicId: String,
        val topicTitle: String,
        val sections: List<SectionRef> = emptyList()
    ) : ClinicalSource {
        override val id: String get() = topicId
        override val displayTitle: String get() = topicTitle

        fun toTopicRefs(): List<SafetyValidator.TopicRef> {
            if (sections.isEmpty()) {
                return listOf(
                    SafetyValidator.TopicRef(
                        topicId = topicId,
                        sectionId = "",
                        label = topicTitle,
                        topicTitle = topicTitle
                    )
                )
            }
            return sections.map { sec ->
                SafetyValidator.TopicRef(
                    topicId = topicId,
                    sectionId = sec.sectionId,
                    label = sec.sectionTitle,
                    topicTitle = topicTitle
                )
            }
        }

        fun toResolvedTopicRefs(): List<ResolvedTopicRef> {
            if (sections.isEmpty()) {
                return listOf(
                    ResolvedTopicRef(
                        topicId = topicId,
                        title = topicTitle,
                        sectionId = null,
                        topicTitle = topicTitle
                    )
                )
            }
            return sections.map { sec ->
                ResolvedTopicRef(
                    topicId = topicId,
                    title = sec.sectionTitle,
                    sectionId = sec.sectionId,
                    topicTitle = topicTitle
                )
            }
        }
    }

    @Serializable
    data class Table(
        val graphicId: String,
        val tableTitle: String,
        val parentTopicId: String? = null,
        val parentTopicTitle: String? = null
    ) : ClinicalSource {
        override val id: String get() = graphicId
        override val displayTitle: String get() = tableTitle

        fun toGraphicRef(): SafetyValidator.GraphicRef {
            return SafetyValidator.GraphicRef(
                graphicId = graphicId,
                label = tableTitle,
                topicId = parentTopicId
            )
        }

        fun toResolvedGraphicRef(): ResolvedGraphicRef {
            return ResolvedGraphicRef(
                graphicId = graphicId,
                title = tableTitle,
                topicId = parentTopicId,
                topicTitle = parentTopicTitle
            )
        }
    }

    companion object {
        /**
         * Converts a list of [SafetyValidator.TopicRef] into grouped [ClinicalSource.Article] instances.
         */
        fun fromTopicRefs(refs: List<SafetyValidator.TopicRef>): List<Article> {
            if (refs.isEmpty()) return emptyList()
            return refs.groupBy { it.topicId }.map { (topicId, topicRefsList) ->
                val primaryTitle = topicRefsList.firstOrNull { it.topicTitle.isNotBlank() }?.topicTitle
                    ?: topicRefsList.firstOrNull()?.label?.takeIf { !AiJsonUtils.isNumericOnly(it) }
                    ?: topicId
                val sections = topicRefsList
                    .filter { it.sectionId.isNotBlank() && !it.sectionId.equals("FULL", ignoreCase = true) }
                    .map { SectionRef(sectionId = it.sectionId, sectionTitle = it.label) }
                    .distinctBy { it.sectionId }
                Article(
                    topicId = topicId,
                    topicTitle = primaryTitle,
                    sections = sections
                )
            }
        }

        /**
         * Converts a list of [SafetyValidator.GraphicRef] into [ClinicalSource.Table] instances.
         */
        fun fromGraphicRefs(
            refs: List<SafetyValidator.GraphicRef>,
            parentTopicTitles: Map<String, String> = emptyMap()
        ): List<Table> {
            if (refs.isEmpty()) return emptyList()
            return refs.distinctBy { it.graphicId }.map { ref ->
                val parentTitle = ref.topicId?.let { parentTopicTitles[it] }
                Table(
                    graphicId = ref.graphicId,
                    tableTitle = ref.label,
                    parentTopicId = ref.topicId,
                    parentTopicTitle = parentTitle
                )
            }
        }

        /**
         * Converts UI [ResolvedTopicRef] and [ResolvedGraphicRef] into [ClinicalSource] models.
         */
        fun fromResolved(
            topicRefs: List<ResolvedTopicRef>,
            graphicRefs: List<ResolvedGraphicRef>
        ): Pair<List<Article>, List<Table>> {
            val articles = topicRefs.groupBy { it.topicId }.map { (topicId, group) ->
                val primaryTitle = group.firstOrNull { it.topicTitle.isNotBlank() }?.topicTitle
                    ?: group.firstOrNull()?.title?.takeIf { !AiJsonUtils.isNumericOnly(it) }
                    ?: topicId
                val sections = group
                    .filter { !it.sectionId.isNullOrBlank() && !it.sectionId.equals("FULL", ignoreCase = true) }
                    .map { SectionRef(sectionId = it.sectionId!!, sectionTitle = it.title) }
                    .distinctBy { it.sectionId }
                Article(
                    topicId = topicId,
                    topicTitle = primaryTitle,
                    sections = sections
                )
            }

            val tables = graphicRefs.distinctBy { it.graphicId }.map { ref ->
                Table(
                    graphicId = ref.graphicId,
                    tableTitle = ref.title,
                    parentTopicId = ref.topicId,
                    parentTopicTitle = ref.topicTitle
                )
            }

            return articles to tables
        }
    }
}
