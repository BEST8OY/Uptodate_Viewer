package com.clinref.app.domain.ai

import kotlinx.serialization.Serializable

/**
 * Phase a deterministic safety violation belongs to. Drives remediation routing:
 * Phase-1 violations remediate toward evidence retrieval, Phase-2 violations toward
 * the tool-free synthesis revision.
 */
@Serializable
enum class ViolationTarget {
    PHASE_1,
    PHASE_2
}

/**
 * Deterministic failure codes surfaced by the safety gates. Codes must be stable
 * identifiers — they key the revision template lines (`[CODE] detail`) and the
 * string-shared vector harness.
 */
object ViolationCodes {
    const val NO_TOOLS = "NO_TOOLS"
    const val NO_SECTIONS = "NO_SECTIONS"
    const val BLANK_NO_EVIDENCE = "BLANK_NO_EVIDENCE"
    const val BLANK_WITH_EVIDENCE = "BLANK_WITH_EVIDENCE"
    const val UNVERIFIED_QTY = "UNVERIFIED_QTY"
    const val VISUAL = "VISUAL"

    /**
     * An [UNVERIFIED_QTY] draft that survived a tool-free revision: the quantities are
     * simply absent from what was retrieved, so retrying without tools cannot help.
     * Remediation goes back to Phase 1 with the offending values named.
     */
    const val UNRETRIEVED_DOSES = "UNRETRIEVED_DOSES"

    /**
     * Codes whose remediation renders the Phase-1 EVIDENCE GATE PAUSE template
     * (tool-call directives). Everything else renders the Phase-2 SAFETY REVISION
     * template, except [UNRETRIEVED_DOSES] which renders the missing-evidence template.
     */
    val PHASE_1_TEMPLATE_CODES: Set<String> = setOf(NO_TOOLS, NO_SECTIONS, BLANK_NO_EVIDENCE)
}

/**
 * One deterministic rule outcome: a rule code, today's human-readable reason string
 * as [detail], and the failure [phase][ViolationTarget] the remediation must target.
 */
@Serializable
data class Violation(
    val code: String,
    val detail: String,
    val target: ViolationTarget
)
