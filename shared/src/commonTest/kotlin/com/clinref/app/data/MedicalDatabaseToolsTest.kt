package com.clinref.app.data

import com.clinref.app.domain.GraphicData
import com.clinref.app.domain.SearchResult
import com.clinref.app.repository.AssetRepository
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.SearchRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import com.clinref.app.data.tools.GetGraphicContentTool
import com.clinref.app.data.tools.GetRelatedTopicsTool
import com.clinref.app.data.tools.GetTopicOverviewTool
import com.clinref.app.data.tools.GetTopicSectionsTextTool
import com.clinref.app.data.tools.SearchTopicsTool
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val res = tools.searchTopics("")
        assertTrue(res.results.isEmpty())
    }

    @Test
    fun `searchTopics returns pure candidate topics without embedded outlines`() {
        every { searchRepository.searchTopics("aspirin") } returns listOf(
            SearchResult.Topic("Aspirin Overview", "123")
        )
        every { searchRepository.getSuggestions("aspirin") } returns listOf("aspirin dose")

        val res = tools.searchTopics("aspirin")
        val results = res.results
        assertEquals(1, results.size)

        val topMatch = results[0]
        assertEquals("123", topMatch.id)
        assertEquals("Aspirin Overview", topMatch.title)
    }

    @Test
    fun `searchTopics auto-retries when first query has no results`() {
        every { searchRepository.searchTopics("asprn") } returns emptyList()
        every { searchRepository.getSuggestions("asprn") } returns listOf("aspirin", "ibuprofen")
        every { searchRepository.searchTopics("aspirin") } returns listOf(
            SearchResult.Topic("Aspirin", "123")
        )

        val res = tools.searchTopics("asprn")
        assertEquals("aspirin", res.query)
        assertTrue(res.message.contains("Auto-refined"))
        assertEquals(1, res.results.size)
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

        val res = tools.getTopicOverview("100")

        assertEquals("Topic 100", res.title)
        assertEquals("article", res.topicType)
        assertEquals(1, res.sections.size)
        // Only graphic_table is included (111), graphic_figure (222) is filtered out
        assertEquals(1, res.graphics.size)
        assertEquals("111", res.graphics[0].id)
        // Legacy hyphen span stripped from title
        assertEquals("Table A", res.graphics[0].title)
    }

    @Test
    fun `getTopicOverview handles calculator topic with empty outlineHtml`() {
        every { contentRepository.getTopicContent("148929") } returns ContentRepository.TopicContent(outlineHtml = "", bodyHtml = "<div>Calculator Input</div>")
        every { contentRepository.getTopicTitle("148929") } returns "AHA PREVENT Calculator"

        val res = tools.getTopicOverview("148929")

        assertEquals("calc", res.topicType)
        val sections = res.sections
        assertEquals(1, sections.size)
        assertEquals("FULL", sections[0].id)
    }

    @Test
    fun `getTopicSectionsText renders full bodyHtml for calculator topics or FULL section`() {
        every { contentRepository.getTopicContent("148929") } returns ContentRepository.TopicContent(outlineHtml = "", bodyHtml = "<p>Age input and risk options</p>")
        every { contentRepository.getTopicTitle("148929") } returns "AHA PREVENT Calculator"

        val res = tools.getTopicSectionsText("148929", listOf("FULL"))
        assertEquals("148929", res.topicId)
        val md = res.markdown

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

        val res = tools.getTopicSectionsText("100", listOf("H1"))
        assertEquals("100", res.topicId)
        val md = res.markdown

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

        val res = tools.getGraphicContent("116392")
        val mdResult = res.markdown
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

        val res = tools.getGraphicContent("999")
        assertNotNull(res.error)
        assertTrue(res.error!!.contains("not a table"))
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
        assertEquals(1, searchToolResult.results.size)

        every { assetRepository.getGraphic("999") } returns GraphicData(
            id = "999",
            title = "Sample Image",
            imageHtml = "<div>img</div>",
            subtype = "graphic_image"
        )
        val graphicResult = tools.getGraphicContentTool.execute(GetGraphicContentTool.Args("999"))
        assertNotNull(graphicResult.error)
        assertTrue(graphicResult.error!!.contains("not a table"))
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

        val res = tools.getTopicOverview("200")
        val sections = res.sections

        assertEquals(4, sections.size)
        // H1 is level 1, parent null
        assertEquals("H1", sections[0].id)
        assertEquals(1, sections[0].level)
        assertNull(sections[0].parentId)

        // H2 is level 2, parent H1
        assertEquals("H2", sections[1].id)
        assertEquals(2, sections[1].level)
        assertEquals("H1", sections[1].parentId)

        // H3 is level 2, parent H1
        assertEquals("H3", sections[2].id)
        assertEquals(2, sections[2].level)
        assertEquals("H1", sections[2].parentId)

        // H4 is level 3, parent H3, legacy hyphen stripped
        assertEquals("H4", sections[3].id)
        assertEquals("Drainage", sections[3].title)
        assertEquals(3, sections[3].level)
        assertEquals("H3", sections[3].parentId)
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

        val res = tools.getTopicOverview("300")
        val sections = res.sections

        assertEquals(2, sections.size)
        val ids = sections.map { it.id }
        assertEquals(listOf("H1", "H_SR"), ids)
    }

    @Test
    fun `getTopicOverview returns error response for nonexistent topic`() {
        every { contentRepository.getTopicContent("nonexistent") } returns null

        val res = tools.getTopicOverview("nonexistent")
        assertNotNull(res.error)
        assertTrue(res.error!!.contains("Topic not found"))
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

        val res = tools.getTopicSectionsText("400", listOf("H2"))
        val md = res.markdown

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

        val res = tools.getTopicSectionsText("500", listOf("Treatment and Management"))
        val sectionTitles = res.sectionTitles

        assertTrue(sectionTitles.containsKey("H_TREAT"))
        val md = res.markdown
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

        val res = tools.getTopicSectionsText("600", listOf("FULL"))
        val md = res.markdown

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

        val res = tools.searchTopicSections("700", "eGFR")
        val matches = res.matches

        assertEquals(1, matches.size)
        assertEquals("H2", matches[0].sectionId)
        assertEquals("Renal Impairment", matches[0].sectionTitle)
        assertTrue(matches[0].snippet.contains("reduce dose by 50%"))
    }

    @Test
    fun `searchTopicSections matches any keyword of multi-word query and ranks title hits first`() {
        // Parity regression vs python_prototype/tools.py search_topic_sections: the whole-phrase
        // substring match returned 0 sections on-device for "acetaminophen dosing" while python
        // returned 7 on the same databases — sections mention either word separately (e.g. an
        // "Analgesics" section whose body contains "acetaminophen" and "dosing" independently).
        val outlineHtml = """
            <ul id="innerOutline">
                <li><a href="appAction({&quot;section&quot;:&quot;H0&quot;})">Dosing and Administration</a></li>
                <li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Analgesics</a></li>
                <li><a href="appAction({&quot;section&quot;:&quot;H2&quot;})">Therapies that may be effective</a></li>
                <li><a href="appAction({&quot;section&quot;:&quot;H3&quot;})">Selected hygiene measures</a></li>
            </ul>
        """.trimIndent()
        val bodyHtml = """
            <p class="headingAnchor" id="H0">For adults and children.</p>
            <p class="headingAnchor" id="H1"><span class="h1">Analgesics</span><span class="headingEndMark">—</span>Acetaminophen provides mild relief; no dosing schedule is listed.</p>
            <p class="headingAnchor" id="H2"><span class="h1">Therapies that may be effective</span><span class="headingEndMark">—</span>Dosing of intranasal ipratropium reduces symptoms.</p>
            <p class="headingAnchor" id="H3"><span class="h1">Selected hygiene measures</span><span class="headingEndMark">—</span>Hand washing plus masks prevent transmission.</p>
        """.trimIndent()

        every { contentRepository.getTopicContent("6868") } returns ContentRepository.TopicContent(outlineHtml = outlineHtml, bodyHtml = bodyHtml)
        every { contentRepository.getTopicTitle("6868") } returns "The common cold in adults"

        val res = tools.searchTopicSections("6868", "acetaminophen dosing")
        val matches = res.matches

        assertEquals(3, matches.size)
        // Score 3x titleHit + 1x bodyHit: title-only H0 (3) ranks above two-body-hit H1 (2),
        // then one-body-hit H2 (1); the keyword-free H3 section never matches.
        assertEquals("H0", matches[0].sectionId)
        // Title-only match falls back to the head-of-body snippet (no keyword window).
        assertEquals("For adults and children.", matches[0].snippet)
        assertEquals("H1", matches[1].sectionId)
        // Snippet is cut around the earliest keyword occurrence in plain body text.
        assertTrue(matches[1].snippet.contains("Acetaminophen provides mild relief"))
        assertEquals("H2", matches[2].sectionId)
        assertEquals("Found 3 matching sections.", res.message)
    }

    @Test
    fun `searchTopicSections caps results at eight with capped count message`() {
        val outlineLi = (1..9).joinToString("") { i ->
            """<li><a href="appAction({&quot;section&quot;:&quot;H$i&quot;})">Dosing section $i</a></li>"""
        }
        val outlineHtml = "<ul id=\"innerOutline\">$outlineLi</ul>"
        val bodyParagraph = (1..9).joinToString("\n") { i ->
            """<p class="headingAnchor" id="H$i"><span class="h1">Dosing section $i</span><span class="headingEndMark">—</span>Dosing topic content $i.</p>"""
        }

        every { contentRepository.getTopicContent("700") } returns ContentRepository.TopicContent(
            outlineHtml = outlineHtml,
            bodyHtml = bodyParagraph
        )
        every { contentRepository.getTopicTitle("700") } returns "Drug Overview"

        val res = tools.searchTopicSections("700", "dosing")

        assertEquals(8, res.matches.size)
        // Python counts the capped top_matches in the message, not every match found.
        assertEquals("Found 8 matching sections.", res.message)
    }

    @Test
    fun `searchTopicSections mirrors python messages for no hits and blank input`() {
        val outlineHtml = """
            <ul id="innerOutline">
                <li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Overview</a></li>
            </ul>
        """.trimIndent()
        val bodyHtml = """
            <p class="headingAnchor" id="H1"><span class="h1">Overview</span><span class="headingEndMark">—</span>General overview text.</p>
        """.trimIndent()

        every { contentRepository.getTopicContent("700") } returns ContentRepository.TopicContent(outlineHtml = outlineHtml, bodyHtml = bodyHtml)
        every { contentRepository.getTopicTitle("700") } returns "Drug Overview"

        val noHit = tools.searchTopicSections("700", "unobtainium")
        assertEquals(0, noHit.matches.size)
        assertEquals("No matching sections found for 'unobtainium'.", noHit.message)

        // Blank query does not touch the repository (python checks emptiness first).
        val blank = tools.searchTopicSections("700", "   ")
        assertEquals("Topic ID and query must be non-empty.", blank.message)
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

        val res = tools.getTopicOverview("100")

        // Invariants
        assertEquals("100", res.topicId)
        assertEquals("Aspirin Therapy", res.title)
        assertEquals("article", res.topicType)
        assertEquals(3, res.sections.size)
        assertEquals(1, res.graphics.size)

        val md = res.markdown
        assertEquals("", md)
    }

    @Test
    fun `getTopicOverview returns structured error for nonexistent topic`() {
        every { contentRepository.getTopicContent("nonexistent_999") } returns null

        val res = tools.getTopicOverview("nonexistent_999")

        assertNotNull(res.error)
        assertEquals("Topic not found: nonexistent_999", res.error)
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
        val res = tools.getTopicSectionsText("200", listOf("H1", "H_RENAL"))

        assertNull(res.truncated)
        assertNull(res.omittedSections)
        val titles = res.sectionTitles
        assertTrue(titles.containsKey("H1"))
        assertTrue(titles.containsKey("H_RENAL"))

        val md = res.markdown
        // converter trims trailing whitespace, so match the body run without the final space
        assertTrue(md.contains(longText.trimEnd()))
        assertFalse(md.contains("[Section truncated due to token limit]"))
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

        val res = tools.getTopicSectionsText("300", (1..11).map { "H$it" })

        // Count guard: at most 10 sections per call; the 11th is reported, never silently dropped
        val titles = res.sectionTitles
        (1..10).forEach { assertTrue(titles.containsKey("H$it")) }
        assertFalse(titles.containsKey("H11"))
        assertNull(res.truncated)
        val omitted = res.omittedSections
        assertEquals(listOf("H11"), omitted)
        val md = res.markdown
        assertTrue(md.contains("=== Section: H10 ==="))
        assertFalse(md.contains("=== Section: H11 ==="))
    }

    @Test
    fun `getTopicOverview correctly handles calculator topics with synthetic FULL section`() {
        every { contentRepository.getTopicContent("148929") } returns ContentRepository.TopicContent(
            outlineHtml = "",
            bodyHtml = "<div>PREVENT Risk Calculator Inputs</div>"
        )
        every { contentRepository.getTopicTitle("148929") } returns "AHA PREVENT Calculator"

        val res = tools.getTopicOverview("148929")

        assertEquals("calc", res.topicType)
        val sections = res.sections
        assertEquals(1, sections.size)
        assertEquals("FULL", sections[0].id)

        val md = res.markdown
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
        assertEquals("100", res1.topicId)

        // Test JSON argument deserialization parity
        val jsonArgs = """{"topic_id": "100"}"""
        val decodedArgs = json.decodeFromString<GetTopicOverviewTool.Args>(jsonArgs)
        assertEquals("100", decodedArgs.topicId)
    }

    @Test
    fun `TopicOverviewResponse decodes snake_case payload from Python prototype`() {
        val pythonPayload = """
            {
              "topic_id": "8907",
              "topic_title": "Aspirin Drug Overview",
              "topic_type": "article",
              "outline": [
                {"id": "H1", "title": "Overview", "level": 1}
              ],
              "related_topics": [
                {"id": "123", "title": "Related"}
              ],
              "markdown": "=== Section: H1 ===\nAspirin is used for..."
            }
        """.trimIndent()

        val decoded = json.decodeFromString<TopicOverviewResponse>(pythonPayload)
        assertEquals("8907", decoded.topicId)
        assertEquals("Aspirin Drug Overview", decoded.title)
        assertEquals("article", decoded.topicType)
        assertEquals(1, decoded.sections.size)
        assertEquals("H1", decoded.sections[0].id)
        assertEquals(1, decoded.relatedTopics.size)
        assertEquals("123", decoded.relatedTopics[0].id)
        assertEquals("=== Section: H1 ===\nAspirin is used for...", decoded.markdown)
    }

    @Test
    fun `TopicOverviewResponse decodes camelCase payload`() {
        val camelPayload = """
            {
              "topicId": "8907",
              "title": "Aspirin Drug Overview",
              "topicType": "article",
              "sections": [
                {"id": "H1", "title": "Overview", "level": 1}
              ],
              "graphics": [
                {"id": "G1", "title": "Table 1", "isTable": true}
              ],
              "relatedTopics": [
                {"id": "123", "title": "Related"}
              ],
              "markdown": "=== Section: H1 ===\nAspirin is used for..."
            }
        """.trimIndent()

        val decoded = json.decodeFromString<TopicOverviewResponse>(camelPayload)
        assertEquals("8907", decoded.topicId)
        assertEquals("Aspirin Drug Overview", decoded.title)
        assertEquals(1, decoded.graphics.size)
        assertEquals("G1", decoded.graphics[0].id)
        assertTrue(decoded.graphics[0].isTable)
        assertEquals("=== Section: H1 ===\nAspirin is used for...", decoded.markdown)
    }

    @Test
    fun `contentDao rejects malformed topic ids without touching database`() {
        val dbManager = mockk<DatabaseManager>(relaxed = true)
        val contentDao = ContentDao(dbManager)

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
            assertNull("ContentDao must return null for malformed topic ID '$id'", contentDao.getTopicContent(id))
            assertNull("ContentDao must return null for malformed topic title '$id'", contentDao.getTopicTitle(id))
        }
        // The `^(?:topic-)?(\d+)$` regex gate must reject everything before any database access.
        verify(exactly = 0) { dbManager.getAssetsDb() }
    }
}

