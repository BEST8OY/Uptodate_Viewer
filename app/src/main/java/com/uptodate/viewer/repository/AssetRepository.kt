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
        return GraphicData(
            id = graphicId,
            imageHtml = data["imageHtml"] as? String ?: "",
            base64Image = data["base64Image"] as? String
        )
    }
}
