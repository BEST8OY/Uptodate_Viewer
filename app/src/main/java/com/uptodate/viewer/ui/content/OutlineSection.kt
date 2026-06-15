package com.uptodate.viewer.ui.content

data class OutlineSection(
    val id: String,
    val title: String,
    val depth: Int = 0,
    val actionJson: String? = null
)
