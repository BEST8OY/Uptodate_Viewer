package com.clinref.app.adversarial

import com.clinref.app.data.MedicalDatabaseTools
import com.clinref.app.data.TopicOverviewResponse
import com.clinref.app.repository.AssetRepository
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.SearchRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdversarialCompoundRetrievalTest {

    private lateinit var searchRepository: SearchRepository
    private lateinit var contentRepository: ContentRepository
    private lateinit var assetRepository: AssetRepository
    private lateinit var tools: MedicalDatabaseTools
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        searchRepository = mockk(relaxed = true)
        contentRepository = mockk(relaxed = true)
        assetRepository = mockk(relaxed = true)

        tools = MedicalDatabaseTools(
            searchRepository = searchRepository,
            contentRepository = contentRepository,
            assetRepository = assetRepository
        )
    }

    // =========================================================================
    // 1. MALFORMED TOPIC IDS & SQL INJECTION
    // =========================================================================

    @Test
    fun testMalformedTopicIdsReturnCleanStructuredError() {
        val malformedIds = listOf(
            "",
            "   ",
            "\t\n",
            "abc",
            "topic_foo",
            "invalid!@#",
            "-1",
            "-999",
            "1' OR 1=1--",
            "1; DROP TABLE topic_asset;--",
            "' UNION SELECT * FROM topic_asset--",
            "💊",
            "🩺",
            "123\u0000",
            "١٢٣",
            "🔥",
            "999999999999999999999999999999"
        )

        for (id in malformedIds) {
            // When repository returns null for invalid topic ID
            every { contentRepository.getTopicContent(id.trim()) } returns null

            val res = tools.getTopicOverview(id)
            assertNotNull("Response must not be null for id: '$id'", res)

            val obj = json.parseToJsonElement(res).jsonObject
            assertTrue("Response must contain 'error' key for malformed id: '$id'", obj.containsKey("error"))
            val errMsg = obj["error"]!!.jsonPrimitive.content
            assertTrue("Error message should mention topic not found for '$id'", errMsg.contains("Topic not found"))
        }
    }

    @Test
    fun testContentDaoRejectsMalformedAndInjectionTopicIds() {
        val dbManager = mockk<com.clinref.app.data.DatabaseManager>(relaxed = true)
        val contentDao = com.clinref.app.data.ContentDao(dbManager)

        val malformed = listOf(
            "",
            "   ",
            "-1",
            "-999",
            "1' OR 1=1--",
            "1; DROP TABLE topic_asset;--",
            "' UNION SELECT * FROM topic_asset--",
            "topic-1-bad",
            "abc123def",
            "💊",
            "123\u0000"
        )
        for (id in malformed) {
            val content = contentDao.getTopicContent(id)
            org.junit.Assert.assertNull("ContentDao must return null for malformed topic ID '$id'", content)
            val title = contentDao.getTopicTitle(id)
            org.junit.Assert.assertNull("ContentDao must return null for malformed topic title '$id'", title)
        }
        // Verify: DatabaseManager was NEVER queried because regex ^(?:topic-)?(\\d+)$ rejects all malformed IDs immediately
        io.mockk.verify(exactly = 0) { dbManager.getAssetsDb() }
    }

    // =========================================================================
    // 2. NON-EXISTENT TOPIC IDS
    // =========================================================================

    @Test
    fun testNonExistentTopicIdsReturnCleanStructuredError() {
        val nonexistentIds = listOf("99999999", "0", "404")
        for (id in nonexistentIds) {
            every { contentRepository.getTopicContent(id) } returns null
            val res = tools.getTopicOverview(id)
            val obj = json.parseToJsonElement(res).jsonObject
            assertTrue(obj.containsKey("error"))
            assertEquals("Topic not found: $id", obj["error"]!!.jsonPrimitive.content)
        }
    }

    // =========================================================================
    // 3. CALCULATOR TOPICS
    // =========================================================================

    @Test
    fun testCalculatorTopicsHandledGracefullyWithoutOutlineCrash() {
        val calcBody = """<div class="calculator"><h3>GFR Cockcroft-Gault</h3><p>Inputs: Age, Weight, Serum Creatinine.</p></div>"""
        every { contentRepository.getTopicContent("148929") } returns ContentRepository.TopicContent(
            outlineHtml = "",
            bodyHtml = calcBody
        )
        every { contentRepository.getTopicTitle("148929") } returns "Cockcroft-Gault CrCl Calculator"

        val res = tools.getTopicOverview("148929")
        val obj = json.parseToJsonElement(res).jsonObject

        assertFalse("Calculator must not return error", obj.containsKey("error"))
        assertEquals("148929", obj["topicId"]!!.jsonPrimitive.content)
        assertEquals("calc", obj["topicType"]!!.jsonPrimitive.content)

        val decoded = json.decodeFromString<TopicOverviewResponse>(res)
        assertEquals("calc", decoded.topicType)
        assertEquals(1, decoded.sections.size)
        assertEquals("FULL", decoded.sections[0].id)
        assertEquals("Calculator Tool Content", decoded.sections[0].title)

        assertTrue(decoded.markdown.contains("=== Calculator: Cockcroft-Gault CrCl Calculator ==="))
        assertTrue(decoded.markdown.contains("Inputs: Age, Weight, Serum Creatinine."))
    }

    // =========================================================================
    // 4. BOUNDARY SECTION LISTS AND OVERVIEW
    // =========================================================================

    @Test
    fun testBoundarySectionListsAndOverview() {
        val outlineHtml = """
            <ul id="innerOutline">
                <li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Overview</a></li>
                <li><a href="appAction({&quot;section&quot;:&quot;H_SR&quot;})"><span class="legacyTopicViewHyphen">- </span>Summary &amp; Recommendations</a></li>
                <li><a href="appAction({&quot;section&quot;:&quot;H_DOSE&quot;})">Dosing &amp; Administration</a></li>
            </ul>
        """.trimIndent()
        val bodyHtml = """
            <p class="headingAnchor" id="H1"><span class="h1">Overview</span><span class="headingEndMark">—</span>General.</p>
            <p class="headingAnchor" id="H_SR"><span class="h1">Summary &amp; Recommendations</span><span class="headingEndMark">—</span>Guidance.</p>
            <p class="headingAnchor" id="H_DOSE"><span class="h1">Dosing &amp; Administration</span><span class="headingEndMark">—</span>5 mg daily.</p>
        """.trimIndent()

        every { contentRepository.getTopicContent("100") } returns ContentRepository.TopicContent(
            outlineHtml = outlineHtml,
            bodyHtml = bodyHtml
        )
        every { contentRepository.getTopicTitle("100") } returns "Anticoagulation Overview"

        // Case A: Valid topic -> Outline returned, no auto-prefetching for articles
        val res = tools.getTopicOverview("100")
        val dec = json.decodeFromString<TopicOverviewResponse>(res)
        assertEquals(3, dec.sections.size)
        assertEquals("article", dec.topicType)
        assertTrue(dec.markdown.isEmpty())
        val rawObj = json.parseToJsonElement(res).jsonObject
        assertFalse(rawObj.containsKey("prefetchedSections"))
        assertFalse(rawObj.containsKey("highYieldSections"))

        // Case B: Non-existent topic -> Clean error without crash
        every { contentRepository.getTopicContent("nonexistent_999") } returns null
        val resNonExistent = tools.getTopicOverview("nonexistent_999")
        val decNonExistent = json.parseToJsonElement(resNonExistent).jsonObject
        assertTrue(decNonExistent.containsKey("error"))

        // Case C: Whitespace topic ID
        val resWhitespace = tools.getTopicOverview("   100   ")
        val decWhitespace = json.decodeFromString<TopicOverviewResponse>(resWhitespace)
        assertEquals("100", decWhitespace.topicId)
    }

    // =========================================================================
    // 5. HIGH CONCURRENCY / RAPID CALLS
    // =========================================================================

    @Test
    fun testHighConcurrencyRapidCalls() = runBlocking(Dispatchers.Default) {
        val outlineHtml = """
            <ul id="innerOutline">
                <li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Overview</a></li>
                <li><a href="appAction({&quot;section&quot;:&quot;H_SR&quot;})">Summary &amp; Recommendations</a></li>
            </ul>
        """.trimIndent()
        val bodyHtml = """
            <p id="H1">Overview content</p>
            <p id="H_SR">Summary content</p>
        """.trimIndent()

        every { contentRepository.getTopicContent("valid_1") } returns ContentRepository.TopicContent(
            outlineHtml = outlineHtml,
            bodyHtml = bodyHtml
        )
        every { contentRepository.getTopicTitle("valid_1") } returns "Topic 1"

        every { contentRepository.getTopicContent("valid_2") } returns ContentRepository.TopicContent(
            outlineHtml = "",
            bodyHtml = "<p>Calculator content</p>"
        )
        every { contentRepository.getTopicTitle("valid_2") } returns "Calc 2"

        every { contentRepository.getTopicContent("invalid") } returns null

        val jobs = (1..100).map { i ->
            async {
                val id = when (i % 3) {
                    0 -> "valid_1"
                    1 -> "valid_2"
                    else -> "invalid"
                }
                tools.getTopicOverview(id)
            }
        }

        val results = jobs.awaitAll()
        assertEquals(100, results.size)
        for (res in results) {
            val obj = json.parseToJsonElement(res).jsonObject
            assertTrue(obj.containsKey("topicId") || obj.containsKey("error"))
        }
    }
}
