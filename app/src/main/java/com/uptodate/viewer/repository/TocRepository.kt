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
        return tocDao.getTocItems(parentId)
    }
}
