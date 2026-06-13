package com.uptodate.viewer.data.database.assets

import com.uptodate.viewer.data.database.assets.models.GraphicPayload

interface AssetRepository {
    suspend fun getGraphic(graphicId: String): GraphicPayload?
}
