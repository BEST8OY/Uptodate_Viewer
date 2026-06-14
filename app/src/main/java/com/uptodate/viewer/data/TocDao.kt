package com.uptodate.viewer.data

import com.github.luben.zstd.Zstd
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TocDao @Inject constructor(
    private val dbManager: DatabaseManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getTocItems(parentId: String? = null): List<Map<String, Any?>> {
        val db = dbManager.getAssetsDb()
        val resourceId = when {
            parentId == null || parentId == "0" -> "RESOURCE/table_of_contents.json"
            !parentId.startsWith("RESOURCE/") -> "RESOURCE/$parentId.json"
            else -> parentId
        }

        val cursor = db.rawQuery(
            "SELECT payload FROM other_asset WHERE id = ?",
            arrayOf(resourceId)
        )

        return cursor.use {
            if (it.moveToFirst()) {
                val payload = it.getBlob(0)
                val payloadStr = if (isZstdCompressed(payload)) {
                    decompressZstd(payload) ?: return emptyList()
                } else {
                    String(payload)
                }

                try {
                    val jsonObj = json.parseToJsonElement(payloadStr).jsonObject
                    val children = jsonObj["childrenInfo"]?.jsonArray ?: return emptyList()
                    processChildren(children)
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }
    }

    private fun processChildren(children: kotlinx.serialization.json.JsonArray): List<Map<String, Any?>> {
        return children.map { child ->
            val obj = child.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            mapOf(
                "id" to (obj["id"]?.jsonPrimitive?.content ?: ""),
                "title" to (obj["title"]?.jsonPrimitive?.content ?: ""),
                "leaf" to (type == "TOPIC"),
                "type" to type,
                "childrenInfo" to obj["childrenInfo"]?.jsonArray?.let { processChildren(it) }
            )
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
