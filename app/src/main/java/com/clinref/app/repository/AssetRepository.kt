package com.clinref.app.repository

import com.clinref.app.data.AssetDao
import com.clinref.app.domain.GraphicData
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AssetRepository @Inject constructor(
    private val assetDao: AssetDao
) {
    fun getGraphic(graphicId: String): GraphicData? {
        val data = assetDao.getGraphicJson(graphicId) ?: return null
        val graphicInfo = data["graphicInfo"] as? Map<*, *>
        return GraphicData(
            id = graphicId,
            title = graphicInfo?.get("displayName") as? String ?: "",
            imageHtml = data["imageHtml"] as? String ?: "",
            base64Image = data["base64Image"] as? String,
            movieUrl = data["movieUrl"] as? String
        )
    }
}
