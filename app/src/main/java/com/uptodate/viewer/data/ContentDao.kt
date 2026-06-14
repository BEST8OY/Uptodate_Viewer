package com.uptodate.viewer.data

import com.github.luben.zstd.Zstd
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentDao @Inject constructor(
    private val dbManager: DatabaseManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class TopicContent(
        val bodyHtml: String,
        val outlineHtml: String = "",
        val relatedGraphics: List<Map<String, Any?>> = emptyList(),
        val contributors: List<Map<String, Any?>>? = null
    )

    fun getTopicContent(topicId: String): TopicContent? {
        val assetResult = loadFromAssets(topicId)
        if (assetResult != null) return assetResult

        val fcontentResult = loadFromFcontentsearch(topicId)
        if (fcontentResult != null) return fcontentResult

        return TopicContent(
            bodyHtml = "<h1>Content not found</h1><p>Could not retrieve content for this topic.</p>"
        )
    }

    private fun extractNumericId(topicId: String): Int? {
        val match = Regex("""^(?:topic-)?(\d+)$""", RegexOption.IGNORE_CASE).find(topicId)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun loadFromAssets(topicId: String): TopicContent? {
        val numericId = extractNumericId(topicId) ?: return null
        val db = dbManager.getAssetsDb()

        val cursor = db.rawQuery(
            "SELECT payload FROM topic_asset WHERE id = ?",
            arrayOf(numericId.toString())
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
                    TopicContent(
                        bodyHtml = jsonObj["bodyHtml"]?.jsonPrimitive?.content?.removeSurrounding("\"") ?: "",
                        outlineHtml = jsonObj["outlineHtml"]?.jsonPrimitive?.content?.removeSurrounding("\"") ?: "",
                        relatedGraphics = emptyList(),
                        contributors = null
                    )
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }
    }

    private fun loadFromFcontentsearch(topicId: String): TopicContent? {
        val db = dbManager.getFcontentsearchDb()

        var cursor = db.rawQuery(
            "SELECT Text FROM search WHERE URL = ?",
            arrayOf("topic-$topicId")
        )

        var result = cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

        if (result == null) {
            cursor = db.rawQuery(
                "SELECT Text FROM search WHERE URL = ?",
                arrayOf(topicId)
            )
            result = cursor.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }

        return if (result != null) {
            TopicContent(bodyHtml = result)
        } else {
            null
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
            val decompressedSize = Zstd.decompressBound(data).toInt()
            val dst = ByteArray(decompressedSize)
            Zstd.decompressByteArray(dst, 0, decompressedSize, data, 0, data.size)
            String(dst, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
