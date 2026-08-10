package com.clinref.app.data

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchDao @Inject constructor(
    private val dbManager: DatabaseManager
) {
    fun getTopicTitle(topicId: String): String? {
        val db = dbManager.getUnidexDb()
        val numericId = topicId.removePrefix("topic-")

        val cursor = db.rawQuery(
            "SELECT title FROM topic WHERE topic_id = ? LIMIT 1",
            arrayOf(numericId)
        )

        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    fun getSuggestions(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val unidexAvailable = try {
            dbManager.getUnidexDb()
            true
        } catch (_: Exception) {
            false
        }

        // Try full query first
        val fullResults = if (unidexAvailable) {
            getUnidexSuggestions(trimmed)
        } else {
            getQfSuggestions(trimmed)
        }
        if (fullResults.isNotEmpty()) return fullResults

        val words = trimmed.split("\\s+".toRegex())

        // Try individual words (longest first) — finds core medical terms
        // e.g., "how to treat diabetes" -> try "diabetes", "treat", "how"
        for (word in words.sortedByDescending { it.length }) {
            if (word.length > 3) {
                val wordResults = if (unidexAvailable) {
                    getUnidexSuggestions(word)
                } else {
                    getQfSuggestions(word)
                }
                if (wordResults.isNotEmpty()) return wordResults
            }
        }

        // Try progressively shorter word prefixes
        for (i in words.size - 1 downTo 1) {
            val prefix = words.subList(0, i).joinToString(" ")
            val prefixResults = if (unidexAvailable) {
                getUnidexSuggestions(prefix)
            } else {
                getQfSuggestions(prefix)
            }
            if (prefixResults.isNotEmpty()) return prefixResults
        }

        return emptyList()
    }

    private fun getUnidexSuggestions(query: String): List<String> {
        val db = dbManager.getUnidexDb()
        val queryLen = query.length

        val (whereClause, params) = when (queryLen) {
            1 -> "d1 = ?" to arrayOf(query)
            2 -> "d2 = ?" to arrayOf(query)
            3 -> "d3 = ?" to arrayOf(query)
            else -> "disp LIKE ?" to arrayOf("$query%")
        }

        val sql = """
            SELECT disp as word, weight
            FROM query
            WHERE hide IS NULL AND $whereClause
            ORDER BY weight DESC, disp ASC
            LIMIT 30
        """

        val cursor = db.rawQuery(sql, params)
        return cursor.use {
            val results = mutableListOf<String>()
            while (it.moveToNext()) {
                results.add(it.getString(0))
            }
            results
        }
    }

    private fun getQfSuggestions(query: String): List<String> {
        val db = dbManager.getQfDb()
        val queryLen = query.length

        val whereClause = when (queryLen) {
            1 -> "q1 = ?"
            2 -> "q2 = ?"
            3 -> "q3 = ?"
            else -> "q LIKE ?"
        }
        val params = if (queryLen <= 3) arrayOf(query) else arrayOf("$query%")

        val sql = """
            SELECT q as _id, u as word
            FROM qf
            WHERE $whereClause
            ORDER BY f DESC, q ASC
            LIMIT 30
        """

        val cursor = db.rawQuery(sql, params)
        return cursor.use {
            val results = mutableListOf<String>()
            while (it.moveToNext()) {
                results.add(it.getString(1))
            }
            results
        }
    }

    fun searchTopics(query: String, preference: String = "X"): List<Map<String, String>> {
        val primaryResults = searchUnidex(query, preference)
        if (primaryResults.isNotEmpty()) {
            val filtered = primaryResults.filter { hasTopicAsset(it["topic_id"] ?: "") }
            if (filtered.isNotEmpty()) return filtered
        }

        // If query has special chars, strip them and retry unidex
        val cleaned = query.replace(Regex("[^a-zA-Z0-9 ]"), "").lowercase().trim()
        if (cleaned.isNotEmpty() && cleaned != query) {
            val cleanedResults = searchUnidex(cleaned, preference)
            if (cleanedResults.isNotEmpty()) {
                val filtered = cleanedResults.filter { hasTopicAsset(it["topic_id"] ?: "") }
                if (filtered.isNotEmpty()) return filtered
            }
        }

        // No FTS fallback — return empty, LLM will use suggestions
        return emptyList()
    }

    private fun searchUnidex(query: String, preference: String): List<Map<String, String>> {
        val db = dbManager.getUnidexDb()
        val cursor = db.rawQuery(
            """
            SELECT q.qbtype, x.topic_hits as hits
            FROM query q, query_topic x
            WHERE q.disp = ? AND x.nqid = q.nqid AND x.pref = ?
            """,
            arrayOf(query, preference)
        )

        return cursor.use {
            if (it.moveToFirst()) {
                val hitsBlob = it.getBlob(1)
                if (hitsBlob != null) {
                    parseHitsBlob(db, hitsBlob)
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }
    }

    private fun parseHitsBlob(db: android.database.sqlite.SQLiteDatabase, hitsBlob: ByteArray): List<Map<String, String>> {
        val hexString = hitsBlob.joinToString("") { "%02X".format(it) }
        val topicIds = mutableListOf<String>()
        val orderingCase = mutableListOf<String>()

        var i = 4
        var rank = 0
        while (i < hexString.length) {
            val chunk = hexString.substring(i, minOf(i + 8, hexString.length))
            if (chunk.length < 8) break
            try {
                val topicId = chunk.toLong(16).toString()
                topicIds.add(topicId)
                orderingCase.add("WHEN $topicId THEN $rank")
                rank++
            } catch (_: NumberFormatException) { }
            i += 8
        }

        if (topicIds.isEmpty()) return emptyList()

        val idsStr = topicIds.joinToString(",")
        val caseStr = orderingCase.joinToString(" ")

        val cursor = db.rawQuery(
            """
            SELECT topic_id, title
            FROM topic
            WHERE topic_id IN ($idsStr)
            ORDER BY CASE topic_id $caseStr END
            """,
            null
        )

        return cursor.use {
            val results = mutableListOf<Map<String, String>>()
            while (it.moveToNext()) {
                results.add(mapOf(
                    "topic_id" to it.getString(0),
                    "title" to it.getString(1)
                ))
            }
            results
        }
    }

    private fun searchFts(db: android.database.sqlite.SQLiteDatabase, query: String): List<Map<String, String>> {
        val ftsQuery = "$query AND URL:topic"
        return try {
            val cursor = db.rawQuery(
                """
                SELECT Text as title, URL as topic_id
                FROM search
                WHERE search MATCH ?
                ORDER BY rank(matchinfo(search)) DESC
                LIMIT 20
                """,
                arrayOf(ftsQuery)
            )

            cursor.use {
                val results = mutableListOf<Map<String, String>>()
                while (it.moveToNext()) {
                    results.add(mapOf(
                        "topic_id" to it.getString(1),
                        "title" to it.getString(0)
                    ))
                }
                results
            }
        } catch (_: Exception) {
            try {
                val cursor = db.rawQuery(
                    """
                    SELECT Text as title, URL as topic_id
                    FROM search
                    WHERE search MATCH ?
                    LIMIT 20
                    """,
                    arrayOf(ftsQuery)
                )

                cursor.use {
                    val results = mutableListOf<Map<String, String>>()
                    while (it.moveToNext()) {
                        results.add(mapOf(
                            "topic_id" to it.getString(1),
                            "title" to it.getString(0)
                        ))
                    }
                    results
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun hasTopicAsset(topicId: String): Boolean {
        if (topicId.isEmpty()) return false
        val numericId = topicId.removePrefix("topic-")
        return try {
            val db = dbManager.getAssetsDb()
            val cursor = db.rawQuery(
                "SELECT 1 FROM topic_asset WHERE id = ? LIMIT 1",
                arrayOf(numericId)
            )
            cursor.use { it.moveToFirst() }
        } catch (_: Exception) {
            false
        }
    }
}

