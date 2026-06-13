package com.uptodate.viewer.data.database.assets.models

import kotlinx.serialization.Serializable

@Serializable
data class GraphicPayload(
    val imageHtml: String? = null,
    val base64Image: String? = null
)
