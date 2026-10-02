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
        val parts = mutableListOf<String>()
        if (age.isNotBlank()) parts.add("Age: $age")
        if (sex.isNotBlank()) parts.add("Sex: $sex")
        if (conditions.isNotEmpty()) parts.add("Conditions: ${conditions.joinToString(", ")}")
        if (medications.isNotEmpty()) parts.add("Medications: ${medications.joinToString(", ")}")
        if (allergies.isNotEmpty()) parts.add("Allergies: ${allergies.joinToString(", ")}")
        if (notes.isNotBlank()) parts.add("Notes: $notes")
        return if (parts.isEmpty()) "" else "## Patient Profile\n${parts.joinToString("\n")}"
    }
}
