package com.uptodate.viewer.domain

data class GraphicData(
    val id: String,
    val imageHtml: String,
    val base64Image: String? = null
)
