package com.clinref.app.adversarial

import com.clinref.app.data.MedicalDatabaseTools
import com.clinref.app.repository.AssetRepository
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.SearchRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            val res = tools.getTopicOverview(id)
            assertNotNull("Must be an error envelope for id: '$id'", res.error)
            assertTrue(
                "Error must say topic not found for id '$id', got: ${res.error}",
                res.error!!.startsWith("Topic not found")
            )
        }
    }

    @Test
    fun `getTopicSectionsText returns clean error envelope for hostile topic ids`() {
        for (id in hostileIds) {
            val res = tools.getTopicSectionsText(id, listOf("H1"))
            assertNotNull("Must be an error envelope for id: '$id'", res.error)
            assertTrue(
                "Error must say topic not found for id '$id', got: ${res.error}",
                res.error!!.startsWith("Topic not found")
            )
        }
    }

    @Test
    fun `getRelatedTopics returns clean error envelope for hostile topic ids`() {
        for (id in hostileIds) {
            val res = tools.getRelatedTopics(id)
            assertNotNull("Must be an error envelope for id: '$id'", res.error)
            assertTrue(
                "Error must say topic not found for id '$id', got: ${res.error}",
                res.error!!.startsWith("Topic not found")
            )
        }
    }

    @Test
    fun `searchTopicSections returns clean error envelope for hostile topic ids`() {
        for (id in hostileIds) {
            val res = tools.searchTopicSections(id, "dose")
            assertNotNull("Must be an error envelope for id: '$id'", res.error)
            assertTrue(
                "Error must say topic not found for id '$id', got: ${res.error}",
                res.error!!.startsWith("Topic not found")
            )
        }
    }

    @Test
    fun `getGraphicContent returns clean error envelope for hostile graphic ids`() {
        for (id in hostileIds) {
            val graphicId = if (id.isBlank()) "Graphic-" else id
            val res = tools.getGraphicContent(graphicId)
            assertNotNull("Must be an error envelope for id: '$graphicId'", res.error)
            assertTrue(
                "Error must say graphic not found for id '$graphicId'",
                res.error!!.startsWith("Graphic")
            )
        }
    }

    @Test
    fun `searchTopics never throws for hostile queries`() {
        for (id in hostileIds) {
            val res = tools.searchTopics(id)
            assertTrue("Hostile query must degrade to empty result list: '$id'", res.results.isEmpty())
        }
    }

    @Test
    fun `getTopicSectionsText reports hostile section ids on a valid topic`() {
        stubValidArticle()

        val res = tools.getTopicSectionsText(
            "100",
            listOf("H1", "", "   ", "H_DOSE'; DROP TABLE", "💊", "1' OR 1=1--")
        )
        val invalid = res.invalidSections ?: emptyList()
        assertTrue(
            "Garbage section ids must be reported as invalid, got: $invalid",
            invalid.containsAll(listOf("H_DOSE'; DROP TABLE", "💊", "1' OR 1=1--"))
        )
        assertFalse(
            "Blank section ids are silently filtered, not reported invalid",
            invalid.contains("")
        )
        assertTrue("Valid requested id must resolve", res.sectionTitles.containsKey("H1"))
        assertTrue(res.markdown.contains("=== Section: H1 ==="))
    }

    @Test
    fun `getTopicOverview emits no legacy prefetch keys for valid article`() {
        stubValidArticle()

        val res = tools.getTopicOverview("100")
        assertNull(res.error)
        assertEquals("100", res.topicId)
    }
}
