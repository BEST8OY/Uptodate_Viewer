package com.clinref.app.domain.ai

import kotlinx.serialization.Serializable

@Serializable
data class PatientProfile(
    val age: String = "",
    val sex: String = "",
    val conditions: List<String> = emptyList(),
    val medications: List<String> = emptyList(),
    val allergies: List<String> = emptyList(),
    val notes: String = ""
) {
    fun toSystemBlock(): String {
        if (this == PatientProfile()) return ""
        val parts = buildList {
            if (age.isNotBlank()) add("- Age: $age")
            if (sex.isNotBlank()) add("- Sex: $sex")
            if (conditions.isNotEmpty()) add("- Conditions: ${conditions.joinToString(", ")}")
            if (medications.isNotEmpty()) add("- Medications: ${medications.joinToString(", ")}")
            if (allergies.isNotEmpty()) add("- Allergies: ${allergies.joinToString(", ")}")
            if (notes.isNotBlank()) add("- Notes: $notes")
        }
        return buildString {
            appendLine("## Active Patient Profile")
            parts.forEach { appendLine(it) }
            appendLine()
            append("Adjust every recommendation for this patient's age, organ function, active medications, and documented allergies.")
        }
    }
}
