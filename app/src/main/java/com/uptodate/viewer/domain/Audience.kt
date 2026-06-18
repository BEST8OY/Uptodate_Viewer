package com.uptodate.viewer.domain

enum class Audience(val code: String, val label: String) {
    ALL("X", "All"),
    ADULT("A", "Adult"),
    PEDIATRIC("P", "Pediatric"),
    PATIENT("I", "Patient")
}
