package com.clinref.app.domain

import kotlinx.serialization.Serializable

/**
 * Content-anchored reading position inside an article.
 *
 * [anchor] is platform-opaque: each article renderer encodes and decodes its own scheme
 * (Android WebView: `b:<blockIndex>:<fraction>`, Desktop: `off:<charOffset>`). Positions are never
 * shared across platforms or devices. [progress] is the portable fallback used when [anchor]
 * cannot be applied (for example when [contentRev] no longer matches the rendered HTML).
 */
@Serializable
data class ReadingPosition(
    val anchor: String,
    val progress: Float,
    val sectionId: String? = null,
    val contentRev: String = ""
) {
    /** True once the reader has effectively reached the end; such articles reopen at the top. */
    val isFinished: Boolean get() = progress >= FINISHED_PROGRESS

    companion object {
        const val FINISHED_PROGRESS = 0.97f
    }
}
