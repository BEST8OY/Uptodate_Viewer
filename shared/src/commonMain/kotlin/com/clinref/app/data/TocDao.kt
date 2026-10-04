package com.clinref.app.data

import com.clinref.app.domain.TocItem
import com.clinref.shared.db.useQuery
import com.clinref.shared.db.useQueryFirstOrNull

class TocDao(
    private val dbManager: DatabaseManager
) {
    companion object {
        private const val VIDEO_INDEX_ID = "99999"
    }

    fun getTocItems(parentId: String? = null): List<TocItem> {
        val db = dbManager.getTocDb()

        if (parentId == null || parentId == "0") {
            val rootItems = mutableListOf<TocItem>()
            rootItems.add(TocItem(
                id = VIDEO_INDEX_ID,
                title = "Video Index",
                isLeaf = false,
                type = "VIDEO_INDEX",
                childrenInfo = null
            ))

            val items = db.useQuery(
                "SELECT id, title, parentId, leaf, section FROM TOC WHERE parentId = 0"
            ) { stmt ->
                val leafInt = stmt.getLong(3).toInt()
                TocItem(
                    id = stmt.getText(0),
                    title = stmt.getText(1),
                    isLeaf = leafInt == 1,
                    type = if (leafInt == 1) "TOPIC" else "SECTION",
                    childrenInfo = null,
                    section = if (stmt.isNull(4)) null else stmt.getText(4)
                )
            }
            rootItems.addAll(items)
            return rootItems
        }

        if (parentId == VIDEO_INDEX_ID) {
            return loadVideoIndex()
        }

        return db.useQuery(
            "SELECT id, title, parentId, leaf, section FROM TOC WHERE parentId = ?",
            bind = { it.bindText(1, parentId) }
        ) { stmt ->
            val leafInt = stmt.getLong(3).toInt()
            TocItem(
                id = stmt.getText(0),
                title = stmt.getText(1),
                isLeaf = leafInt == 1,
                type = if (leafInt == 1) "TOPIC" else "SECTION",
                childrenInfo = null,
                section = if (stmt.isNull(4)) null else stmt.getText(4)
            )
        }
    }

    private fun loadVideoIndex(): List<TocItem> {
        return try {
            val db = dbManager.getFsearchDb()
            db.useQuery(
                "SELECT Text as title, URL as id FROM search WHERE search.'table' MATCH 'movie'"
            ) { stmt ->
                TocItem(
                    id = stmt.getText(1),
                    title = stmt.getText(0),
                    isLeaf = true,
                    type = "GRAPHIC",
                    childrenInfo = null,
                    section = "Video Index"
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getTopicIdFromTocId(tocId: String): String? {
        return try {
            val db = dbManager.getTocDb()
            db.useQueryFirstOrNull(
                "SELECT topicId FROM TOCMap WHERE tocId = ?",
                bind = { it.bindText(1, tocId) }
            ) { stmt -> stmt.getText(0) }
        } catch (_: Exception) {
            null
        }
    }
}
