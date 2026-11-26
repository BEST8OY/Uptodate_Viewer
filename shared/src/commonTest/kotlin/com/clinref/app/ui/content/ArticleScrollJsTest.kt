package com.clinref.app.ui.content

import com.clinref.app.domain.ReadingPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleScrollJsTest {

    @Test
    fun `parsePosition successfully decodes valid payload`() {
        val payload = """
            {
                "anchor": "b:15:0.4500",
                "progress": 0.4215,
                "sectionId": "sec-treatment",
                "contentRev": "a1b2c3d4"
            }
        """.trimIndent()

        val parsed = ArticleScrollJs.parsePosition(payload)
        assertNotNull(parsed)
        assertEquals("b:15:0.4500", parsed?.anchor)
        assertEquals(0.4215f, parsed?.progress)
        assertEquals("sec-treatment", parsed?.sectionId)
        assertEquals("a1b2c3d4", parsed?.contentRev)
    }

    @Test
    fun `parsePosition handles null sectionId`() {
        val payload = """
            {
                "anchor": "top",
                "progress": 0.0,
                "sectionId": null,
                "contentRev": "rev1"
            }
        """.trimIndent()

        val parsed = ArticleScrollJs.parsePosition(payload)
        assertNotNull(parsed)
        assertEquals("top", parsed?.anchor)
        assertEquals(0f, parsed?.progress)
        assertNull(parsed?.sectionId)
        assertEquals("rev1", parsed?.contentRev)
    }

    @Test
    fun `parsePosition returns null on invalid json`() {
        assertNull(ArticleScrollJs.parsePosition("not json"))
        assertNull(ArticleScrollJs.parsePosition(""))
    }

    @Test
    fun `inject embeds start target and script into head`() {
        val html = "<html><head><title>Test</title></head><body><p>Hello</p></body></html>"
        val start = StartTarget.Resume(
            ReadingPosition(anchor = "b:5:0.2", progress = 0.3f, sectionId = "s1", contentRev = "rev1")
        )

        val injected = ArticleScrollJs.inject(html, start, "rev1")

        assertTrue(injected.contains("window.__clinrefStart="))
        assertTrue(injected.contains("window.__clinrefRev=\"rev1\""))
        assertTrue(injected.contains("window.__clinrefFlush"))
        assertTrue(injected.contains("</head>"))
    }

    @Test
    fun `inject prepends if no head tag present`() {
        val html = "<body><p>No head</p></body>"
        val start = StartTarget.Top

        val injected = ArticleScrollJs.inject(html, start, "rev2")

        assertTrue(injected.startsWith("<script>window.__clinrefStart="))
        assertTrue(injected.contains("window.__clinrefRev=\"rev2\""))
    }
}
