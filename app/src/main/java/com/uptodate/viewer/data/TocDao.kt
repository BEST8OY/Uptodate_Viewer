package com.uptodate.viewer.data

import com.uptodate.viewer.domain.TocItem
import com.uptodate.viewer.util.GzipUtil
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

    fun getTocItems(parentId: String? = null): List<TocItem> {
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
                val payloadStr = GzipUtil.decodePayload(payload)

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

    private fun processChildren(children: kotlinx.serialization.json.JsonArray): List<TocItem> {
        return children.map { child ->
            val obj = child.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            TocItem(
                id = obj["id"]?.jsonPrimitive?.content ?: "",
                title = obj["title"]?.jsonPrimitive?.content ?: "",
                isLeaf = type == "TOPIC",
                type = type,
                childrenInfo = obj["childrenInfo"]?.jsonArray?.let { processChildren(it) }
            )
        }
    }

}
