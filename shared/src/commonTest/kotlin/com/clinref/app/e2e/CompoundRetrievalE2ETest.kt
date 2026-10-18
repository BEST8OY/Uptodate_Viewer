package com.clinref.app.e2e

import com.clinref.app.data.MedicalDatabaseTools
import com.clinref.app.domain.GraphicData
import com.clinref.app.domain.SearchResult
import com.clinref.app.repository.AssetRepository
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.SearchRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end opaque-box tests for compound retrieval contracts and granular database tool access.
 *
 * Verifies:
 * 1. Batch compound section retrieval bundling outline metadata and markdown in a single round-trip.
 * 2. Smart section resolution: exact match, case-insensitive match, and section title match.
 * 3. Fallback and structured error handling for invalid or missing section IDs.
 * 4. Calculator topic handling: returns "calc" topicType and "FULL" section without outline failures.
 * 5. Structured JSON error envelopes for non-existent topics.
 * 6. Graphic table markdown conversion and rejection of non-table graphics.
 * 7. Preservation of all 6 granular database tools with bidirectional serialization schemas.
 */
class CompoundRetrievalE2ETest {

    private lateinit var searchRepository: SearchRepository
    private lateinit var contentRepository: ContentRepository
    private lateinit var assetRepository: AssetRepository
    private lateinit var databaseTools: MedicalDatabaseTools
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        searchRepository = mockk(relaxed = true)
        contentRepository = mockk(relaxed = true)
        assetRepository = mockk(relaxed = true)

        databaseTools = MedicalDatabaseTools(
            searchRepository = searchRepository,
            contentRepository = contentRepository,
            assetRepository = assetRepository
        )
    }

    @Test
    fun testCompoundBatchRetrievalBundlesMultipleSectionsInSingleCall() {
        val outlineHtml = """
            <a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Summary & Recommendations</a>
            <a href="appAction({&quot;section&quot;:&quot;H2&quot;})">Dosing & Administration</a>
            <a href="appAction({&quot;section&quot;:&quot;H3&quot;})">Adverse Effects</a>
        """.trimIndent()

        val bodyHtml = """
            <h2 id="H1">Summary & Recommendations</h2>
            <p>Apixaban is recommended for stroke prevention in nonvalvular AF.</p>
            <h2 id="H2">Dosing & Administration</h2>
            <p>The standard dose is 5 mg orally twice daily.</p>
            <h2 id="H3">Adverse Effects</h2>
            <p>Major bleeding is the primary risk.</p>
        """.trimIndent()

        every { contentRepository.getTopicContent("128998") } returns ContentRepository.TopicContent(
            outlineHtml = outlineHtml,
            bodyHtml = bodyHtml
        )
        every { contentRepository.getTopicTitle("128998") } returns "Apixaban Clinical Overview"

        // Request multiple high-yield sections in a single round-trip call
        val resultJson = databaseTools.getTopicSectionsText("128998", listOf("H1", "H2"))
        val responseObj = json.parseToJsonElement(resultJson).jsonObject

        assertEquals("128998", responseObj["topicId"]?.jsonPrimitive?.content)
        assertEquals("Apixaban Clinical Overview", responseObj["topicTitle"]?.jsonPrimitive?.content)

        val sectionTitlesObj = responseObj["sectionTitles"]?.jsonObject
        assertNotNull(sectionTitlesObj)
        assertEquals("Summary & Recommendations", sectionTitlesObj?.get("H1")?.jsonPrimitive?.content)
        assertEquals("Dosing & Administration", sectionTitlesObj?.get("H2")?.jsonPrimitive?.content)

        val markdown = responseObj["markdown"]?.jsonPrimitive?.content ?: ""
        assertTrue("Markdown must contain Section H1", markdown.contains("=== Section: H1 ==="))
        assertTrue("Markdown must contain Section H2", markdown.contains("=== Section: H2 ==="))
        assertTrue("Markdown must contain Section H1 body", markdown.contains("Apixaban is recommended"))
        assertTrue("Markdown must contain Section H2 body", markdown.contains("standard dose is 5 mg"))
        assertFalse("Markdown should not contain unrequested Section H3", markdown.contains("=== Section: H3 ==="))
    }

    @Test
    fun testSmartSectionResolutionMatchesCaseInsensitiveAndTitles() {
        val outlineHtml = """
            <a href="appAction({&quot;section&quot;:&quot;H100&quot;})"><span class="legacyTopicViewHyphen">- </span>Dosing & Administration</a>
            <a href="appAction({&quot;section&quot;:&quot;H200&quot;})">Summary and Recommendations</a>
        """.trimIndent()

        val bodyHtml = """
            <h2 id="H100">Dosing & Administration</h2>
            <p>5 mg BID</p>
            <h2 id="H200">Summary and Recommendations</h2>
            <p>Grade 1A evidence</p>
        """.trimIndent()

        every { contentRepository.getTopicContent("500") } returns ContentRepository.TopicContent(
            outlineHtml = outlineHtml,
            bodyHtml = bodyHtml
        )
        every { contentRepository.getTopicTitle("500") } returns "Sample Topic"

        // Request with case-insensitive ID "h100" and title "Summary and Recommendations"
        val resultJson = databaseTools.getTopicSectionsText("500", listOf("h100", "Summary and Recommendations"))
        val responseObj = json.parseToJsonElement(resultJson).jsonObject

        val sectionTitlesObj = responseObj["sectionTitles"]?.jsonObject
        assertNotNull(sectionTitlesObj)
        assertTrue(sectionTitlesObj?.containsKey("H100") == true)
        assertTrue(sectionTitlesObj?.containsKey("H200") == true)

        val markdown = responseObj["markdown"]?.jsonPrimitive?.content ?: ""
        assertTrue(markdown.contains("=== Section: H100 ==="))
        assertTrue(markdown.contains("=== Section: H200 ==="))
    }

    @Test
    fun testInvalidSectionsReportedInStructuredEnvelope() {
        val outlineHtml = """
            <a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Section 1</a>
        """.trimIndent()
        every { contentRepository.getTopicContent("100") } returns ContentRepository.TopicContent(
            outlineHtml = outlineHtml,
            bodyHtml = "<h2 id='H1'>Sec 1</h2><p>Content</p>"
        )
        every { contentRepository.getTopicTitle("100") } returns "Topic 100"

        // Request with non-existent section IDs
        val resultJson = databaseTools.getTopicSectionsText("100", listOf("INVALID_SEC_1", "INVALID_SEC_2"))
        val responseObj = json.parseToJsonElement(resultJson).jsonObject

        assertEquals("", responseObj["markdown"]?.jsonPrimitive?.content)
        val invalidArr = responseObj["invalidSections"]?.jsonArray
        assertNotNull(invalidArr)
        assertEquals(2, invalidArr?.size)
        assertEquals("INVALID_SEC_1", invalidArr?.get(0)?.jsonPrimitive?.content)
    }

    @Test
    fun testCalculatorTopicReturnsCalcTypeAndFullSection() {
        // Calculator topics have blank outlineHtml
        val calcBodyHtml = """
            <div id="calculator">
                <h1>CHA2DS2-VASc Score</h1>
                <p>Calculates stroke risk for patients with atrial fibrillation.</p>
            </div>
        """.trimIndent()

        every { contentRepository.getTopicContent("148929") } returns ContentRepository.TopicContent(
            outlineHtml = "",
            bodyHtml = calcBodyHtml
        )
        every { contentRepository.getTopicTitle("148929") } returns "CHA2DS2-VASc Score"

        // 1. Outline returns topicType = calc and FULL section
        val outlineJson = databaseTools.getTopicOutline("148929")
        val outlineObj = json.parseToJsonElement(outlineJson).jsonObject

        assertEquals("calc", outlineObj["topicType"]?.jsonPrimitive?.content)
        val sections = outlineObj["sections"]?.jsonArray
        assertEquals(1, sections?.size)
        assertEquals("FULL", sections?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.content)

        // 2. getTopicSectionsText("FULL") returns full calculator markdown
        val sectionsJson = databaseTools.getTopicSectionsText("148929", listOf("FULL"))
        val sectionsObj = json.parseToJsonElement(sectionsJson).jsonObject

        val markdown = sectionsObj["markdown"]?.jsonPrimitive?.content ?: ""
        assertTrue(markdown.contains("=== Calculator: CHA2DS2-VASc Score ==="))
        assertTrue(markdown.contains("Calculates stroke risk"))
    }

    @Test
    fun testNonExistentTopicReturnsStructuredErrorEnvelope() {
        every { contentRepository.getTopicContent("999999") } returns null

        val outlineResult = databaseTools.getTopicOutline("999999")
        val outlineObj = json.parseToJsonElement(outlineResult).jsonObject
        assertTrue(outlineObj.containsKey("error"))
        assertTrue(outlineObj["error"]?.jsonPrimitive?.content?.contains("Topic not found") == true)

        val sectionsResult = databaseTools.getTopicSectionsText("999999", listOf("H1"))
        val sectionsObj = json.parseToJsonElement(sectionsResult).jsonObject
        assertTrue(sectionsObj.containsKey("error"))
        assertTrue(sectionsObj["error"]?.jsonPrimitive?.content?.contains("Topic not found") == true)
    }

    @Test
    fun testGraphicTableContentRetrievalAndNonTableRejection() {
        val tableHtml = """
            <table>
                <tr><th>Drug</th><th>Dose</th></tr>
                <tr><td>Apixaban</td><td>5 mg BID</td></tr>
            </table>
        """.trimIndent()

        // 1. Table graphic returns Markdown table with headers and separator
        every { assetRepository.getGraphic("123") } returns GraphicData(
            id = "123",
            title = "Anticoagulant Dosing Table",
            type = "table",
            subtype = "graphic_table",
            imageHtml = tableHtml
        )
        val tableMd = databaseTools.getGraphicContent("Graphic-123")
        assertTrue(tableMd.contains("### Graphic Table: Anticoagulant Dosing Table"))
        assertTrue(tableMd.contains("| Drug | Dose |"))
        assertTrue(tableMd.contains("| --- | --- |"))
        assertTrue(tableMd.contains("| Apixaban | 5 mg BID |"))

        // 2. Non-table graphic (e.g. figure) returns descriptive error envelope
        every { assetRepository.getGraphic("fig_456") } returns GraphicData(
            id = "fig_456",
            title = "Management Algorithm",
            type = "figure",
            subtype = "graphic_figure",
            imageHtml = "<img src='alg.png'/>"
        )
        val figureResult = databaseTools.getGraphicContent("fig_456")
        val figureObj = json.parseToJsonElement(figureResult).jsonObject
        assertTrue(figureObj.containsKey("error"))
        assertTrue(figureObj["error"]?.jsonPrimitive?.content?.contains("Only table content is retrievable") == true)
    }

    @Test
    fun testGranularToolListPreservesAllSixTools() {
        val toolList = databaseTools.asToolList()
        assertEquals(7, toolList.size)

        val toolNames = toolList.map { it.name }.toSet()
        assertTrue(toolNames.contains("getTopicOverview"))
        assertTrue(toolNames.contains("searchTopics"))
        assertTrue(toolNames.contains("getTopicOutline"))
        assertTrue(toolNames.contains("searchTopicSections"))
        assertTrue(toolNames.contains("getRelatedTopics"))
        assertTrue(toolNames.contains("getTopicSectionsText"))
        assertTrue(toolNames.contains("getGraphicContent"))
    }
}
