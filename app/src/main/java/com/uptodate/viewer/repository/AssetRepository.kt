package com.uptodate.viewer.repository

import com.uptodate.viewer.data.AssetDao
import com.uptodate.viewer.domain.GraphicData
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
