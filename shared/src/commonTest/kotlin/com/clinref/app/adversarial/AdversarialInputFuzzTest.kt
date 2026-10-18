package com.clinref.app.adversarial

import com.clinref.app.data.MedicalDatabaseTools
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Consolidated hostile-input battery for the retrieval tool surface (all 6 tools).
 *
 * Replaces the per-milestone adversarial files. Rule-level safety coverage lives in
 * `SafetyValidatorTest`, graph wiring in `ClinicalAgentStrategyTest`, and tool
 * contracts in `MedicalDatabaseToolsTest` — this file only asserts that hostile
 * IDs/inputs are neutralized into clean structured error envelopes without
 * exceptions and without legacy response keys leaking across the tool boundary.
 */
class AdversarialInputFuzzTest {

    private lateinit var contentRepository: ContentRepository
    private lateinit var assetRepository: AssetRepository
    private lateinit var tools: MedicalDatabaseTools
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val hostileIds = listOf(
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
        "🔥",
        "١٢٣",
        "123\u0000",
        "999999999999999999999999999999",
    )

    @Before
    fun setUp() {
        // All lookups miss: hostile inputs must degrade to structured errors, not exceptions.
        contentRepository = mockk()
        assetRepository = mockk()
        every { contentRepository.getTopicContent(any()) } returns null
        every { contentRepository.getTopicTitle(any()) } returns null
        every { assetRepository.getGraphic(any()) } returns null

        tools = MedicalDatabaseTools(
            searchRepository = mockk(relaxed = true),
            contentRepository = contentRepository,
            assetRepository = assetRepository
        )
    }

    private fun stubValidArticle() {
        every { contentRepository.getTopicContent("100") } returns ContentRepository.TopicContent(
            outlineHtml = """
                <ul id="innerOutline">
                    <li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Overview</a></li>
                </ul>
            """.trimIndent(),
            bodyHtml = """
                <p id="H1">Overview body content.</p>
            """.trimIndent()
        )
        every { contentRepository.getTopicTitle("100") } returns "Hostile Probe"
    }

    @Test
    fun `getTopicOverview returns clean error envelope for hostile topic ids`() {
        for (id in hostileIds) {
            val obj = json.parseToJsonElement(tools.getTopicOverview(id)).jsonObject
            assertTrue("Must be an error envelope for id: '$id'", obj.containsKey("error"))
            assertTrue(
                "Error must say topic not found for id '$id', got: ${obj["error"]?.jsonPrimitive?.content}",
                obj["error"]!!.jsonPrimitive.content.startsWith("Topic not found")
            )
        }
    }

    @Test
    fun `getTopicSectionsText returns clean error envelope for hostile topic ids`() {
        for (id in hostileIds) {
            val obj = json.parseToJsonElement(tools.getTopicSectionsText(id, listOf("H1"))).jsonObject
            assertTrue("Must be an error envelope for id: '$id'", obj.containsKey("error"))
            assertTrue(
                "Error must say topic not found for id '$id', got: ${obj["error"]?.jsonPrimitive?.content}",
                obj["error"]!!.jsonPrimitive.content.startsWith("Topic not found")
            )
        }
    }

    @Test
    fun `getRelatedTopics returns clean error envelope for hostile topic ids`() {
        for (id in hostileIds) {
            val obj = json.parseToJsonElement(tools.getRelatedTopics(id)).jsonObject
            assertTrue("Must be an error envelope for id: '$id'", obj.containsKey("error"))
            assertTrue(
                "Error must say topic not found for id '$id', got: ${obj["error"]?.jsonPrimitive?.content}",
                obj["error"]!!.jsonPrimitive.content.startsWith("Topic not found")
            )
        }
    }

    @Test
    fun `searchTopicSections returns clean error envelope for hostile topic ids`() {
        for (id in hostileIds) {
            val obj = json.parseToJsonElement(tools.searchTopicSections(id, "dose")).jsonObject
            assertTrue("Must be an error envelope for id: '$id'", obj.containsKey("error"))
            assertTrue(
                "Error must say topic not found for id '$id', got: ${obj["error"]?.jsonPrimitive?.content}",
                obj["error"]!!.jsonPrimitive.content.startsWith("Topic not found")
            )
        }
    }

    @Test
    fun `getGraphicContent returns clean error envelope for hostile graphic ids`() {
        for (id in hostileIds) {
            val graphicId = if (id.isBlank()) "Graphic-" else id
            val obj = json.parseToJsonElement(tools.getGraphicContent(graphicId)).jsonObject
            assertTrue("Must be an error envelope for id: '$graphicId'", obj.containsKey("error"))
            assertTrue(
                "Error must say graphic not found for id '$graphicId'",
                obj["error"]!!.jsonPrimitive.content.startsWith("Graphic")
            )
        }
    }

    @Test
    fun `searchTopics never throws for hostile queries`() {
        for (id in hostileIds) {
            val obj = json.parseToJsonElement(tools.searchTopics(id)).jsonObject
            assertTrue("Hostile query must degrade to empty result list: '$id'", obj["results"]!!.jsonArray.isEmpty())
        }
    }

    @Test
    fun `getTopicSectionsText reports hostile section ids on a valid topic`() {
        stubValidArticle()

        val result = tools.getTopicSectionsText(
            "100",
            listOf("H1", "", "   ", "H_DOSE'; DROP TABLE", "💊", "1' OR 1=1--")
        )
        val obj = json.parseToJsonElement(result).jsonObject

        val invalid = obj["invalidSections"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(
            "Garbage section ids must be reported as invalid, got: $invalid",
            invalid.containsAll(listOf("H_DOSE'; DROP TABLE", "💊", "1' OR 1=1--"))
        )
        assertFalse(
            "Blank section ids are silently filtered, not reported invalid",
            invalid.contains("")
        )
        assertTrue("Valid requested id must resolve", obj["sectionTitles"]!!.jsonObject.containsKey("H1"))
        assertTrue(obj["markdown"]!!.jsonPrimitive.content.contains("=== Section: H1 ==="))
    }

    @Test
    fun `getTopicOverview emits no legacy prefetch keys for valid article`() {
        stubValidArticle()

        val raw = tools.getTopicOverview("100")
        val obj = json.parseToJsonElement(raw).jsonObject

        assertFalse(obj.containsKey("prefetchedSections"))
        assertFalse(obj.containsKey("highYieldSections"))
        assertEquals("100", obj["topicId"]!!.jsonPrimitive.content)
    }
}
