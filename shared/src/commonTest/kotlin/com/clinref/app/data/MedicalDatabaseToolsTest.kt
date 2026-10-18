package com.clinref.app.data

import com.clinref.app.domain.GraphicData
import com.clinref.app.domain.SearchResult
import com.clinref.app.repository.AssetRepository
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.SearchRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import com.clinref.app.data.tools.GetGraphicContentTool
import com.clinref.app.data.tools.GetRelatedTopicsTool
import com.clinref.app.data.tools.GetTopicOverviewTool
import com.clinref.app.data.tools.GetTopicSectionsTextTool
import com.clinref.app.data.tools.SearchTopicsTool
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

class MedicalDatabaseToolsTest {

    private lateinit var searchRepository: SearchRepository
    private lateinit var contentRepository: ContentRepository
    private lateinit var assetRepository: AssetRepository
    private lateinit var tools: MedicalDatabaseTools
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        searchRepository = mockk(relaxed = true)
        contentRepository = mockk(relaxed = true)
        assetRepository = mockk(relaxed = true)

        tools = MedicalDatabaseTools(
            searchRepository = searchRepository,
            contentRepository = contentRepository,
            assetRepository = assetRepository
        )
    }

    @Test
    fun `searchTopics empty query returns empty results`() {
        val resultJson = tools.searchTopics("")
        val obj = json.parseToJsonElement(resultJson).jsonObject
        assertTrue(obj["results"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `searchTopics returns pure candidate topics without embedded outlines`() {
        every { searchRepository.searchTopics("aspirin") } returns listOf(
            SearchResult.Topic("Aspirin Overview", "123")
        )
        every { searchRepository.getSuggestions("aspirin") } returns listOf("aspirin dose")

        val resultJson = tools.searchTopics("aspirin")
        val obj = json.parseToJsonElement(resultJson).jsonObject
        val results = obj["results"]!!.jsonArray
        assertEquals(1, results.size)

        val topMatch = results[0].jsonObject
        assertEquals("123", topMatch["id"]!!.jsonPrimitive.content)
        assertEquals("Aspirin Overview", topMatch["title"]!!.jsonPrimitive.content)
        assertFalse(topMatch.containsKey("outline"))
    }

    @Test
    fun `searchTopics auto-retries when first query has no results`() {
        every { searchRepository.searchTopics("asprn") } returns emptyList()
        every { searchRepository.getSuggestions("asprn") } returns listOf("aspirin", "ibuprofen")
        every { searchRepository.searchTopics("aspirin") } returns listOf(
            SearchResult.Topic("Aspirin", "123")
        )

        val resultJson = tools.searchTopics("asprn")
        val obj = json.parseToJsonElement(resultJson).jsonObject
        assertEquals("aspirin", obj["query"]!!.jsonPrimitive.content)
        assertTrue(obj["message"]!!.jsonPrimitive.content.contains("Auto-refined"))
        assertEquals(1, obj["results"]!!.jsonArray.size)
    }

    @Test
    fun `getTopicOverview parses outline html into sections and table graphics and sets topicType article`() {
        val outlineHtml = """
            <a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Overview</a>
            <a href="appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;graphic&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;111&quot;,&quot;type&quot;:&quot;graphic&quot;,&quot;subtype&quot;:&quot;graphic_table&quot;}]})"><span class="legacyTopicViewHyphen">- </span>Table A</a>
            <a href="appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;graphic&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;222&quot;,&quot;type&quot;:&quot;graphic&quot;,&quot;subtype&quot;:&quot;graphic_figure&quot;}]})">Figure B</a>
        """.trimIndent()

        every { contentRepository.getTopicContent("100") } returns ContentRepository.TopicContent(outlineHtml = outlineHtml, bodyHtml = "")
        every { contentRepository.getTopicTitle("100") } returns "Topic 100"

        val outlineJson = tools.getTopicOverview("100")
        val obj = json.parseToJsonElement(outlineJson).jsonObject

        assertEquals("Topic 100", obj["title"]!!.jsonPrimitive.content)
        assertEquals("article", obj["topicType"]!!.jsonPrimitive.content)
        assertEquals(1, obj["sections"]!!.jsonArray.size)
        // Only graphic_table is included (111), graphic_figure (222) is filtered out
        assertEquals(1, obj["graphics"]!!.jsonArray.size)
        assertEquals("111", obj["graphics"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        // Legacy hyphen span stripped from title
        assertEquals("Table A", obj["graphics"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `getTopicOverview handles calculator topic with empty outlineHtml`() {
        every { contentRepository.getTopicContent("148929") } returns ContentRepository.TopicContent(outlineHtml = "", bodyHtml = "<div>Calculator Input</div>")
        every { contentRepository.getTopicTitle("148929") } returns "AHA PREVENT Calculator"

        val outlineJson = tools.getTopicOverview("148929")
        val obj = json.parseToJsonElement(outlineJson).jsonObject

        assertEquals("calc", obj["topicType"]!!.jsonPrimitive.content)
        val sections = obj["sections"]!!.jsonArray
        assertEquals(1, sections.size)
        assertEquals("FULL", sections[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `getTopicSectionsText renders full bodyHtml for calculator topics or FULL section`() {
        every { contentRepository.getTopicContent("148929") } returns ContentRepository.TopicContent(outlineHtml = "", bodyHtml = "<p>Age input and risk options</p>")
        every { contentRepository.getTopicTitle("148929") } returns "AHA PREVENT Calculator"

        val resJson = tools.getTopicSectionsText("148929", listOf("FULL"))
        val obj = json.parseToJsonElement(resJson).jsonObject
        assertEquals("148929", obj["topicId"]!!.jsonPrimitive.content)
        val md = obj["markdown"]!!.jsonPrimitive.content

        assertTrue(md.contains("=== Calculator: AHA PREVENT Calculator ==="))
        assertTrue(md.contains("Age input and risk options"))
    }

    @Test
    fun `getTopicSectionsText renders markdown and converts appAction links`() {
        val outlineHtml = """<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Overview</a>"""
        val bodyHtml = """
            <div id="H1">
                <p>See <a href="appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;topic&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;999&quot;}]})">related topic</a> for details[1, 2].</p>
            </div>
        """.trimIndent()

        every { contentRepository.getTopicContent("100") } returns ContentRepository.TopicContent(outlineHtml = outlineHtml, bodyHtml = bodyHtml)
        every { contentRepository.getTopicTitle("100") } returns "Topic 100"

        val resJson = tools.getTopicSectionsText("100", listOf("H1"))
        val obj = json.parseToJsonElement(resJson).jsonObject
        assertEquals("100", obj["topicId"]!!.jsonPrimitive.content)
        val md = obj["markdown"]!!.jsonPrimitive.content

        assertTrue(md.contains("[related topic](Topic-999)"))
        // Footnote bracket [1, 2] should be stripped
        assertFalse(md.contains("[1, 2]"))
    }

    @Test
    fun `getGraphicContent renders markdown tables for graphic_table type`() {
        val graphicData = GraphicData(
            id = "116392",
            title = "Dosing Table",
            type = "graphic",
            subtype = "graphic_table",
            imageHtml = "<table><tr><th>Drug</th><th>Dose</th></tr><tr><td>Aspirin</td><td>100 mg</td></tr></table>"
        )
        every { assetRepository.getGraphic("116392") } returns graphicData

        val mdResult = tools.getGraphicContent("116392")
        assertTrue(mdResult.contains("### Graphic Table: Dosing Table"))
        assertTrue(mdResult.contains("| Drug | Dose |"))
        assertTrue(mdResult.contains("| Aspirin | 100 mg |"))
    }

    @Test
    fun `getGraphicContent blocks non-table graphic types`() {
        val graphicData = GraphicData(
            id = "999",
            title = "Chest X-Ray",
            type = "graphic",
            subtype = "graphic_figure",
            imageHtml = ""
        )
        every { assetRepository.getGraphic("999") } returns graphicData

        val resJson = tools.getGraphicContent("999")
        assertTrue(resJson.contains("not a table"))
    }

    @Test
    fun `asToolList returns 6 class-based tools with expected names`() {
        val toolList = tools.asToolList()
        assertEquals(6, toolList.size)
        val names = toolList.map { it.name }.toSet()
        assertTrue(names.contains("getTopicOverview"))
        assertTrue(names.contains("searchTopics"))
        assertTrue(names.contains("searchTopicSections"))
        assertTrue(names.contains("getRelatedTopics"))
        assertTrue(names.contains("getTopicSectionsText"))
        assertTrue(names.contains("getGraphicContent"))
        assertFalse(names.contains("getTopicOutline"))
        assertFalse(names.contains("submitClinicalAnswer"))
    }

    @Test
    fun `class-based tools execute properly and return structured content`() = runBlocking {
        every { searchRepository.searchTopics("metformin") } returns listOf(
            SearchResult.Topic("Metformin Overview", "456")
        )
        val searchToolResult = tools.searchTopicsTool.execute(SearchTopicsTool.Args("metformin"))
        val searchObj = json.parseToJsonElement(searchToolResult).jsonObject
        assertEquals(1, searchObj["results"]!!.jsonArray.size)

        every { assetRepository.getGraphic("999") } returns GraphicData(
            id = "999",
            title = "Sample Image",
            imageHtml = "<div>img</div>",
            subtype = "graphic_image"
        )
        val graphicResult = tools.getGraphicContentTool.execute(GetGraphicContentTool.Args("999"))
        assertTrue(graphicResult.contains("not a table"))
    }

    @Test
    fun `getTopicOverview extracts section levels and parentIds properly`() {
        val outlineHtml = """
            <ul id="innerOutline">
                <li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Hematoma</a>
                    <ul>
                        <li><a href="appAction({&quot;section&quot;:&quot;H2&quot;})">Clinical Manifestations</a></li>
                        <li><a href="appAction({&quot;section&quot;:&quot;H3&quot;})">Treatment</a>
                            <ul>
                                <li><a href="appAction({&quot;section&quot;:&quot;H4&quot;})"><span class="legacyTopicViewHyphen">- </span>Drainage</a></li>
                            </ul>
                        </li>
                    </ul>
                </li>
            </ul>
        """.trimIndent()

        every { contentRepository.getTopicContent("200") } returns ContentRepository.TopicContent(outlineHtml = outlineHtml, bodyHtml = "")
        every { contentRepository.getTopicTitle("200") } returns "Topic 200"

        val outlineJson = tools.getTopicOverview("200")
        val obj = json.parseToJsonElement(outlineJson).jsonObject
        val sections = obj["sections"]!!.jsonArray

        assertEquals(4, sections.size)
        // H1 is level 1, parent null
        assertEquals("H1", sections[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(1, sections[0].jsonObject["level"]!!.jsonPrimitive.content.toInt())
        assertTrue(sections[0].jsonObject["parentId"] == null || sections[0].jsonObject["parentId"] is kotlinx.serialization.json.JsonNull)

        // H2 is level 2, parent H1
        assertEquals("H2", sections[1].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(2, sections[1].jsonObject["level"]!!.jsonPrimitive.content.toInt())
        assertEquals("H1", sections[1].jsonObject["parentId"]!!.jsonPrimitive.content)

        // H3 is level 2, parent H1
        assertEquals("H3", sections[2].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(2, sections[2].jsonObject["level"]!!.jsonPrimitive.content.toInt())
        assertEquals("H1", sections[2].jsonObject["parentId"]!!.jsonPrimitive.content)

        // H4 is level 3, parent H3, legacy hyphen stripped
        assertEquals("H4", sections[3].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("Drainage", sections[3].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(3, sections[3].jsonObject["level"]!!.jsonPrimitive.content.toInt())
        assertEquals("H3", sections[3].jsonObject["parentId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `getTopicOverview deduplicates repeated sections such as summary and recommendations`() {
        val outlineHtml = """
            <li class="sr-button"><a href="appAction({&quot;section&quot;:&quot;H_SR&quot;})">Summary &amp; Recommendations</a></li>
            <ul id="innerOutline">
                <li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Intro</a></li>
                <li><a href="appAction({&quot;section&quot;:&quot;H_SR&quot;})">Summary &amp; Recommendations</a></li>
            </ul>
        """.trimIndent()

        every { contentRepository.getTopicContent("300") } returns ContentRepository.TopicContent(outlineHtml = outlineHtml, bodyHtml = "")
        every { contentRepository.getTopicTitle("300") } returns "Topic 300"

        val outlineJson = tools.getTopicOverview("300")
        val obj = json.parseToJsonElement(outlineJson).jsonObject
        val sections = obj["sections"]!!.jsonArray

        assertEquals(2, sections.size)
        val ids = sections.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("H1", "H_SR"), ids)
    }

    @Test
    fun `getTopicOverview returns error response for nonexistent topic`() {
        every { contentRepository.getTopicContent("nonexistent") } returns null

        val resultJson = tools.getTopicOverview("nonexistent")
        val obj = json.parseToJsonElement(resultJson).jsonObject
        assertTrue(obj.containsKey("error"))
        assertTrue(obj["error"]!!.jsonPrimitive.content.contains("Topic not found"))
    }

    @Test
    fun `getTopicSectionsText includes subsections when parent section is requested`() {
        val outlineHtml = """
            <ul id="innerOutline">
                <li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Overview</a></li>
                <li><a href="appAction({&quot;section&quot;:&quot;H2&quot;})">Treatment</a>
                    <ul>
                        <li><a href="appAction({&quot;section&quot;:&quot;H3&quot;})">Antibiotics</a></li>
                    </ul>
                </li>
                <li><a href="appAction({&quot;section&quot;:&quot;H4&quot;})">Prevention</a></li>
            </ul>
        """.trimIndent()

        val bodyHtml = """
            <p class="headingAnchor" id="H1"><span class="h1">Overview</span><span class="headingEndMark">—</span>General overview text.</p>
            <p class="headingAnchor" id="H2"><span class="h2">Treatment</span><span class="headingEndMark">—</span>Infected wounds must be drained.</p>
            <p class="headingAnchor" id="H3"><span class="h3">Antibiotics</span><span class="headingEndMark">—</span>Give Vancomycin 15 to 20 mg/kg IV.</p>
            <p class="headingAnchor" id="H4"><span class="h2">Prevention</span><span class="headingEndMark">—</span>Use sterile technique.</p>
        """.trimIndent()

        every { contentRepository.getTopicContent("400") } returns ContentRepository.TopicContent(outlineHtml = outlineHtml, bodyHtml = bodyHtml)
        every { contentRepository.getTopicTitle("400") } returns "Topic 400"

        val resJson = tools.getTopicSectionsText("400", listOf("H2"))
        val obj = json.parseToJsonElement(resJson).jsonObject
        val md = obj["markdown"]!!.jsonPrimitive.content

        // Treatment (H2) must include child H3 (Antibiotics with Vancomycin 15 to 20 mg/kg IV)
        assertTrue(md.contains("Infected wounds must be drained."))
        assertTrue(md.contains("Vancomycin 15 to 20 mg/kg IV"))
        // But must NOT bleed into sibling H4 (Prevention)
        assertFalse(md.contains("Use sterile technique."))
    }

    @Test
    fun `getTopicSectionsText resolves section by title if title is passed instead of id`() {
        val outlineHtml = """
            <ul id="innerOutline">
                <li><a href="appAction({&quot;section&quot;:&quot;H_TREAT&quot;})">Treatment and Management</a></li>
            </ul>
        """.trimIndent()
        val bodyHtml = """<p class="headingAnchor" id="H_TREAT"><span class="h1">Treatment and Management</span><span class="headingEndMark">—</span>Clinical management details.</p>"""

        every { contentRepository.getTopicContent("500") } returns ContentRepository.TopicContent(outlineHtml = outlineHtml, bodyHtml = bodyHtml)
        every { contentRepository.getTopicTitle("500") } returns "Topic 500"

        val resJson = tools.getTopicSectionsText("500", listOf("Treatment and Management"))
        val obj = json.parseToJsonElement(resJson).jsonObject
        val sectionTitles = obj["sectionTitles"]!!.jsonObject

        assertTrue(sectionTitles.containsKey("H_TREAT"))
        val md = obj["markdown"]!!.jsonPrimitive.content
        assertTrue(md.contains("=== Section: H_TREAT ==="))
        assertTrue(md.contains("Clinical management details."))
    }

    @Test
    fun `getTopicSectionsText strips calculator SDI lookup tables to prevent context blowup`() {
        val bodyHtml = """
            <div id="topicContentCalculator">
                <h1>Creatinine Clearance Calculator</h1>
                <p>Enter patient age and serum creatinine.</p>
                <td id="sdi1">00501 00544 01005 01007 01008 90210 99950</td>
                <p>Cockcroft-Gault formula calculates estimated CrCl.</p>
            </div>
        """.trimIndent()

        every { contentRepository.getTopicContent("600") } returns ContentRepository.TopicContent(outlineHtml = "", bodyHtml = bodyHtml)
        every { contentRepository.getTopicTitle("600") } returns "CrCl Calculator"

        val resJson = tools.getTopicSectionsText("600", listOf("FULL"))
        val obj = json.parseToJsonElement(resJson).jsonObject
        val md = obj["markdown"]!!.jsonPrimitive.content

        assertTrue(md.contains("Cockcroft-Gault formula"))
        assertFalse(md.contains("90210"))
    }

    @Test
    fun `searchTopicSections finds matching sections and snippets`() {
        val outlineHtml = """
            <ul id="innerOutline">
                <li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Overview</a></li>
                <li><a href="appAction({&quot;section&quot;:&quot;H2&quot;})">Renal Impairment</a></li>
            </ul>
        """.trimIndent()
        val bodyHtml = """
            <p class="headingAnchor" id="H1"><span class="h1">Overview</span><span class="headingEndMark">—</span>General disease overview.</p>
            <p class="headingAnchor" id="H2"><span class="h1">Renal Impairment</span><span class="headingEndMark">—</span>Dose adjustment: For eGFR &lt; 30 mL/min, reduce dose by 50%.</p>
        """.trimIndent()

        every { contentRepository.getTopicContent("700") } returns ContentRepository.TopicContent(outlineHtml = outlineHtml, bodyHtml = bodyHtml)
        every { contentRepository.getTopicTitle("700") } returns "Drug Overview"

        val searchJson = tools.searchTopicSections("700", "eGFR")
        val obj = json.parseToJsonElement(searchJson).jsonObject
        val matches = obj["matches"]!!.jsonArray

        assertEquals(1, matches.size)
        assertEquals("H2", matches[0].jsonObject["sectionId"]!!.jsonPrimitive.content)
        assertEquals("Renal Impairment", matches[0].jsonObject["sectionTitle"]!!.jsonPrimitive.content)
        assertTrue(matches[0].jsonObject["snippet"]!!.jsonPrimitive.content.contains("reduce dose by 50%"))
    }

    @Test
    fun `getTopicOverview returns outline and graphics for articles`() {
        val outlineHtml = """
            <ul id="innerOutline">
                <li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Overview</a></li>
                <li><a href="appAction({&quot;section&quot;:&quot;H_SR&quot;})">Summary &amp; Recommendations</a></li>
                <li><a href="appAction({&quot;section&quot;:&quot;H_DOSE&quot;})">Dosing &amp; Administration</a></li>
                <li><a href="appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;graphic&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;111&quot;,&quot;type&quot;:&quot;graphic&quot;,&quot;subtype&quot;:&quot;graphic_table&quot;}]})">Dosing Table</a></li>
            </ul>
        """.trimIndent()
        val bodyHtml = """
            <p class="headingAnchor" id="H1"><span class="h1">Overview</span><span class="headingEndMark">—</span>Disease description.</p>
            <p class="headingAnchor" id="H_SR"><span class="h1">Summary &amp; Recommendations</span><span class="headingEndMark">—</span>Aspirin 81 mg daily is recommended.</p>
            <p class="headingAnchor" id="H_DOSE"><span class="h1">Dosing &amp; Administration</span><span class="headingEndMark">—</span>Oral route with water.</p>
        """.trimIndent()

        every { contentRepository.getTopicContent("100") } returns ContentRepository.TopicContent(outlineHtml = outlineHtml, bodyHtml = bodyHtml)
        every { contentRepository.getTopicTitle("100") } returns "Aspirin Therapy"

        val resultJson = tools.getTopicOverview("100")
        val obj = json.parseToJsonElement(resultJson).jsonObject

        // Invariants
        assertEquals("100", obj["topicId"]!!.jsonPrimitive.content)
        assertEquals("Aspirin Therapy", obj["title"]!!.jsonPrimitive.content)
        assertEquals("article", obj["topicType"]!!.jsonPrimitive.content)
        assertEquals(3, obj["sections"]!!.jsonArray.size)
        assertEquals(1, obj["graphics"]!!.jsonArray.size)

        val md = obj["markdown"]!!.jsonPrimitive.content
        assertEquals("", md)
    }

    @Test
    fun `getTopicOverview returns structured error for nonexistent topic`() {
        every { contentRepository.getTopicContent("nonexistent_999") } returns null

        val resultJson = tools.getTopicOverview("nonexistent_999")
        val obj = json.parseToJsonElement(resultJson).jsonObject

        assertTrue(obj.containsKey("error"))
        assertEquals("Topic not found: nonexistent_999", obj["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `getTopicSectionsText returns full sections without token cap`() {
        val outlineHtml = """
            <ul id="innerOutline">
                <li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Overview</a></li>
                <li><a href="appAction({&quot;section&quot;:&quot;H_RENAL&quot;})">Renal Impairment</a></li>
                <li><a href="appAction({&quot;section&quot;:&quot;H_SR&quot;})">Summary &amp; Recommendations</a></li>
            </ul>
        """.trimIndent()
        val longText = "Guideline statement. ".repeat(60)
        val bodyHtml = """
            <p class="headingAnchor" id="H1"><span class="h1">Overview</span><span class="headingEndMark">—</span>$longText</p>
            <p class="headingAnchor" id="H_RENAL"><span class="h1">Renal Impairment</span><span class="headingEndMark">—</span>$longText</p>
            <p class="headingAnchor" id="H_SR"><span class="h1">Summary &amp; Recommendations</span><span class="headingEndMark">—</span>$longText</p>
        """.trimIndent()

        every { contentRepository.getTopicContent("200") } returns ContentRepository.TopicContent(outlineHtml = outlineHtml, bodyHtml = bodyHtml)
        every { contentRepository.getTopicTitle("200") } returns "Drug A"

        // No token cap: sections come back whole.
        val resultJson = tools.getTopicSectionsText("200", listOf("H1", "H_RENAL"))
        val obj = json.parseToJsonElement(resultJson).jsonObject

        assertTrue(!obj.containsKey("truncated"))
        assertTrue(!obj.containsKey("omittedSections"))
        val titles = obj["sectionTitles"]!!.jsonObject
        assertTrue(titles.containsKey("H1"))
        assertTrue(titles.containsKey("H_RENAL"))

        val md = obj["markdown"]!!.jsonPrimitive.content
        // converter trims trailing whitespace, so match the body run without the final space
        assertTrue(md.contains(longText.trimEnd()))
        assertTrue(!md.contains("[Section truncated due to token limit]"))
    }

    @Test
    fun `getTopicSectionsText returns at most ten sections per call`() {
        val outlineHtml = (1..11).joinToString("") {
            """<li><a href="appAction({&quot;section&quot;:&quot;H$it&quot;})">Sec $it</a></li>"""
        }
        val bodyHtml = (1..11).joinToString("") {
            """<p class="headingAnchor" id="H$it"><span class="h1">Sec $it</span><span class="headingEndMark">—</span>Short text $it.</p>"""
        }
        every { contentRepository.getTopicContent("300") } returns ContentRepository.TopicContent(
            outlineHtml = """<ul id="innerOutline">$outlineHtml</ul>""",
            bodyHtml = bodyHtml
        )
        every { contentRepository.getTopicTitle("300") } returns "Topic 300"

        val resultJson = tools.getTopicSectionsText("300", (1..11).map { "H$it" })
        val obj = json.parseToJsonElement(resultJson).jsonObject

        // Count guard: at most 10 sections per call; the 11th is reported, never silently dropped
        val titles = obj["sectionTitles"]!!.jsonObject
        (1..10).forEach { assertTrue(titles.containsKey("H$it")) }
        assertTrue(!titles.containsKey("H11"))
        assertTrue(!obj.containsKey("truncated"))
        val omitted = obj["omittedSections"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("H11"), omitted)
        val md = obj["markdown"]!!.jsonPrimitive.content
        assertTrue(md.contains("=== Section: H10 ==="))
        assertTrue(!md.contains("=== Section: H11 ==="))
    }

    @Test
    fun `getTopicOverview correctly handles calculator topics with synthetic FULL section`() {
        every { contentRepository.getTopicContent("148929") } returns ContentRepository.TopicContent(
            outlineHtml = "",
            bodyHtml = "<div>PREVENT Risk Calculator Inputs</div>"
        )
        every { contentRepository.getTopicTitle("148929") } returns "AHA PREVENT Calculator"

        val resultJson = tools.getTopicOverview("148929")
        val obj = json.parseToJsonElement(resultJson).jsonObject

        assertEquals("calc", obj["topicType"]!!.jsonPrimitive.content)
        val sections = obj["sections"]!!.jsonArray
        assertEquals(1, sections.size)
        assertEquals("FULL", sections[0].jsonObject["id"]!!.jsonPrimitive.content)

        val md = obj["markdown"]!!.jsonPrimitive.content
        assertTrue(md.contains("=== Calculator: AHA PREVENT Calculator ==="))
        assertTrue(md.contains("PREVENT Risk Calculator Inputs"))
    }

    @Test
    fun `getTopicOverviewTool executes properly with snake_case and camelCase args`() = runBlocking {
        every { contentRepository.getTopicContent("100") } returns ContentRepository.TopicContent(
            outlineHtml = """<a href="appAction({&quot;section&quot;:&quot;H_SR&quot;})">Summary &amp; Recommendations</a>""",
            bodyHtml = """<p id="H_SR">Guidance 10 mg.</p>"""
        )
        every { contentRepository.getTopicTitle("100") } returns "Topic 100"

        // Execute via class-based Koog tool instance
        val res1 = tools.getTopicOverviewTool.execute(
            GetTopicOverviewTool.Args(topicId = "100")
        )
        val obj1 = json.parseToJsonElement(res1).jsonObject
        assertEquals("100", obj1["topicId"]!!.jsonPrimitive.content)

        // Test JSON argument deserialization parity
        val jsonArgs = """{"topic_id": "100"}"""
        val decodedArgs = json.decodeFromString<GetTopicOverviewTool.Args>(jsonArgs)
        assertEquals("100", decodedArgs.topicId)
    }
}

