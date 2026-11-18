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
    fun `getTopicOverview takes sections from the outline and graphics from the payload JSON`() {
        val outlineHtml = """
            <ul id="innerOutline">
                <li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Overview</a></li>
                <li><a href="appAction({&quot;section&quot;:&quot;H2&quot;})"><span class="legacyTopicViewHyphen">- </span>Renal Dosage Adjustment</a></li>
            </ul>
        """.trimIndent()

        every { contentRepository.getTopicContent("100") } returns ContentRepository.TopicContent(
            outlineHtml = outlineHtml,
            bodyHtml = "",
            topicType = "medical",
            graphics = listOf(
                ContentDao.GraphicMeta("111", "Table A", "graphic_table"),
                ContentDao.GraphicMeta("222", "Figure B", "graphic_figure")
            ),
            relatedTopics = listOf(ContentDao.TopicMeta("300", "Related Topic"))
        )
        every { contentRepository.getTopicTitle("100") } returns "Topic 100"

        val res = tools.getTopicOverview("100")

        assertEquals("Topic 100", res.title)
        assertEquals("medical", res.topicType)
        assertEquals(listOf("H1", "H2"), res.sections.map { it.id })
        // Legacy hyphen span stripped from the outline title
        assertEquals("Renal Dosage Adjustment", res.sections[1].title)
        // Only graphic_table survives; figures are not retrievable content
        assertEquals(listOf("111"), res.graphics.map { it.id })
        assertEquals("Table A", res.graphics[0].title)
        assertEquals(listOf("300"), res.relatedTopics.map { it.id })
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
    fun `getTopicSectionsText renders markdown and converts appAction links to plain text`() {
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

        assertTrue(md.contains("See related topic for details."))
        assertFalse(md.contains("Topic-999"))
        assertFalse(md.contains("[related topic]("))
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
    fun `asToolList returns the 4 class-based retrieval tools`() {
        val toolList = tools.asToolList()
        assertEquals(4, toolList.size)
        val names = toolList.map { it.name }.toSet()
        assertTrue(names.contains("getTopicOverview"))
        assertTrue(names.contains("searchTopics"))
        assertTrue(names.contains("getTopicSectionsText"))
        assertTrue(names.contains("getGraphicContent"))
        assertFalse(names.contains("searchTopicSections"))
        assertFalse(names.contains("getTopicBrief"))
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
    fun `getTopicSectionsText avoids duplicate text when both parent and child sections are requested`() {
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

        every { contentRepository.getTopicContent("401") } returns ContentRepository.TopicContent(outlineHtml = outlineHtml, bodyHtml = bodyHtml)
        every { contentRepository.getTopicTitle("401") } returns "Topic 401"

        // Request both H2 (parent) and H3 (child), in reverse order to verify sorting & subsumption
        val res = tools.getTopicSectionsText("401", listOf("H3", "H2"))
        val md = res.markdown

        // Both sections are in sectionTitles for citation badges
        assertTrue(res.sectionTitles.containsKey("H2"))
        assertTrue(res.sectionTitles.containsKey("H3"))

        // H2 is emitted in full and contains H3 content
        assertTrue(md.contains("=== Section: H2 ==="))
        assertTrue(md.contains("Infected wounds must be drained."))
        assertTrue(md.contains("Vancomycin 15 to 20 mg/kg IV"))

        // H3 is emitted with pointer to H2 and avoids duplicating the clinical text
        assertTrue(md.contains("=== Section: H3 ===\n(Content included in section [H2] above)"))

        // The text "Vancomycin 15 to 20 mg/kg IV" should appear EXACTLY once in the whole response
        val occurrences = Regex("Vancomycin 15 to 20 mg/kg IV").findAll(md).count()
        assertEquals(1, occurrences)
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
        assertEquals("medical", res.topicType)
        assertEquals(3, res.sections.size)
        assertEquals(0, res.graphics.size)

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
    fun `getTopicSectionsText returns at most twenty sections per call`() {
        val outlineHtml = (1..21).joinToString("") {
            """<li><a href="appAction({&quot;section&quot;:&quot;H$it&quot;})">Sec $it</a></li>"""
        }
        val bodyHtml = (1..21).joinToString("") {
            """<p class="headingAnchor" id="H$it"><span class="h1">Sec $it</span><span class="headingEndMark">—</span>Short text $it.</p>"""
        }
        every { contentRepository.getTopicContent("300") } returns ContentRepository.TopicContent(
            outlineHtml = """<ul id="innerOutline">$outlineHtml</ul>""",
            bodyHtml = bodyHtml
        )
        every { contentRepository.getTopicTitle("300") } returns "Topic 300"

        val res = tools.getTopicSectionsText("300", (1..21).map { "H$it" })

        // Count guard: at most 20 sections per call; the 21st is reported, never silently dropped
        val titles = res.sectionTitles
        (1..20).forEach { assertTrue(titles.containsKey("H$it")) }
        assertFalse(titles.containsKey("H21"))
        assertNull(res.truncated)
        val omitted = res.omittedSections
        assertEquals(listOf("H21"), omitted)
        val md = res.markdown
        assertTrue(md.contains("=== Section: H20 ==="))
        assertFalse(md.contains("=== Section: H21 ==="))
    }

    @Test
    fun `getTopicSectionsText does not count subsumed pointer stubs against MAX_SECTIONS_PER_CALL limit`() {
        val outlineHtml = buildString {
            append("""<li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Parent 1</a><ul>""")
            (1..5).forEach {
                append("""<li><a href="appAction({&quot;section&quot;:&quot;H1_$it&quot;})">Child 1.$it</a></li>""")
            }
            append("""</ul></li>""")
            (2..20).forEach {
                append("""<li><a href="appAction({&quot;section&quot;:&quot;H$it&quot;})">Parent $it</a></li>""")
            }
        }
        val bodyHtml = buildString {
            append("""<p class="headingAnchor" id="H1"><span class="h1">Parent 1</span><span class="headingEndMark">—</span>P1 content.""")
            (1..5).forEach {
                append("""<p class="headingAnchor" id="H1_$it"><span class="h2">Child 1.$it</span><span class="headingEndMark">—</span>C1.$it content.</p>""")
            }
            append("</p>")
            (2..20).forEach {
                append("""<p class="headingAnchor" id="H$it"><span class="h1">Parent $it</span><span class="headingEndMark">—</span>Text $it.</p>""")
            }
        }
        every { contentRepository.getTopicContent("301") } returns ContentRepository.TopicContent(
            outlineHtml = """<ul id="innerOutline">$outlineHtml</ul>""",
            bodyHtml = bodyHtml
        )
        every { contentRepository.getTopicTitle("301") } returns "Topic 301"

        val requestedIds = listOf("H1") + (1..5).map { "H1_$it" } + (2..20).map { "H$it" }
        assertEquals(25, requestedIds.size)

        val res = tools.getTopicSectionsText("301", requestedIds)

        assertNull(res.omittedSections)
        assertEquals(25, res.sectionTitles.size)
        assertTrue(res.markdown.contains("=== Section: H1_1 ===\n(Content included in section [H1] above)"))
        assertTrue(res.markdown.contains("=== Section: H20 ==="))
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

    @Test
    fun `injectWhatsNewHtml injects PCU and WhatsNew cards into topicWhatsNewContainer`() {
        val dbManager = mockk<DatabaseManager>(relaxed = true)
        val contentDao = ContentDao(dbManager)

        val whatsNewJson = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject {
                put("contentId", kotlinx.serialization.json.JsonPrimitive("152105"))
                put("pcu", kotlinx.serialization.json.JsonPrimitive(true))
                put("title", kotlinx.serialization.json.JsonPrimitive("Aspirin for VTE"))
                put("html", kotlinx.serialization.json.JsonPrimitive("<p class=\"headingAnchor\"><span class=\"h2\">Aspirin for VTE</span></p><div class=\"collapseContent\"><p>We suggest aspirin alone (Grade 2B).</p></div>"))
            })
            add(kotlinx.serialization.json.buildJsonObject {
                put("contentId", kotlinx.serialization.json.JsonPrimitive("152038"))
                put("pcu", kotlinx.serialization.json.JsonPrimitive(false))
                put("title", kotlinx.serialization.json.JsonPrimitive("STITCH trial"))
                put("html", kotlinx.serialization.json.JsonPrimitive("<p class=\"headingAnchor\"><span class=\"h2\">STITCH trial</span></p><div class=\"collapseContent\"><p>Small bites technique reduced hernias.</p></div>"))
            })
        }

        val bodyHtml = """
            <div id="topicLastUpdated">Jul 08, 2026.</div>
            <div id="topicWhatsNewContainer"></div>
            <div id="topicText"><p class="headingAnchor" id="H1"><span class="h1">Introduction</span></p><p>Content</p></div>
        """.trimIndent()

        val injected = contentDao.injectWhatsNewHtml(bodyHtml, whatsNewJson)
        assertTrue(injected.contains("""<div id="PCU0" class="utdCallout utdPCU">"""))
        assertTrue(injected.contains("""PRACTICE CHANGING UPDATE"""))
        assertTrue(injected.contains("""We suggest aspirin alone (Grade 2B)."""))
        assertTrue(injected.contains("""<div id="WhatsNew0" class="utdCallout utdWhatsNew">"""))
        assertTrue(injected.contains("""WHAT'S NEW"""))
        assertTrue(injected.contains("""Small bites technique reduced hernias."""))
    }

    @Test
    fun `getTopicSectionsText extracts PCU0 and WhatsNew0 sections`() = runBlocking {
        val outlineHtml = """
            <ul id="innerOutline">
                <li id="pcuOutlineItem" class="topicChanged"><a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;topic&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;500&quot;,&quot;section&quot;:&quot;PCU0&quot;}]});"><span>PRACTICE CHANGING UPDATE</span></a></li>
                <li id="whatsNewOutlineItem" class="topicChanged"><a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;topic&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;500&quot;,&quot;section&quot;:&quot;WhatsNew0&quot;}]});"><span>WHAT'S NEW</span></a></li>
                <li><a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;topic&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;500&quot;,&quot;section&quot;:&quot;H1&quot;}]});"><span>Introduction</span></a></li>
            </ul>
        """.trimIndent()

        val bodyHtml = """
            <div id="topicWhatsNewContainer">
                <div id="PCU0" class="utdCallout utdPCU"><div class="calloutBadge">PRACTICE CHANGING UPDATE</div><p class="headingAnchor"><span class="h2">Aspirin for VTE</span></p><div class="collapseContent"><p>We suggest aspirin alone.</p></div></div>
                <div id="WhatsNew0" class="utdCallout utdWhatsNew"><div class="calloutBadge">WHAT'S NEW</div><p class="headingAnchor"><span class="h2">STITCH trial</span></p><div class="collapseContent"><p>Small bites closure.</p></div></div>
            </div>
            <div id="topicText"><p class="headingAnchor" id="H1"><span class="h1">Introduction</span></p><p>Standard intro prose.</p></div>
        """.trimIndent()

        every { contentRepository.getTopicContent("500") } returns ContentRepository.TopicContent(
            outlineHtml = outlineHtml,
            bodyHtml = bodyHtml
        )

        val res = tools.getTopicSectionsText("500", listOf("PCU0", "WhatsNew0"))
        assertEquals("500", res.topicId)
        assertTrue(res.markdown.contains("PRACTICE CHANGING UPDATE"))
        assertTrue(res.markdown.contains("We suggest aspirin alone."))
        assertTrue(res.markdown.contains("WHAT'S NEW"))
        assertTrue(res.markdown.contains("Small bites closure."))
        assertFalse(res.markdown.contains("Standard intro prose."))
        assertEquals("PRACTICE CHANGING UPDATE", res.sectionTitles["PCU0"])
        assertEquals("WHAT'S NEW", res.sectionTitles["WhatsNew0"])
    }
}

