package com.clinref.app.domain.ai

import com.clinref.app.data.OutlineGraphicItem
import com.clinref.app.data.OutlineSectionItem
import com.clinref.app.data.RelatedTopicItem
import com.clinref.app.data.SearchResultTopicItem
import com.clinref.app.data.SearchTopicsResponse
import com.clinref.app.data.TopicOverviewResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionGatesTest {

    private val json = AiJsonUtils.json

    @Test
    fun `rankSearchCandidates scores topics sorts descending and respects thresholds and fallback`() = runTest {
        val candidates = listOf(
            SearchResultTopicItem(id = "Topic-1", title = "General headache"),
            SearchResultTopicItem(id = "Topic-2", title = "Cluster headache acute treatment"),
            SearchResultTopicItem(id = "Topic-3", title = "Sumatriptan drug monograph", type = "drug")
        )

        val transport = DecisionTransport { reqJson ->
            val qs = json.parseToJsonElement(reqJson).jsonObject["questions"]!!.jsonObject
            val answers = buildJsonObject {
                put(
                    "requires_drug_monograph",
                    buildJsonObject { put("type", "noul"); put("noul", 0.82); put("confidence", 0.9) }
                )
                put(
                    "requires_table_data",
                    buildJsonObject { put("type", "noul"); put("noul", 0.40); put("confidence", 0.8) }
                )
                qs.keys.filter { it.startsWith("topic_") }.forEach { key ->
                    val s = when {
                        key.contains("Topic-2") -> 2.85
                        key.contains("Topic-3") -> 2.10
                        else -> 0.60
                    }
                    put(
                        key,
                        buildJsonObject {
                            put("type", "score")
                            put("score", s)
                            put("legend", buildJsonObject { put("0", "0"); put("1", "1"); put("2", "2"); put("3", "3") })
                            put("confidence", 0.85)
                        }
                    )
                }
            }
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("answers", answers)
                    put("usage", buildJsonObject { put("total_input_tokens", 320); put("total_tokens", 320) })
                }
            )
        }

        val client = DecisionClient(transport)
        val res = DecisionGates.rankSearchCandidates(
            client = client,
            question = "Dose of subcutaneous sumatriptan for cluster headache?",
            patientProfileText = "Age 35y, male",
            query = "cluster headache sumatriptan",
            candidates = candidates
        )

        assertTrue(res.ok)
        assertTrue(res.requiresDrugMonograph)
        assertFalse(res.requiresTableData)
        assertEquals(listOf("Topic-2", "Topic-3", "Topic-1"), res.rankedTopics.map { it.id })
        assertEquals(listOf("Topic-2", "Topic-3"), res.topTopics.map { it.id })
        assertEquals(320, res.usage.totalInputTokens)

        // Fallback to top 2 when no candidate passes a high threshold
        val strictRes = DecisionGates.rankSearchCandidates(
            client = client,
            question = "Dose?",
            patientProfileText = "",
            query = "cluster headache",
            candidates = candidates,
            config = DecisionConfig(topicScoreThreshold = 2.95)
        )
        assertEquals(listOf("Topic-2", "Topic-3"), strictRes.topTopics.map { it.id })
    }

    @Test
    fun `rankSearchCandidates returns empty success with 0 calls on empty candidates`() = runTest {
        var calls = 0
        val client = DecisionClient(DecisionTransport { calls++; "{}" })
        val res = DecisionGates.rankSearchCandidates(
            client = client,
            question = "Test",
            patientProfileText = "",
            query = "nothing",
            candidates = emptyList()
        )
        assertTrue(res.ok)
        assertEquals(0, calls)
    }

    @Test
    fun `rankOverviewItems skips calculator topics with zero calls`() = runTest {
        var calls = 0
        val client = DecisionClient(DecisionTransport { calls++; "{}" })
        val calcOverview = TopicOverviewResponse(
            topicId = "Topic-Calc-1",
            title = "CrCl Calculator",
            topicType = "calc",
            sections = listOf(OutlineSectionItem("S1", "Formula")),
            markdown = "CrCl = ..."
        )

        val res = DecisionGates.rankOverviewItems(
            client = client,
            question = "Calculate CrCl",
            patientProfileText = "",
            overview = calcOverview
        )
        assertTrue(res.ok)
        assertEquals(0, calls)
        assertTrue(res.recommendedSectionIds.isEmpty())
        assertEquals(1, res.rankedSections.size)
        assertEquals("FULL", res.rankedSections[0].id)
        assertEquals(3.0, res.rankedSections[0].score, 0.001)
    }

    @Test
    fun `rankOverviewItems filters non-table graphics enforces limits and skips drug monograph check for drug topics`() = runTest {
        var capturedKeys: Set<String> = emptySet()
        val transport = DecisionTransport { reqJson ->
            val qs = json.parseToJsonElement(reqJson).jsonObject["questions"]!!.jsonObject
            capturedKeys = qs.keys
            val answers = buildJsonObject {
                qs.keys.forEach { key ->
                    if (key == "needs_drug_monograph") {
                        put(key, buildJsonObject { put("type", "noul"); put("noul", 0.90); put("confidence", 0.9) })
                    } else {
                        val score = when {
                            key.endsWith("_S10") || key.endsWith("_G_TBL") || key.endsWith("_REL_1") -> 2.7
                            key.endsWith("_S11") -> 2.2
                            else -> 0.5
                        }
                        put(
                            key,
                            buildJsonObject {
                                put("type", "score")
                                put("score", score)
                                put("legend", buildJsonObject { put("0", "0"); put("1", "1"); put("2", "2"); put("3", "3") })
                                put("confidence", 0.9)
                            }
                        )
                    }
                }
            }
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("answers", answers)
                    put("usage", buildJsonObject { put("total_input_tokens", 500) })
                }
            )
        }

        val sections = (1..165).map { idx ->
            OutlineSectionItem(id = "S$idx", title = "Section $idx", level = if (idx == 10) 2 else 1, parentId = if (idx == 10) "S1" else null)
        }
        val graphics = listOf(
            OutlineGraphicItem(id = "G_PHOTO", title = "Skin Rash Photo", isTable = false),
            OutlineGraphicItem(id = "G_TBL", title = "Antibiotic Dosing Table", isTable = true)
        )
        val related = (1..35).map { idx -> RelatedTopicItem(id = "REL_$idx", title = "Related $idx") }

        val diseaseOverview = TopicOverviewResponse(
            topicId = "Topic-CAP",
            title = "Community-acquired pneumonia",
            topicType = "medical",
            sections = sections,
            graphics = graphics,
            relatedTopics = related
        )

        val client = DecisionClient(transport)
        val res = DecisionGates.rankOverviewItems(
            client = client,
            question = "Outpatient CAP dose?",
            patientProfileText = "",
            overview = diseaseOverview
        )

        assertTrue(res.ok)
        assertTrue(res.needsDrugMonograph)
        assertTrue("needs_drug_monograph" in capturedKeys)
        assertEquals(minOf(sections.size, DecisionConfig.MAX_OUTLINE_SECTIONS_TO_SCORE), capturedKeys.count { it.startsWith("sec_") })
        assertEquals(1, capturedKeys.count { it.startsWith("gfx_") })
        assertEquals(25, capturedKeys.count { it.startsWith("rel_") })
        assertFalse(capturedKeys.any { it.contains("G_PHOTO") })
        assertEquals(listOf("S10", "S11"), res.recommendedSectionIds)
        assertEquals(listOf("G_TBL"), res.recommendedGraphicIds)
        assertEquals(listOf("REL_1"), res.recommendedRelatedTopics.map { it.id })

        // Drug topic should skip needs_drug_monograph question
        val drugOverview = diseaseOverview.copy(topicType = "drug", relatedTopics = emptyList())
        val drugRes = DecisionGates.rankOverviewItems(
            client = client,
            question = "Renal adjustment?",
            patientProfileText = "",
            overview = drugOverview
        )
        assertFalse("needs_drug_monograph" in capturedKeys)
        assertFalse(drugRes.needsDrugMonograph)
    }

    @Test
    fun `injectRerankIntoToolJson injects decisionRerank metadata and skips error payloads`() {
        val searchJson = json.encodeToString(
            SearchTopicsResponse(
                query = "sepsis",
                results = listOf(SearchResultTopicItem("T1", "Sepsis management"))
            )
        )
        val searchRerank = SearchRerankResult(
            topTopics = listOf(RankedItem(id = "T1", title = "Sepsis management", score = 2.9)),
            requiresDrugMonograph = true,
            requiresTableData = false,
            ok = true
        )
        val enrichedSearch = DecisionGates.injectSearchRerankIntoJson(searchJson, searchRerank, json)
        val searchObj = json.parseToJsonElement(enrichedSearch).jsonObject
        val rerankBlock = searchObj["decisionRerank"]!!.jsonObject
        assertEquals("T1", rerankBlock["topTopics"]!!.jsonArray[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull)
        assertTrue(rerankBlock["requiresDrugMonograph"]?.jsonPrimitive?.booleanOrNull == true)
        assertTrue(rerankBlock["hint"]?.jsonPrimitive?.contentOrNull?.contains("T1") == true)

        val overviewJson = json.encodeToString(TopicOverviewResponse(topicId = "T1", title = "Sepsis"))
        val overviewRerank = OverviewRerankResult(
            topicId = "T1",
            topicTitle = "Sepsis",
            recommendedSectionIds = listOf("S2", "S5"),
            recommendedGraphicIds = listOf("G9"),
            ok = true
        )
        val enrichedOverview = DecisionGates.injectOverviewRerankIntoJson(
            toolJson = overviewJson,
            rerank = overviewRerank,
            autoPrefetched = true,
            prefetchedSectionsMarkdown = "Empiric vancomycin plus piperacillin-tazobactam",
            prefetchedTablesMarkdown = "| Drug | Dose |",
            json = json
        )
        val ovBlock = json.parseToJsonElement(enrichedOverview).jsonObject["decisionRerank"]!!.jsonObject
        assertEquals(true, ovBlock["autoPrefetched"]?.jsonPrimitive?.booleanOrNull)

        val errorJson = """{"error":"Topic not found"}"""
        assertEquals(errorJson, DecisionGates.injectSearchRerankIntoJson(errorJson, searchRerank, json))
        assertNull(json.parseToJsonElement(errorJson).jsonObject["decisionRerank"])
    }

    @Test
    fun `injectOverviewRerankIntoJson does not emit low relevance hint for calculator topics`() {
        val calcOverviewJson = json.encodeToString(
            TopicOverviewResponse(
                topicId = "Topic-Calc-1",
                title = "CrCl Calculator",
                topicType = "calc",
                markdown = "CrCl = ..."
            )
        )
        val calcRerank = OverviewRerankResult(
            topicId = "Topic-Calc-1",
            topicTitle = "CrCl Calculator",
            rankedSections = listOf(RankedItem(id = "FULL", title = "CrCl Calculator", score = 3.0)),
            recommendedSectionIds = emptyList(),
            ok = true
        )
        val enriched = DecisionGates.injectOverviewRerankIntoJson(
            toolJson = calcOverviewJson,
            rerank = calcRerank,
            autoPrefetched = true,
            json = json
        )
        val block = json.parseToJsonElement(enriched).jsonObject["decisionRerank"]!!.jsonObject
        val hint = block["hint"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertFalse(hint.contains("low relevance (<1.0)"))
    }

    @Test
    fun `rankOverviewItems default limits allow up to 8 sections and 4 tables`() = runTest {
        val transport = DecisionTransport { reqJson ->
            val qs = json.parseToJsonElement(reqJson).jsonObject["questions"]!!.jsonObject
            val answers = buildJsonObject {
                qs.keys.forEach { key ->
                    if (key == "needs_drug_monograph") {
                        put(key, buildJsonObject { put("type", "noul"); put("noul", 0.2); put("confidence", 0.9) })
                    } else {
                        put(
                            key,
                            buildJsonObject {
                                put("type", "score")
                                put("score", 2.6)
                                put("legend", buildJsonObject { put("0", "0"); put("1", "1"); put("2", "2"); put("3", "3") })
                                put("confidence", 0.9)
                            }
                        )
                    }
                }
            }
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("answers", answers)
                    put("usage", buildJsonObject { put("total_input_tokens", 120) })
                }
            )
        }

        val overview = TopicOverviewResponse(
            topicId = "Topic-Large",
            title = "Large Topic",
            topicType = "medical",
            sections = (1..22).map { OutlineSectionItem("S$it", "Section $it") },
            graphics = (1..10).map { OutlineGraphicItem("G$it", "Table $it", isTable = true) }
        )

        val res = DecisionGates.rankOverviewItems(
            client = DecisionClient(transport),
            question = "Full management and dosing?",
            patientProfileText = "",
            overview = overview
        )

        assertTrue(res.ok)
        assertEquals(8, res.recommendedSectionIds.size)
        assertEquals(4, res.recommendedGraphicIds.size)
    }

    @Test
    fun `rankOverviewItems does not penalize parent if child does not meet sectionScoreThreshold`() = runTest {
        val overview = TopicOverviewResponse(
            topicId = "Topic-ParentChild",
            title = "Hypertension Management",
            topicType = "medical",
            sections = listOf(
                OutlineSectionItem(id = "sec-parent", title = "Initial Blood Pressure Management", level = 1),
                OutlineSectionItem(id = "sec-child", title = "Lifestyle Modifications", level = 2, parentId = "sec-parent")
            )
        )

        // Parent scores 1.9 (passes threshold 1.8), Child scores 1.6 (fails threshold 1.8)
        val transport = DecisionTransport { reqJson ->
            val qs = json.parseToJsonElement(reqJson).jsonObject["questions"]!!.jsonObject
            val answers = buildJsonObject {
                put("is_topic_relevant", buildJsonObject { put("type", "noul"); put("noul", 0.95); put("confidence", 0.9) })
                qs.keys.filter { it.startsWith("sec_") }.forEach { key ->
                    val s = if (key.contains("parent")) 1.9 else 1.6
                    put(
                        key,
                        buildJsonObject {
                            put("type", "score")
                            put("score", s)
                            put("confidence", 0.9)
                        }
                    )
                }
            }
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("answers", answers)
                    put("usage", buildJsonObject { put("total_input_tokens", 80) })
                }
            )
        }

        val res = DecisionGates.rankOverviewItems(
            client = DecisionClient(transport),
            question = "How to treat hypertension?",
            patientProfileText = "",
            overview = overview,
            config = DecisionConfig(sectionScoreThreshold = 1.8)
        )

        assertTrue(res.ok)
        // Parent must be retained and recommended because child failed the threshold and was dropped
        assertEquals(listOf("sec-parent"), res.recommendedSectionIds)
    }

    @Test
    fun `injectOverviewRerankIntoJson suppresses related topic hints unless needsRelatedTopic is true`() {
        val overviewJson = json.encodeToString(TopicOverviewResponse(topicId = "T1", title = "AFib"))
        val rerank = OverviewRerankResult(
            topicId = "T1",
            topicTitle = "AFib",
            recommendedSectionIds = listOf("S1"),
            recommendedRelatedTopics = listOf(RankedItem(id = "REL_1", title = "AFib in Pregnancy", score = 2.1)),
            ok = true
        )

        val suppressed = DecisionGates.injectOverviewRerankIntoJson(
            toolJson = overviewJson,
            rerank = rerank,
            needsRelatedTopic = false,
            json = json
        )
        val suppressedBlock = json.parseToJsonElement(suppressed).jsonObject["decisionRerank"]!!.jsonObject
        val suppressedHint = suppressedBlock["hint"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertFalse(suppressedHint.contains("Promising related topics"))
        assertEquals(0, suppressedBlock["recommendedRelatedTopics"]!!.jsonArray.size)

        val enabled = DecisionGates.injectOverviewRerankIntoJson(
            toolJson = overviewJson,
            rerank = rerank,
            needsRelatedTopic = true,
            json = json
        )
        val enabledBlock = json.parseToJsonElement(enabled).jsonObject["decisionRerank"]!!.jsonObject
        val enabledHint = enabledBlock["hint"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue(enabledHint.contains("Promising related topics"))
        assertEquals(1, enabledBlock["recommendedRelatedTopics"]!!.jsonArray.size)
    }

    @Test
    fun `buildPrefetchedDigest summarizes sections and tables within maxChars`() {
        val secMd = """
            === Section: S1 ===
            Empiric therapy includes vancomycin plus piperacillin-tazobactam within 1 hour.

            === Section: S2 ===
            Adjust vancomycin trough levels based on renal clearance.
        """.trimIndent()
        val tblMd = """
            ### Graphic Table: Empiric Dosing
            | Agent | Dose |
            | Vancomycin | 15 mg/kg |
        """.trimIndent()

        val digest = DecisionGates.buildPrefetchedDigest(secMd, tblMd, maxChars = 400)
        requireNotNull(digest)
        assertTrue(digest.contains("S1"))
        assertTrue(digest.contains("S2"))
        assertTrue(digest.contains("Empiric Dosing"))
        assertTrue(digest.length <= 401)
    }

    @Test
    fun `rankOverviewItems includes H1 headings beyond 15 in overviewDigest`() = runTest {
        var capturedInputText = ""
        val transport = DecisionTransport { reqJson ->
            val root = json.parseToJsonElement(reqJson).jsonObject
            capturedInputText = root["input"]!!.jsonArray.joinToString("\n") {
                it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val qs = root["questions"]!!.jsonObject
            val answers = buildJsonObject {
                qs.keys.forEach { k ->
                    if (k == "is_topic_relevant" || k == "needs_drug_monograph") {
                        put(k, buildJsonObject { put("type", "noul"); put("noul", 0.5); put("confidence", 0.9) })
                    } else {
                        put(k, buildJsonObject { put("type", "score"); put("score", 2.0); put("confidence", 0.9) })
                    }
                }
            }
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("answers", answers)
                    put("usage", buildJsonObject { put("total_input_tokens", 100) })
                }
            )
        }

        val overview = TopicOverviewResponse(
            topicId = "Drug-Long",
            title = "Long Drug Monograph",
            topicType = "drug",
            sections = (1..25).map { idx ->
                OutlineSectionItem(id = "H$idx", title = "Heading $idx", level = 1)
            },
            graphics = listOf(
                OutlineGraphicItem(id = "G_DOSING", title = "Empiric IV Antimicrobial Dosing", isTable = true),
                OutlineGraphicItem(id = "G_IMG", title = "Rash Photo", isTable = false)
            )
        )

        val res = DecisionGates.rankOverviewItems(
            client = DecisionClient(transport),
            question = "Renal dosing?",
            patientProfileText = "",
            overview = overview
        )

        assertTrue(res.ok)
        assertTrue(capturedInputText.contains("- Heading 16"))
        assertTrue(capturedInputText.contains("- Heading 25"))
        assertTrue(capturedInputText.contains("Available Tables:"))
        assertTrue(capturedInputText.contains("- Empiric IV Antimicrobial Dosing"))
        assertFalse(capturedInputText.contains("Rash Photo"))
    }

    @Test
    fun `injectOverviewRerankIntoJson prunes low-scoring sections even when autoPrefetched is false`() {
        val overview = TopicOverviewResponse(
            topicId = "T_Large",
            title = "Large Topic",
            sections = (1..20).map { idx -> OutlineSectionItem(id = "S$idx", title = "Section $idx") }
        )
        val rerank = OverviewRerankResult(
            topicId = "T_Large",
            topicTitle = "Large Topic",
            rankedSections = (1..20).map { idx ->
                RankedItem(id = "S$idx", title = "Section $idx", score = if (idx <= 3) 2.5 else 0.4)
            },
            recommendedSectionIds = listOf("S1", "S2", "S3"),
            ok = true
        )

        val enriched = DecisionGates.injectOverviewRerankIntoJson(
            toolJson = json.encodeToString(overview),
            rerank = rerank,
            autoPrefetched = false,
            json = json
        )
        val parsed = json.parseToJsonElement(enriched).jsonObject
        val keptIds = parsed["sections"]!!.jsonArray.mapNotNull {
            it.jsonObject["id"]?.jsonPrimitive?.contentOrNull
        }
        assertEquals(listOf("S1", "S2", "S3"), keptIds)
    }
}
