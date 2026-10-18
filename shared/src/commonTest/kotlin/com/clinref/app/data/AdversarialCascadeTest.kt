package com.clinref.app.data

import com.clinref.app.domain.GraphicData
import com.clinref.app.domain.SearchResult
import com.clinref.app.repository.AssetRepository
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.SearchRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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

class AdversarialCascadeTest {

    private lateinit var searchRepository: SearchRepository
    private lateinit var contentRepository: ContentRepository
    private lateinit var assetRepository: AssetRepository
    private lateinit var tools: MedicalDatabaseTools
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

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
    fun `tool count strictly 6 on Kotlin`() {
        val toolList = tools.asToolList()
        assertEquals(6, toolList.size)
        val names = toolList.map { it.name }.toSet()
        val expected = setOf(
            "getTopicOverview",
            "searchTopics",
            "searchTopicSections",
            "getRelatedTopics",
            "getTopicSectionsText",
            "getGraphicContent"
        )
        assertEquals(expected, names)
    }

    @Test
    fun `smart section resolution with case mismatch`() {
        val outlineHtml = """
            <ul id="innerOutline">
                <li><a href="appAction({&quot;section&quot;:&quot;H_SR&quot;})">Summary &amp; Recommendations</a></li>
                <li><a href="appAction({&quot;section&quot;:&quot;H_DOSE&quot;})">Dosing &amp; Administration</a></li>
            </ul>
        """.trimIndent()
        val bodyHtml = """
            <p id="H_SR">Summary content.</p>
            <p id="H_DOSE">Dosing content.</p>
        """.trimIndent()

        every { contentRepository.getTopicContent("100") } returns ContentRepository.TopicContent(outlineHtml = outlineHtml, bodyHtml = bodyHtml)
        every { contentRepository.getTopicTitle("100") } returns "Drug A"

        // Lowercase request "h_sr"
        val res = tools.getTopicSectionsText("100", listOf("h_sr"))
        val obj = json.parseToJsonElement(res).jsonObject
        val titles = obj["sectionTitles"]!!.jsonObject
        assertTrue("Expected H_SR to be resolved from h_sr", titles.containsKey("H_SR"))

        // Overview returns outline metadata without prefetching for articles
        val resOverview = tools.getTopicOverview("100")
        val ovObj = json.parseToJsonElement(resOverview).jsonObject
        val sections = ovObj["sections"]!!.jsonArray
        assertEquals(2, sections.size)
        assertFalse("prefetchedSections must not exist", ovObj.containsKey("prefetchedSections"))
        assertFalse("highYieldSections must not exist", ovObj.containsKey("highYieldSections"))
    }

    @Test
    fun `smart section resolution with dashes and roman numerals`() {
        val outlineHtml = """
            <ul id="innerOutline">
                <li><a href="appAction({&quot;section&quot;:&quot;H_ROMAN&quot;})">IV. Special Populations</a></li>
                <li><a href="appAction({&quot;section&quot;:&quot;H_MGMT&quot;})">Management of Toxicity</a></li>
            </ul>
        """.trimIndent()
        val bodyHtml = """
            <p id="H_ROMAN">Special populations details.</p>
            <p id="H_MGMT">Toxicity management details.</p>
        """.trimIndent()

        every { contentRepository.getTopicContent("200") } returns ContentRepository.TopicContent(outlineHtml = outlineHtml, bodyHtml = bodyHtml)
        every { contentRepository.getTopicTitle("200") } returns "Drug B"

        // Request with leading dash "- Management of Toxicity"
        val resDash = tools.getTopicSectionsText("200", listOf("- Management of Toxicity"))
        val objDash = json.parseToJsonElement(resDash).jsonObject
        assertTrue("Resolved leading dash", objDash["sectionTitles"]!!.jsonObject.containsKey("H_MGMT"))

        // Request with en-dash "– Management of Toxicity"
        val resEnDash = tools.getTopicSectionsText("200", listOf("– Management of Toxicity"))
        val objEnDash = json.parseToJsonElement(resEnDash).jsonObject
        assertTrue("Resolved leading en-dash", objEnDash["sectionTitles"]!!.jsonObject.containsKey("H_MGMT"))

        // Request with Roman numeral "IV. Special Populations"
        val resRoman = tools.getTopicSectionsText("200", listOf("IV. Special Populations"))
        val objRoman = json.parseToJsonElement(resRoman).jsonObject
        assertTrue("Resolved Roman numeral header", objRoman["sectionTitles"]!!.jsonObject.containsKey("H_ROMAN"))
    }

    @Test
    fun `smart section resolution with bullet prefixes`() {
        val outlineHtml = """
            <ul id="innerOutline">
                <li><a href="appAction({&quot;section&quot;:&quot;H_WARN&quot;})">Warnings and Precautions</a></li>
            </ul>
        """.trimIndent()
        val bodyHtml = """<p id="H_WARN">Warning details.</p>"""

        every { contentRepository.getTopicContent("300") } returns ContentRepository.TopicContent(outlineHtml = outlineHtml, bodyHtml = bodyHtml)
        every { contentRepository.getTopicTitle("300") } returns "Drug C"

        // Request with bullet prefix "• Warnings and Precautions" (bullet prefix stripped by normalization)
        val resBullet = tools.getTopicSectionsText("300", listOf("• Warnings and Precautions"))
        val objBullet = json.parseToJsonElement(resBullet).jsonObject
        assertTrue(
            "Bullet prefix resolution should include H_WARN",
            objBullet["sectionTitles"]!!.jsonObject.containsKey("H_WARN")
        )
    }

    @Test
    fun `non-table graphics are rejected properly`() {
        val figGraphic = GraphicData(
            id = "fig_1",
            title = "Diagram",
            type = "graphic",
            subtype = "graphic_figure",
            imageHtml = "<div>fig</div>"
        )
        every { assetRepository.getGraphic("fig_1") } returns figGraphic

        val resFig = tools.getGraphicContent("fig_1")
        val objFig = json.parseToJsonElement(resFig).jsonObject
        assertTrue(objFig.containsKey("error"))
        assertTrue(objFig["error"]!!.jsonPrimitive.content.contains("not a table"))

        val algGraphic = GraphicData(
            id = "alg_1",
            title = "Algorithm",
            type = "graphic",
            subtype = "graphic_algorithm",
            imageHtml = "<div>alg</div>"
        )
        every { assetRepository.getGraphic("alg_1") } returns algGraphic

        val resAlg = tools.getGraphicContent("alg_1")
        val objAlg = json.parseToJsonElement(resAlg).jsonObject
        assertTrue(objAlg.containsKey("error"))
        assertTrue(objAlg["error"]!!.jsonPrimitive.content.contains("not a table"))
    }

    @Test
    fun `bidirectional serialization handles snake_case payload from Python`() {
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
    fun `bidirectional serialization handles camelCase payload`() {
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
        assertEquals("=== Section: H1 ===\nAspirin is used for...", decoded.markdown)
    }
}
