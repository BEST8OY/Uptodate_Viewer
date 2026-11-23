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
 * Mutually exclusive bottom sheet display state for ContentScreen.
 */
sealed interface ContentSheet {
    data class References(val data: ReferenceSheetData) : ContentSheet
    data class Grade(val data: GradeSheetData) : ContentSheet
}

/**
 * State for presenting reference citation details in a bottom sheet.
 */
data class ReferenceSheetData(
    val references: List<ReferenceItem>,
    val targetSectionId: String = "references"
)

/**
 * State for presenting GRADE recommendation explanations, mirroring the
 * official UpToDate grade pages (https://www.uptodate.com/contents/grade/1..6).
 */
data class GradeSheetData(
    val grade: String,
    /** Headline sentence from the official grade page. */
    val summary: String,
    /** "Grade 1 recommendation is a strong recommendation..." / "Grade 2 ... weak ..." paragraph. */
    val tierExplanation: String,
    /** "Grade A/B/C means the evidence comes from..." paragraph. */
    val letterExplanation: String
) {
    companion object {
        val GENERIC_RECOMMENDATION = GradeSheetData(
            grade = "GRADE Recommendation",
            summary = "UpToDate applies the GRADE system (Grading of Recommendations Assessment, Development " +
                "and Evaluation) to classify the strength of each recommendation and the quality of the " +
                "supporting evidence.",
            tierExplanation = "",
            letterExplanation = ""
        )

        fun fromGradeId(gradeId: String?): GradeSheetData = when (gradeId) {
            "1" -> GradeSheetData(
                grade = "Grade 1A",
                summary = "A Grade 1A recommendation is a strong recommendation, and applies to most patients in " +
                    "most circumstances without reservation. Clinicians should follow a strong recommendation " +
                    "unless a clear and compelling rationale for an alternative approach is present.",
                tierExplanation = UTD_GRADE_1_EXPLANATION,
                letterExplanation = UTD_GRADE_A_EXPLANATION
            )
            "2" -> GradeSheetData(
                grade = "Grade 1B",
                summary = "A Grade 1B recommendation is a strong recommendation, and applies to most patients. " +
                    "Clinicians should follow a strong recommendation unless a clear and compelling rationale " +
                    "for an alternative approach is present.",
                tierExplanation = UTD_GRADE_1_EXPLANATION,
                letterExplanation = UTD_GRADE_B_EXPLANATION
            )
            "3" -> GradeSheetData(
                grade = "Grade 1C",
                summary = "A Grade 1C recommendation is a strong recommendation; however, some of the key evidence " +
                    "supporting the recommendation is of low quality. While we currently believe the " +
                    "recommendation applies to most patients, the recommendation may change when higher quality " +
                    "evidence becomes available.",
                tierExplanation = UTD_GRADE_1_EXPLANATION,
                letterExplanation = UTD_GRADE_C_EXPLANATION
            )
            "4" -> GradeSheetData(
                grade = "Grade 2A",
                summary = "A Grade 2A recommendation is a weak recommendation, and the best action may differ " +
                    "depending on circumstances or patient or societal values.",
                tierExplanation = UTD_GRADE_2_EXPLANATION,
                letterExplanation = UTD_GRADE_A_EXPLANATION
            )
            "5" -> GradeSheetData(
                grade = "Grade 2B",
                summary = "A Grade 2B recommendation is a weak recommendation; alternative approaches may be " +
                    "better for some patients under some circumstances.",
                tierExplanation = UTD_GRADE_2_EXPLANATION,
                letterExplanation = UTD_GRADE_B_EXPLANATION
            )
            "6" -> GradeSheetData(
                grade = "Grade 2C",
                summary = "A Grade 2C recommendation is a very weak recommendation; other alternatives may be " +
                    "equally reasonable.",
                tierExplanation = UTD_GRADE_2_EXPLANATION,
                letterExplanation = UTD_GRADE_C_EXPLANATION
            )
            else -> GENERIC_RECOMMENDATION
        }
    }
}

/**
 * Canonical UpToDate GRADE copy shared by the six grade pages.
 */
internal const val UTD_GRADE_1_EXPLANATION =
    "A Grade 1 recommendation is a strong recommendation. It means that we believe that if you follow the " +
    "recommendation, you will be doing more good than harm for most, if not all of your patients."

internal const val UTD_GRADE_2_EXPLANATION =
    "A Grade 2 recommendation is a weak recommendation. It means \u201Cthis is our suggestion, but you may want " +
    "to think about it.\u201D It is unlikely that you should follow the suggested approach in all your patients, " +
    "and you might reasonably choose an alternative approach. For Grade 2 recommendations, benefits and risks " +
    "may be finely balanced, or the benefits and risks may be uncertain. In deciding whether to follow a " +
    "Grade 2 recommendation in an individual patient, you may want to think about your patient's values and " +
    "preferences or about your patient's risk aversion."

internal const val UTD_GRADE_A_EXPLANATION =
    "Grade A means that the best estimates of the critical benefits and risks come from consistent data from " +
    "well-performed, randomized, controlled trials or overwhelming data of some other form (eg, well-executed " +
    "observational studies with very large treatment effects). Further research is unlikely to have an impact " +
    "on our confidence in the estimates of benefit and risk."

internal const val UTD_GRADE_B_EXPLANATION =
    "Grade B means that the best estimates of the critical benefits and risks come from randomized, controlled " +
    "trials with important limitations (eg, inconsistent results, methodologic flaws, imprecise results, " +
    "extrapolation from a different population or setting) or very strong evidence of some other form. " +
    "Further research (if performed) is likely to have an impact on our confidence in the estimates of benefit " +
    "and risk, and may change the estimates."

internal const val UTD_GRADE_C_EXPLANATION =
    "Grade C means the evidence comes from observational studies, unsystematic clinical experience, or from " +
    "randomized, controlled trials with serious flaws. Any estimate of effect is uncertain."

internal val UTD_RECOMMENDATION_GRADE_LEGEND: List<Pair<String, String>> = listOf(
    "Strong recommendation" to
        "Benefits clearly outweigh the risks and burdens (or vice versa) for most, if not all, patients",
    "Weak recommendation" to
        "Benefits and risks closely balanced and/or uncertain"
)

internal val UTD_EVIDENCE_GRADE_LEGEND: List<Pair<String, String>> = listOf(
    "High-quality evidence" to
        "Consistent evidence from randomized trials, or overwhelming evidence of some other form",
    "Moderate-quality evidence" to
        "Evidence from randomized trials with important limitations, or very strong evidence of some other form",
    "Low-quality evidence" to
        "Evidence from observational studies, unsystematic clinical observations, or from randomized trials " +
        "with serious flaws"
)

