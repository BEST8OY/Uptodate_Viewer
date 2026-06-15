package com.uptodate.viewer.ui.content

data class OutlineSection(
    val id: String,
    val title: String,
    val depth: Int = 0,
    val actionJson: String? = null,
    val sectionType: SectionType = SectionType.TOPIC,
    val graphicSubtype: String = "",
    val graphicLabel: String = ""
)

enum class SectionType {
    TOPIC,
    GRAPHIC,
    RELATED
}
