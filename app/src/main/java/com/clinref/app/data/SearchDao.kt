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
        val unidexAvailable = try {
            dbManager.getUnidexDb()
            true
        } catch (_: Exception) {
            false
        }

        if (unidexAvailable) {
            return getUnidexSuggestions(query)
        }
        return getQfSuggestions(query)
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
        if (primaryResults.isNotEmpty()) return primaryResults

        val fcontentResults = searchFts(dbManager.getFcontentsearchDb(), query)
        if (fcontentResults.isNotEmpty()) return fcontentResults

        return searchFts(dbManager.getFsearchDb(), query)
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
}
