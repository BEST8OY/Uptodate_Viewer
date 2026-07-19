package com.clinref.app.data

import android.graphics.BitmapFactory
import com.clinref.app.util.GzipUtil
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
                    val jsonObj = json.parseToJsonElement(payloadStr).jsonObject
                    mapOf(
                        "imageHtml" to (jsonObj["imageHtml"]?.jsonPrimitive?.content ?: ""),
                        "base64Image" to jsonObj["base64Image"]?.jsonPrimitive?.content
                    )
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }
    }

}
