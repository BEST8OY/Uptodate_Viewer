package com.clinref.app.data

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
    fun `searchTopics embeds speculative outline with sections graphics and related topics`() {
        every { searchRepository.searchTopics("aspirin") } returns listOf(
            SearchResult.Topic("123", "Aspirin Overview")
        )
        every { searchRepository.getSuggestions("aspirin") } returns listOf("aspirin dose")

        val outlineHtml = """
            <a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Dosing</a>
            <a href="appAction({&quot;section&quot;:&quot;H2&quot;})">Side Effects</a>
            <a href="appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;graphic&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;G1&quot;,&quot;type&quot;:&quot;graphic&quot;,&quot;subtype&quot;:&quot;graphic_table&quot;}]})">Table 1</a>
            <a href="appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;graphic&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;G2&quot;,&quot;type&quot;:&quot;graphic&quot;,&quot;subtype&quot;:&quot;graphic_figure&quot;}]})">Fig 1</a>
            <a href="appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;topic&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;999&quot;,&quot;type&quot;:&quot;medical&quot;}]})">Related Drug</a>
        """.trimIndent()

        every { contentRepository.getTopicContent("123") } returns ContentRepository.TopicContent(
            outlineHtml = outlineHtml,
            bodyHtml = "<div id='H1'>Content</div>"
        )

        val resultJson = tools.searchTopics("aspirin")
        val obj = json.parseToJsonElement(resultJson).jsonObject
        val results = obj["results"]!!.jsonArray
        assertEquals(1, results.size)

        val topMatch = results[0].jsonObject
        assertEquals("123", topMatch["id"]!!.jsonPrimitive.content)
        assertTrue(topMatch.containsKey("outline"))

        val outline = topMatch["outline"]!!.jsonObject
        assertEquals(2, outline["sections"]!!.jsonArray.size)
        // All graphics included (no 5-item cutoff)
        assertEquals(2, outline["graphics"]!!.jsonArray.size)
        assertEquals(1, outline["relatedTopics"]!!.jsonArray.size)
    }

    @Test
    fun `searchTopics auto-retries when first query has no results`() {
        every { searchRepository.searchTopics("asprn") } returns emptyList()
        every { searchRepository.getSuggestions("asprn") } returns listOf("aspirin", "ibuprofen")
        every { searchRepository.searchTopics("aspirin") } returns listOf(
            SearchResult.Topic("123", "Aspirin")
        )

        val resultJson = tools.searchTopics("asprn")
        val obj = json.parseToJsonElement(resultJson).jsonObject
        assertEquals("aspirin", obj["query"]!!.jsonPrimitive.content)
        assertTrue(obj["message"]!!.jsonPrimitive.content.contains("Auto-refined"))
        assertEquals(1, obj["results"]!!.jsonArray.size)
    }

    @Test
    fun `getTopicOutline parses outline html into sections and graphics`() {
        val outlineHtml = """
            <a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Overview</a>
            <a href="appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;graphic&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;111&quot;,&quot;type&quot;:&quot;graphic&quot;,&quot;subtype&quot;:&quot;graphic_table&quot;}]})">Table A</a>
        """.trimIndent()

        every { contentRepository.getTopicContent("100") } returns ContentRepository.TopicContent(outlineHtml = outlineHtml, bodyHtml = "")
        every { contentRepository.getTopicTitle("100") } returns "Topic 100"

        val outlineJson = tools.getTopicOutline("100")
        val obj = json.parseToJsonElement(outlineJson).jsonObject

        assertEquals("Topic 100", obj["title"]!!.jsonPrimitive.content)
        assertEquals(1, obj["sections"]!!.jsonArray.size)
        assertEquals(1, obj["graphics"]!!.jsonArray.size)
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
}
