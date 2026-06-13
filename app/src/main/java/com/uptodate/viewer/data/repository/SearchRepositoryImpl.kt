package com.uptodate.viewer.data.repository

import android.database.sqlite.SQLiteDatabase
import com.uptodate.viewer.data.database.DatabaseManager
import com.uptodate.viewer.data.database.ext.blob
import com.uptodate.viewer.data.database.ext.int
import com.uptodate.viewer.data.database.ext.mapEach
import com.uptodate.viewer.data.database.ext.string
import com.uptodate.viewer.data.database.search.SearchRepository
import com.uptodate.viewer.data.database.search.models.SearchResultRow
import com.uptodate.viewer.data.database.search.models.Suggestion
import com.uptodate.viewer.util.SearchColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SearchRepositoryImpl(
    private val dbManager: DatabaseManager
) : SearchRepository {

    override suspend fun getTopicTitle(topicId: String): String? = withContext(Dispatchers.IO) {
        val db = try { dbManager.getUnidexDb() } catch (_: Exception) { return@withContext null }
        val cursor = db.rawQuery("SELECT title FROM topic WHERE topic_id = ? LIMIT 1", arrayOf(topicId))
        cursor.use {
            if (!it.moveToFirst()) return@withContext null
            it.string("title")
        }
    }

    override suspend fun getSuggestions(query: String): List<Suggestion> = withContext(Dispatchers.IO) {
        val db = try { dbManager.getUnidexDb() } catch (_: Exception) { return@withContext emptyList() }
        val where = when {
            query.length == 1 -> "${SearchColumns.D1} = ?"
            query.length == 2 -> "${SearchColumns.D2} = ?"
            query.length == 3 -> "${SearchColumns.D3} = ?"
            else -> "disp LIKE ?"
        }
        val param = if (query.length < 4) query else "$query%"
        val cursor = db.rawQuery(
            "SELECT disp, weight FROM query WHERE hide IS NULL AND $where ORDER BY weight DESC, disp ASC LIMIT 30",
            arrayOf(param)
        )
        cursor.use {
            it.mapEach { row ->
                Suggestion(
                    word = row.string("disp") ?: "",
                    weight = row.int("weight") ?: 0
                )
            }
        }
    }

    override suspend fun searchTopics(query: String, preference: String): List<SearchResultRow> = withContext(Dispatchers.IO) {
        // Tier 1: unidex.en.sqlite primary search
        val unidexDb = try { dbManager.getUnidexDb() } catch (_: Exception) { null }
        if (unidexDb != null) {
            val results = primarySearch(unidexDb, query, preference)
            if (results.isNotEmpty()) return@withContext results
        }

        // Tier 2: fcontentsearch.db FTS
        val fcsDb = try { dbManager.getFcontentsearchDb() } catch (_: Exception) { null }
        if (fcsDb != null) {
            val results = ftsSearch(fcsDb, query)
            if (results.isNotEmpty()) return@withContext results
        }

        // Tier 3: fsearch.db FTS
        val fsDb = try { dbManager.getFsearchDb() } catch (_: Exception) { null }
        if (fsDb != null) {
            return@withContext ftsSearch(fsDb, query)
        }

        emptyList()
    }

    private fun primarySearch(db: SQLiteDatabase, query: String, preference: String): List<SearchResultRow> {
        // Get topic hits from query_topic
        val cursor = db.rawQuery(
            """SELECT q.qbtype, x.topic_hits as hits
               FROM query q, query_topic x
               WHERE q.disp = ? AND x.nqid = q.nqid AND x.pref = ?""",
            arrayOf(query, preference)
        )
        return cursor.use {
            val allHits = mutableListOf<String>()
            while (it.moveToNext()) {
                val blob = it.blob("hits") ?: continue
                parseHitsBlob(blob, allHits)
            }
            if (allHits.isEmpty()) return@use emptyList()

            // Fetch titles preserving order
            val placeholders = allHits.joinToString(",") { "?" }
            val caseWhen = allHits.withIndex().joinToString(" ") { (i, id) ->
                "WHEN ? THEN $i"
            }
            val params = allHits.flatMap { listOf(it, it) }.toTypedArray()
            val titleCursor = db.rawQuery(
                """SELECT topic_id, title FROM topic
                   WHERE topic_id IN ($placeholders)
                   ORDER BY CASE topic_id $caseWhen END""",
                params
            )
            titleCursor.use { titleCur ->
                titleCur.mapEach { row ->
                    SearchResultRow(
                        topicId = row.string("topic_id") ?: "",
                        title = row.string("title") ?: ""
                    )
                }
            }
        }
    }

    private fun parseHitsBlob(blob: ByteArray, output: MutableList<String>) {
        if (blob.size < 4) return
        val hex = blob.joinToString("") { byte -> "%02x".format(byte) }
        if (hex.length < 8) return
        // Skip first 4 hex chars (2 bytes)
        val relevant = hex.substring(4)
        if (relevant.length < 8) return
        for (i in 0..relevant.length - 8 step 8) {
            val chunk = relevant.substring(i, i + 8)
            val id = chunk.toIntOrNull(16) ?: continue
            if (id > 0) output.add(id.toString())
        }
    }

    private fun ftsSearch(db: SQLiteDatabase, query: String): List<SearchResultRow> {
        val sanitizedQuery = query
            .replace("\"", "")
            .replace("'", "")
            .replace(Regex("[()*+:^~\\-]"), "")
            .trim()
        if (sanitizedQuery.isBlank()) return emptyList()
        val ftsQuery = "\"$sanitizedQuery\" AND URL:topic"
        return try {
            val cursor = db.rawQuery(
                "SELECT Text as title, URL as topic_id FROM search WHERE search MATCH ? ORDER BY rank(matchinfo(search)) DESC LIMIT 20",
                arrayOf(ftsQuery)
            )
            cursor.use {
                it.mapEach { row ->
                    SearchResultRow(
                        topicId = row.string("topic_id")?.removePrefix("topic-") ?: "",
                        title = row.string("title") ?: ""
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
