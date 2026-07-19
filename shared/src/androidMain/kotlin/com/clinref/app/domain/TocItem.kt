package com.clinref.app.domain

data class TocItem(
    val id: String,
    val title: String,
    val isLeaf: Boolean,
    val type: String?,
    val childrenInfo: List<TocItem>?,
    val section: String? = null
)
