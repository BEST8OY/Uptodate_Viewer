package com.clinref.app.data

import com.clinref.app.domain.TocItem
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TocDao @Inject constructor(
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

            val cursor = db.rawQuery(
                "SELECT id, title, parentId, leaf, section FROM TOC WHERE parentId = 0",
                null
            )
            cursor.use {
                while (it.moveToNext()) {
                    rootItems.add(TocItem(
                        id = it.getString(0),
                        title = it.getString(1),
                        isLeaf = it.getInt(3) == 1,
                        type = if (it.getInt(3) == 1) "TOPIC" else "SECTION",
                        childrenInfo = null,
                        section = it.getString(4)
                    ))
                }
            }
            return rootItems
        }

        if (parentId == VIDEO_INDEX_ID) {
            return loadVideoIndex()
        }

        val cursor = db.rawQuery(
            "SELECT id, title, parentId, leaf, section FROM TOC WHERE parentId = ?",
            arrayOf(parentId)
        )
        return cursor.use {
            val items = mutableListOf<TocItem>()
            while (it.moveToNext()) {
                items.add(TocItem(
                    id = it.getString(0),
                    title = it.getString(1),
                    isLeaf = it.getInt(3) == 1,
                    type = if (it.getInt(3) == 1) "TOPIC" else "SECTION",
                    childrenInfo = null,
                    section = it.getString(4)
                ))
            }
            items
        }
    }

    private fun loadVideoIndex(): List<TocItem> {
        val db = dbManager.getFsearchDb()
        return try {
            val cursor = db.rawQuery(
                "SELECT Text as title, URL as id FROM search WHERE search.'table' MATCH 'movie'",
                null
            )
            cursor.use {
                val items = mutableListOf<TocItem>()
                while (it.moveToNext()) {
                    items.add(TocItem(
                        id = it.getString(1),
                        title = it.getString(0),
                        isLeaf = true,
                        type = "GRAPHIC",
                        childrenInfo = null,
                        section = "Video Index"
                    ))
                }
                items
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getTopicIdFromTocId(tocId: String): String? {
        val db = dbManager.getTocDb()
        val cursor = db.rawQuery(
            "SELECT topicId FROM TOCMap WHERE tocId = ?",
            arrayOf(tocId)
        )
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }
}
