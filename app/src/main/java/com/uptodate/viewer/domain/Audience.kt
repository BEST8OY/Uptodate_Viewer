package com.uptodate.viewer.domain

enum class Audience(val code: String, val label: String) {
    ALL("X", "All"),
    ADULTS("A", "Adults"),
    PEDIATRICS("P", "Pediatrics"),
    PATIENTS("I", "Patients")
}
