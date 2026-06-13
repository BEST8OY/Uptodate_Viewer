package com.uptodate.viewer.data.database.ext

import android.database.Cursor

fun Cursor.string(name: String): String? {
    val idx = getColumnIndex(name)
    return if (idx >= 0) getString(idx) else null
}

fun Cursor.stringOrThrow(name: String): String {
    return string(name) ?: error("Column $name not found or null")
}

fun Cursor.int(name: String): Int? {
    val idx = getColumnIndex(name)
    return if (idx >= 0) getInt(idx) else null
}

fun Cursor.long(name: String): Long? {
    val idx = getColumnIndex(name)
    return if (idx >= 0) getLong(idx) else null
}

fun Cursor.blob(name: String): ByteArray? {
    val idx = getColumnIndex(name)
    return if (idx >= 0) getBlob(idx) else null
}

fun <T> Cursor.mapEach(transform: (Cursor) -> T): List<T> {
    val list = mutableListOf<T>()
    while (moveToNext()) {
        list.add(transform(this))
    }
    return list
}
