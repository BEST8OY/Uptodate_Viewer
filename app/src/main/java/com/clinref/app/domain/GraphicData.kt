package com.clinref.app.domain

data class GraphicData(
    val id: String,
    val title: String = "",
    val type: String = "",
    val subtype: String = "",
    val imageHtml: String,
    val base64Image: String? = null,
    val movieUrl: String? = null
) {
    val isTable: Boolean get() = subtype == "graphic_table"
}
