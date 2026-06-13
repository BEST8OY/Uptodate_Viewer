package com.uptodate.viewer.util

object TopicIdNormalizer {
    fun extractNumeric(raw: String): Int? {
        return TopicId.REGEX.find(raw.trim())?.groupValues?.get(1)?.toIntOrNull()
    }

    fun normalize(raw: String): String {
        val num = extractNumeric(raw) ?: return raw
        return num.toString()
    }
}
