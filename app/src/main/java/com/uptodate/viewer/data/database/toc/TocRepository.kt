package com.uptodate.viewer.data.database.toc

import com.uptodate.viewer.data.database.toc.models.TocNode

interface TocRepository {
    suspend fun getRootItems(): List<TocNode>
    suspend fun getChildItems(parentId: String): List<TocNode>
}
