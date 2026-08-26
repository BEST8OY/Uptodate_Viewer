package com.clinref.app.data

import com.clinref.app.util.ZstdUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AssetDao @Inject constructor(
    private val dbManager: DatabaseManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GraphicInfo(
        val displayName: String = "",
        val type: String = "",
        val subtype: String = "",
    )

    @Serializable
    private data class GraphicPayload(
        val graphicInfo: GraphicInfo? = null,
        val imageHtml: String = "",
        val base64Image: String? = null,
        val movieUrl: String? = null,
    )

    fun getGraphicJson(graphicId: String): Map<String, Any?>? {
        val db = dbManager.getAssetsDb()
        val cursor = db.rawQuery(
            "SELECT payload FROM graphic_asset WHERE id = ?",
            arrayOf(graphicId)
        )

        return cursor.use {
            if (it.moveToFirst()) {
                val payload = it.getBlob(0)
                val payloadStr = ZstdUtil.decodePayload(payload)

                try {
                    val decoded = json.decodeFromString<GraphicPayload>(payloadStr)
                    mapOf(
                        "graphicInfo" to decoded.graphicInfo?.let { info ->
                            mapOf(
                                "displayName" to info.displayName,
                                "type" to info.type,
                                "subtype" to info.subtype,
                            )
                        },
                        "imageHtml" to decoded.imageHtml,
                        "base64Image" to decoded.base64Image,
                        "movieUrl" to decoded.movieUrl,
                    )
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }
    }

    fun getGraphicTitle(graphicId: String): String? {
        val info = getGraphicJson(graphicId)?.get("graphicInfo") as? Map<*, *>
        return (info?.get("displayName") as? String)?.ifEmpty { null }
    }
}
