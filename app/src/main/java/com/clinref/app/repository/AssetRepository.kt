package com.clinref.app.repository

import android.util.Log
import com.clinref.app.data.AssetDao
import com.clinref.app.domain.GraphicData
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AssetRepository @Inject constructor(
    private val assetDao: AssetDao
) {
    private val cache = object : LinkedHashMap<String, GraphicData>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GraphicData>): Boolean =
            size > MAX_CACHED_GRAPHICS
    }

    fun getGraphic(graphicId: String): GraphicData? {
        synchronized(cache) { cache[graphicId] }?.let { return it }

        val data = assetDao.getGraphicJson(graphicId) ?: run {
            Log.w(TAG, "No decodable payload for graphic $graphicId")
            return null
        }
        val graphicInfo = data["graphicInfo"] as? Map<*, *>
        val result = GraphicData(
            id = graphicId,
            title = graphicInfo?.get("displayName") as? String ?: "",
            type = graphicInfo?.get("type") as? String ?: "",
            subtype = graphicInfo?.get("subtype") as? String ?: "",
            imageHtml = data["imageHtml"] as? String ?: "",
            base64Image = data["base64Image"] as? String,
            movieUrl = data["movieUrl"] as? String
        )
        synchronized(cache) { cache[graphicId] = result }
        return result
    }

    fun getGraphicTitle(graphicId: String): String? {
        synchronized(cache) { cache[graphicId] }?.let {
            if (it.title.isNotEmpty()) return it.title
        }
        return assetDao.getGraphicTitle(graphicId)
    }

    private companion object {
        const val TAG = "AssetRepository"
        const val MAX_CACHED_GRAPHICS = 16
    }
}
