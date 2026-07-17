package com.clinref.app.data.local

import androidx.room3.ColumnTypeConverter

class Converters {
    @ColumnTypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(separator = ",")

    @ColumnTypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isBlank()) emptyList() else value.split(",")
}
