package com.clinref.app.ui.content

import com.clinref.app.domain.GraphicData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildGraphicHtmlTest {

    private val css = "body { color: red; }"

    @Test
    fun `injects validated base64 into img src`() {
        val graphic = GraphicData(
            id = "1",
            imageHtml = """<img src="https://example.com/a.png">""",
            base64Image = "aGVsbG8=",
        )

        val html = buildGraphicHtml(graphic, css)

        assertTrue(html.contains("""src="data:image/png;base64,aGVsbG8=""""))
        assertFalse(html.contains("example.com"))
    }

    @Test
    fun `keeps original src when base64 has invalid characters`() {
        val originalSrc = "https://example.com/a.png"
        val graphic = GraphicData(
            id = "1",
            imageHtml = """<img src="$originalSrc">""",
            base64Image = "not valid!!",
        )

        val html = buildGraphicHtml(graphic, css)

        assertTrue(html.contains(originalSrc))
        assertFalse(html.contains("data:image/png"))
    }

    @Test
    fun `leaves src untouched when base64Image is null`() {
        val originalSrc = "https://example.com/a.png"
        val graphic = GraphicData(
            id = "1",
            imageHtml = """<img src="$originalSrc">""",
        )

        val html = buildGraphicHtml(graphic, css)

        assertTrue(html.contains(originalSrc))
    }

    @Test
    fun `rewrites graphic class token to graphic_view`() {
        val graphic = GraphicData(
            id = "1",
            imageHtml = """<span class="graphic">x</span>""",
        )

        val html = buildGraphicHtml(graphic, css)

        assertTrue(html.contains("""class="graphic_view""""))
        assertFalse(html.contains("""class="graphic""""))
    }

    @Test
    fun `preserves sibling classes when rewriting graphic token`() {
        val graphic = GraphicData(
            id = "1",
            imageHtml = """<span class="keepme graphic">x</span>""",
        )

        val html = buildGraphicHtml(graphic, css)

        assertTrue(html.contains("""class="keepme graphic_view""""))
    }

    @Test
    fun `wraps fragment in document shell with css`() {
        val graphic = GraphicData(id = "1", imageHtml = "<p>x</p>")

        val html = buildGraphicHtml(graphic, css)

        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("<meta name=\"viewport\""))
        assertTrue(html.contains("<style>$css</style>"))
        assertTrue(html.contains("<body><p>x</p></body>"))
    }

    @Test
    fun `escapes unbalanced markup instead of passing it through raw`() {
        val graphic = GraphicData(id = "1", imageHtml = "<p>a < b</p>")

        val html = buildGraphicHtml(graphic, css)

        assertFalse(html.contains("< b"))
        assertTrue(html.contains("&lt; b"))
    }
}
