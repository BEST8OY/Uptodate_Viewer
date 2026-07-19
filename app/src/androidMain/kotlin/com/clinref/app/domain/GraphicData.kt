package com.clinref.app.domain

data class GraphicData(
    val id: String,
    val title: String = "",
    val imageHtml: String,
    val base64Image: String? = null,
    val movieUrl: String? = null
)
