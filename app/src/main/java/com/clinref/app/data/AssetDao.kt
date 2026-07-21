package com.clinref.app.data

import android.graphics.BitmapFactory
import com.clinref.app.util.GzipUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AssetDao @Inject constructor(
    private val dbManager: DatabaseManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GraphicPayload(
        val imageHtml: String = "",
        val base64Image: String? = null
    )

    fun getGraphic(graphicId: String): android.graphics.Bitmap? {
        val db = dbManager.getAssetsDb()
        val cursor = db.rawQuery(
            "SELECT payload FROM other_asset WHERE id = ?",
            arrayOf("RESOURCE/graphic-$graphicId.jpg")
        )

        return cursor.use {
            if (it.moveToFirst()) {
                val payload = it.getBlob(0)
                BitmapFactory.decodeByteArray(payload, 0, payload.size)
            } else {
                null
            }
        }
    }

    fun getGraphicJson(graphicId: String): Map<String, Any?>? {
        val db = dbManager.getAssetsDb()
        val cursor = db.rawQuery(
            "SELECT payload FROM graphic_asset WHERE id = ?",
            arrayOf(graphicId)
        )

        return cursor.use {
            if (it.moveToFirst()) {
                val payload = it.getBlob(0)
                val payloadStr = GzipUtil.decodePayload(payload)

                try {
                    val decoded = json.decodeFromString<GraphicPayload>(payloadStr)
                    mapOf(
                        "imageHtml" to decoded.imageHtml,
                        "base64Image" to decoded.base64Image
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
        val db = dbManager.getAssetsDb()
        val cursor = db.rawQuery(
            "SELECT payload FROM graphic_asset WHERE id = ?",
            arrayOf(graphicId)
        )

        return cursor.use {
            if (it.moveToFirst()) {
                val payload = it.getBlob(0)
                val payloadStr = GzipUtil.decodePayload(payload)
                try {
                    val element = json.parseToJsonElement(payloadStr)
                    val graphicInfo = element.jsonObject["graphicInfo"]?.jsonObject
                    graphicInfo?.get("displayName")?.jsonPrimitive?.content
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }
    }

}
