package com.clinref.app.ui.content

/**
 * Parsed citation metadata for a reference in an UpToDate topic.
 */
data class ReferenceItem(
    val number: Int,
    val abstractId: String?,
    val citation: String,
    val title: String = "",
    val authors: String = "",
    val journal: String = "",
    val pubmedUrl: String = ""
)

/**
 * State for presenting reference citation details in a bottom sheet / dialog.
 */
data class ReferenceDialogData(
    val references: List<ReferenceItem>,
    val targetSectionId: String = "references"
)

/**
 * State for presenting GRADE recommendation explanations.
 */
data class GradeDialogData(
    val grade: String,
    val recommendationStrength: String,
    val evidenceQuality: String,
    val description: String
)

/**
 * State for presenting editorial/legal policy modal.
 */
data class PolicyDialogData(
    val title: String,
    val content: String
)
