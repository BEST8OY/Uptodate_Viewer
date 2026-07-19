package com.clinref.app.repository

import com.clinref.app.data.TocDao
import com.clinref.app.domain.TocItem
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TocRepository @Inject constructor(
    private val tocDao: TocDao
) {
    fun getTocItems(parentId: String? = null): List<TocItem> {
        return tocDao.getTocItems(parentId)
    }

    fun getTopicIdFromTocId(tocId: String): String? {
        return tocDao.getTopicIdFromTocId(tocId)
    }
}
