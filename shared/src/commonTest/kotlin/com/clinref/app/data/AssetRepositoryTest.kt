package com.clinref.app.data

import com.clinref.app.repository.AssetRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AssetRepositoryTest {

    private val assetDao: AssetDao = mockk(relaxed = true)

    @Test
    fun `caches decoded graphic per id`() {
        every { assetDao.getGraphicJson("9") } returns mapOf(
            "graphicInfo" to mapOf(
                "displayName" to "Figure 1",
                "type" to "graphic",
                "subtype" to "graphic_figure",
            ),
            "imageHtml" to "<img src=\"x\">",
            "base64Image" to "aGVsbG8=",
        )
        val repo = AssetRepository(assetDao)

        val first = repo.getGraphic("9")
        val second = repo.getGraphic("9")

        verify(exactly = 1) { assetDao.getGraphicJson("9") }
        assertSame(first, second)
        assertEquals("Figure 1", first?.title)
        assertEquals("9", first?.id)
    }

    @Test
    fun `evicts eldest entry beyond capacity and refetches`() {
        every { assetDao.getGraphicJson(any()) } returns mapOf(
            "graphicInfo" to emptyMap<String, String>(),
            "imageHtml" to "",
        )
        val repo = AssetRepository(assetDao)

        (1..17).forEach { repo.getGraphic(it.toString()) }
        repo.getGraphic("1")

        verify(exactly = 2) { assetDao.getGraphicJson("1") }
    }

    @Test
    fun `most recent entries survive beyond capacity`() {
        every { assetDao.getGraphicJson(any()) } returns mapOf(
            "graphicInfo" to emptyMap<String, String>(),
            "imageHtml" to "",
        )
        val repo = AssetRepository(assetDao)

        (1..20).forEach { repo.getGraphic(it.toString()) }
        repo.getGraphic("20")

        verify(exactly = 1) { assetDao.getGraphicJson("20") }
    }

    @Test
    fun `missing payload returns null and is not cached as success`() {
        every { assetDao.getGraphicJson("404") } returns null
        val repo = AssetRepository(assetDao)

        assertNull(repo.getGraphic("404"))
        assertNull(repo.getGraphic("404"))

        verify(exactly = 2) { assetDao.getGraphicJson("404") }
    }

    @Test
    fun `distinct ids return distinct instances`() {
        every { assetDao.getGraphicJson("1") } returns mapOf(
            "graphicInfo" to emptyMap<String, String>(),
            "imageHtml" to "<p>one</p>",
        )
        every { assetDao.getGraphicJson("2") } returns mapOf(
            "graphicInfo" to emptyMap<String, String>(),
            "imageHtml" to "<p>two</p>",
        )
        val repo = AssetRepository(assetDao)

        val a = repo.getGraphic("1")
        val b = repo.getGraphic("2")

        assertNotSame(a, b)
        assertEquals("<p>one</p>", a?.imageHtml)
        assertEquals("<p>two</p>", b?.imageHtml)
    }

    @Test
    fun `getGraphicTitle returns cached title when graphic is already loaded`() {
        every { assetDao.getGraphicJson("50") } returns mapOf(
            "graphicInfo" to mapOf(
                "displayName" to "Cached Title",
                "type" to "graphic",
                "subtype" to "graphic_table",
            ),
            "imageHtml" to "<table></table>",
        )
        val repo = AssetRepository(assetDao)
        repo.getGraphic("50")

        val title = repo.getGraphicTitle("50")

        assertEquals("Cached Title", title)
        verify(exactly = 0) { assetDao.getGraphicTitle("50") }
    }

    @Test
    fun `getGraphicTitle delegates to dao when graphic is not cached`() {
        every { assetDao.getGraphicTitle("99") } returns "DAO Title"
        val repo = AssetRepository(assetDao)

        val title = repo.getGraphicTitle("99")

        assertEquals("DAO Title", title)
        verify(exactly = 1) { assetDao.getGraphicTitle("99") }
    }
}
