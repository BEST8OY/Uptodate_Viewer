package com.clinref.app.data

import androidx.sqlite.SQLiteConnection
import com.clinref.shared.db.useQuery
import com.clinref.shared.db.useQueryFirstOrNull

class SearchDao(
    private val dbManager: DatabaseManager
) {
    fun getTopicTitle(topicId: String): String? {
        val clean = topicId.trim()
        val numericId = Regex("""\d+""").find(clean)?.value ?: clean.removePrefix("topic-").removePrefix("Topic-")

        return try {
            val db = dbManager.getUnidexDb()
            db.useQueryFirstOrNull(
                "SELECT title FROM topic WHERE topic_id = ? LIMIT 1",
                bind = { it.bindText(1, numericId) }
            ) { stmt ->
                stmt.getText(0).trim().takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private var cachedStopwords: Set<String>? = null

    private fun getStopwords(): Set<String> {
        cachedStopwords?.let { return it }
        return try {
            val db = dbManager.getUnidexDb()
            val set = db.useQuery(
                "SELECT inword FROM replac WHERE rtype = 'S'"
            ) { stmt ->
                stmt.getText(0).lowercase().trim()
            }.toSet()
            cachedStopwords = set
            set
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun getTokensByClinicalWeight(tokens: List<String>): List<Pair<String, Int>> {
        return try {
            val db = dbManager.getUnidexDb()
            tokens.map { token ->
                val weight = try {
                    db.useQueryFirstOrNull(
                        "SELECT max(weight) FROM query WHERE disp = ?",
                        bind = { it.bindText(1, token) }
                    ) { stmt -> stmt.getLong(0).toInt() } ?: 0
                } catch (_: Exception) { 0 }
                token to weight
            }.sortedByDescending { it.second }
        } catch (_: Exception) {
            tokens.map { it to 0 }
        }
    }

    private fun findQueriesByTokens(anchor: String, modifier: String): List<String> {
        return try {
            val db = dbManager.getUnidexDb()
            db.useQuery(
                """
                SELECT disp FROM query 
                WHERE (disp LIKE ? OR disp LIKE ?) AND hide IS NULL 
                ORDER BY weight DESC LIMIT 5
                """,
                bind = {
                    it.bindText(1, "$anchor $modifier%")
                    it.bindText(2, "$modifier $anchor%")
                }
            ) { stmt -> stmt.getText(0) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findQueriesByAnchor(anchor: String): List<String> {
        return try {
            val db = dbManager.getUnidexDb()
            db.useQuery(
                """
                SELECT disp FROM query 
                WHERE disp LIKE ? AND hide IS NULL 
                ORDER BY weight DESC LIMIT 5
                """,
                bind = { it.bindText(1, "$anchor%") }
            ) { stmt -> stmt.getText(0) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getSuggestions(query: String): List<String> {
        val trimmed = query.trim().lowercase()
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

        // Stopword sanitization & clinical weight ranking
        val stopwords = getStopwords()
        val tokens = trimmed.replace("-", " ").split("\\s+".toRegex()).filter { it.isNotBlank() && it !in stopwords }
        if (tokens.isNotEmpty() && unidexAvailable) {
            val weightedTokens = getTokensByClinicalWeight(tokens)
            for ((token, weight) in weightedTokens) {
                if (weight > 0) {
                    val wordResults = getUnidexSuggestions(token)
                    if (wordResults.isNotEmpty()) return wordResults
                }
            }
        }

        // Try progressively shorter word prefixes
        val words = trimmed.split("\\s+".toRegex())
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

        val (whereClause, param) = when (queryLen) {
            1 -> "d1 = ?" to query
            2 -> "d2 = ?" to query
            3 -> "d3 = ?" to query
            else -> "disp LIKE ?" to "$query%"
        }

        val sql = """
            SELECT disp as word, weight
            FROM query
            WHERE hide IS NULL AND $whereClause
            ORDER BY weight DESC, disp ASC
            LIMIT 30
        """

        return db.useQuery(
            sql,
            bind = { it.bindText(1, param) }
        ) { stmt -> stmt.getText(0) }
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
        val param = if (queryLen <= 3) query else "$query%"

        val sql = """
            SELECT q as _id, u as word
            FROM qf
            WHERE $whereClause
            ORDER BY f DESC, q ASC
            LIMIT 30
        """

        return db.useQuery(
            sql,
            bind = { it.bindText(1, param) }
        ) { stmt -> stmt.getText(1) }
    }

    fun searchTopics(query: String, preference: String = "X"): List<Map<String, String>> {
        val clean = query.trim().lowercase()
        if (clean.isEmpty()) return emptyList()

        // 1. Direct exact match
        val primaryResults = searchUnidex(clean, preference)
        if (primaryResults.isNotEmpty()) {
            val filtered = primaryResults.filter { hasTopicAsset(it["topic_id"] ?: "") }
            if (filtered.isNotEmpty()) return filtered
        }

        // 2. Stopword-sanitized exact match
        val stopwords = getStopwords()
        val tokens = clean.replace("-", " ").split("\\s+".toRegex()).filter { it.isNotBlank() && it !in stopwords }
        val sanitized = tokens.joinToString(" ")
        if (sanitized.isNotEmpty() && sanitized != clean) {
            val sanitizedResults = searchUnidex(sanitized, preference)
            if (sanitizedResults.isNotEmpty()) {
                val filtered = sanitizedResults.filter { hasTopicAsset(it["topic_id"] ?: "") }
                if (filtered.isNotEmpty()) return filtered
            }
        }

        // 3. Clinical weight-ranked token anchoring
        if (tokens.isNotEmpty()) {
            val weightedTokens = getTokensByClinicalWeight(tokens)
            if (weightedTokens.isNotEmpty() && weightedTokens[0].second > 0) {
                val anchor = weightedTokens[0].first
                val otherTokens = weightedTokens.drop(1).filter { it.first.length > 2 }.map { it.first }

                // Try anchor + modifier pair in query table
                for (other in otherTokens) {
                    val candidateQueries = findQueriesByTokens(anchor, other)
                    for (candidateQuery in candidateQueries) {
                        val results = searchUnidex(candidateQuery, preference)
                        if (results.isNotEmpty()) {
                            val filtered = results.filter { hasTopicAsset(it["topic_id"] ?: "") }
                            if (filtered.isNotEmpty()) return filtered
                        }
                    }
                }

                // Fallback to anchor prefix
                val anchorQueries = findQueriesByAnchor(anchor)
                for (candidateQuery in anchorQueries) {
                    val results = searchUnidex(candidateQuery, preference)
                    if (results.isNotEmpty()) {
                        val filtered = results.filter { hasTopicAsset(it["topic_id"] ?: "") }
                        if (filtered.isNotEmpty()) return filtered
                    }
                }
            }
        }

        return emptyList()
    }

    private fun searchUnidex(query: String, preference: String): List<Map<String, String>> {
        val db = dbManager.getUnidexDb()
        val sql = """
            SELECT q.qbtype, x.topic_hits as hits
            FROM query q, query_topic x
            WHERE q.disp = ? AND x.nqid = q.nqid AND x.pref = ?
        """

        val hitsBlob = db.useQueryFirstOrNull(
            sql,
            bind = {
                it.bindText(1, query)
                it.bindText(2, preference)
            }
        ) { stmt ->
            if (stmt.isNull(1)) null else stmt.getBlob(1)
        }

        return if (hitsBlob != null) {
            parseHitsBlob(db, hitsBlob)
        } else {
            emptyList()
        }
    }

    private fun parseHitsBlob(db: SQLiteConnection, hitsBlob: ByteArray): List<Map<String, String>> {
        val hexString = hitsBlob.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
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

        val sql = """
            SELECT topic_id, title
            FROM topic
            WHERE topic_id IN ($idsStr)
            ORDER BY CASE topic_id $caseStr END
        """

        return db.useQuery(sql) { stmt ->
            mapOf(
                "topic_id" to stmt.getText(0),
                "title" to stmt.getText(1)
            )
        }
    }

    private fun hasTopicAsset(topicId: String): Boolean {
        if (topicId.isEmpty()) return false
        val numericId = Regex("""\d+""").find(topicId.trim())?.value ?: topicId.trim().removePrefix("topic-").removePrefix("Topic-")
        return try {
            val db = dbManager.getAssetsDb()
            val exists = db.useQueryFirstOrNull(
                "SELECT 1 FROM topic_asset WHERE id = ? LIMIT 1",
                bind = { it.bindText(1, numericId) }
            ) { true }
            exists ?: false
        } catch (_: Exception) {
            false
        }
    }
}
