package com.clinref.app.ui.content

import com.clinref.app.domain.ReadingPosition

/** Where a freshly (re)loaded [ArticleDocument] should be scrolled to before the user sees it. */
sealed interface StartTarget {
    /** Natural top of the article. */
    data object Top : StartTarget

    /** Deep link / chat citation / outline: scroll to the element with this id. */
    data class Section(val id: String) : StartTarget

    /** Resume a previously recorded reading position. */
    data class Resume(val position: ReadingPosition) : StartTarget
}

/**
 * Everything an article renderer needs to present one article load. A pure, immutable value.
 *
 * Renderers must (re)load only when [revision] changes; [html] is the themed document without any
 * platform scripts. [contentRev] identifies the underlying article content (independent of theme),
 * so positions captured against one theme/render remain valid for another.
 *
 * [start] is where this document was *born* (deep link, saved position, or the live position at a
 * re-theme). A renderer view can be recreated for the *same* revision (tab switch, rotation); it
 * must then ask [ArticleWebViewController.resolveStart], which prefers the reader's latest position
 * over [start].
 */
data class ArticleDocument(
    val topicId: String,
    val html: String,
    val contentRev: String,
    val revision: Long,
    val start: StartTarget = StartTarget.Top
)
