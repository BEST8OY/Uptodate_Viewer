package com.uptodate.viewer.repository

import com.uptodate.viewer.data.TocDao
import com.uptodate.viewer.domain.TocItem
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TocRepository @Inject constructor(
    private val tocDao: TocDao
) {
    fun getTocItems(parentId: String? = null): List<TocItem> {
        return tocDao.getTocItems(parentId).map { map ->
            @Suppress("UNCHECKED_CAST")
            TocItem(
                id = map["id"] as String,
                title = map["title"] as String,
                isLeaf = map["leaf"] as Boolean,
                type = map["type"] as? String,
                childrenInfo = (map["childrenInfo"] as? List<*>)?.map { child ->
                    val childMap = child as Map<String, Any?>
                    TocItem(
                        id = childMap["id"] as String,
                        title = childMap["title"] as String,
                        isLeaf = childMap["leaf"] as Boolean,
                        type = childMap["type"] as? String,
                        childrenInfo = null
                    )
                }
            )
        }
    }
}
