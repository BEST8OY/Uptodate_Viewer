package com.clinref.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicDataTest {

    @Test
    fun `isTable returns true for graphic_table subtype`() {
        val graphic = GraphicData(
            id = "123",
            title = "Table 1",
            type = "graphic",
            subtype = "graphic_table",
            imageHtml = "<table><tr><td>A</td></tr></table>",
        )
        assertTrue(graphic.isTable)
    }

    @Test
    fun `isTable returns false for graphic_figure subtype`() {
        val graphic = GraphicData(
            id = "456",
            title = "Figure 1",
            type = "graphic",
            subtype = "graphic_figure",
            imageHtml = "",
        )
        assertFalse(graphic.isTable)
    }

    @Test
    fun `isTable returns false for graphic_algorithm subtype`() {
        val graphic = GraphicData(
            id = "789",
            title = "Algorithm 1",
            type = "graphic",
            subtype = "graphic_algorithm",
            imageHtml = "",
        )
        assertFalse(graphic.isTable)
    }

    @Test
    fun `isTable returns false for empty subtype`() {
        val graphic = GraphicData(
            id = "000",
            title = "Unknown",
            type = "",
            subtype = "",
            imageHtml = "",
        )
        assertFalse(graphic.isTable)
    }

    @Test
    fun `defaults are correct`() {
        val graphic = GraphicData(id = "1", imageHtml = "html")
        assertEquals("", graphic.title)
        assertEquals("", graphic.type)
        assertEquals("", graphic.subtype)
        assertNull(graphic.base64Image)
        assertNull(graphic.movieUrl)
        assertFalse(graphic.isTable)
    }

    private fun <T> assertNull(value: T?) {
        assertEquals(null, value)
    }
}
