package com.uptodate.viewer.data

import android.graphics.BitmapFactory
import com.github.luben.zstd.Zstd
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
                val payloadStr = if (isZstdCompressed(payload)) {
                    decompressZstd(payload) ?: return null
                } else {
                    String(payload)
                }

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

    private fun isZstdCompressed(data: ByteArray): Boolean {
        return data.size >= 4 &&
            data[0] == 0x28.toByte() &&
            data[1] == 0xB5.toByte() &&
            data[2] == 0x2F.toByte() &&
            data[3] == 0xFD.toByte()
    }

    private fun decompressZstd(data: ByteArray): String? {
        return try {
            val decompressed = Zstd.decompressByteArray(data)
            String(decompressed, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
