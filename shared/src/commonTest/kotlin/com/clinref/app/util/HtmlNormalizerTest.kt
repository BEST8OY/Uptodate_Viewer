package com.clinref.app.util

import com.clinref.app.ui.content.SectionType
import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlNormalizerTest {

    @Test
    fun `parseOutline strips legacy hyphen span from topic subsections at root`() {
        val outlineHtml = """
            <nav id="outlineSections">
                <h2>Outline</h2>
                <ul>
                    <li><a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;topic&quot;},&quot;data&quot;:[{&quot;section&quot;:&quot;H1&quot;}]});">TREATMENT</a>
                        <ul>
                            <li><a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;topic&quot;},&quot;data&quot;:[{&quot;section&quot;:&quot;H2&quot;}]});"><span class="legacyTopicViewHyphen">- </span>Incision and drainage</a></li>
                            <li><a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;topic&quot;},&quot;data&quot;:[{&quot;section&quot;:&quot;H3&quot;}]});"><span class="legacyTopicViewHyphen">- </span>Wound dressings</a></li>
                            <li><a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;topic&quot;},&quot;data&quot;:[{&quot;section&quot;:&quot;H4&quot;}]});"><span class="legacyTopicViewHyphen">- </span>Antibiotic therapy</a></li>
                        </ul>
                    </li>
                </ul>
            </nav>
        """.trimIndent()

        val sections = HtmlNormalizer.parseOutline(outlineHtml)

        assertEquals(4, sections.size)
        assertEquals("TREATMENT", sections[0].title)
        assertEquals(SectionType.TOPIC, sections[0].sectionType)

        assertEquals("Incision and drainage", sections[1].title)
        assertEquals("Wound dressings", sections[2].title)
        assertEquals("Antibiotic therapy", sections[3].title)
    }

    @Test
    fun `parseOutline formats graphics cleanly with and without labels`() {
        val outlineHtml = """
            <nav id="outlineGraphics">
                <ul>
                    <li><a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;graphic&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;116392&quot;,&quot;type&quot;:&quot;graphic&quot;,&quot;subtype&quot;:&quot;graphic_table&quot;,&quot;label&quot;:&quot;table 1&quot;}]});" class="graphic graphic_table"><span class="legacyTopicViewHyphen">- </span>Diagnosis criteria</a></li>
                    <li><a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;graphic&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;62246&quot;,&quot;type&quot;:&quot;graphic&quot;,&quot;subtype&quot;:&quot;graphic_table&quot;}]});" class="graphic graphic_table"><span class="legacyTopicViewHyphen">- </span>Risk factors for SSI</a></li>
                </ul>
            </nav>
        """.trimIndent()

        val sections = HtmlNormalizer.parseOutline(outlineHtml)

        assertEquals(2, sections.size)
        assertEquals("table 1 - Diagnosis criteria", sections[0].title)
        assertEquals(SectionType.GRAPHIC, sections[0].sectionType)

        assertEquals("Risk factors for SSI", sections[1].title)
        assertEquals(SectionType.GRAPHIC, sections[1].sectionType)
    }

    @Test
    fun `parseOutline deduplicates sr-button shortcut and repeated sections`() {
        val outlineHtml = """
            <nav id="outlineSections">
                <ul id="innerOutline">
                    <li class="sr-button"><a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;topic&quot;,&quot;section&quot;:&quot;H188821106&quot;},&quot;data&quot;:[{&quot;section&quot;:&quot;H188821106&quot;}]});"><span>SUMMARY AND RECOMMENDATIONS</span></a></li>
                    <li><a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;topic&quot;,&quot;section&quot;:&quot;H1&quot;},&quot;data&quot;:[{&quot;section&quot;:&quot;H1&quot;}]});">INTRODUCTION</a></li>
                    <li><a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;topic&quot;,&quot;section&quot;:&quot;H188821106&quot;},&quot;data&quot;:[{&quot;section&quot;:&quot;H188821106&quot;}]});">SUMMARY AND RECOMMENDATIONS</a></li>
                </ul>
            </nav>
        """.trimIndent()

        val sections = HtmlNormalizer.parseOutline(outlineHtml)

        assertEquals(2, sections.size)
        assertEquals(listOf("H1", "H188821106"), sections.map { it.id })
        assertEquals("INTRODUCTION", sections[0].title)
        assertEquals("SUMMARY AND RECOMMENDATIONS", sections[1].title)
    }

    @Test
    fun `normalizeReferences parses ordered list and injects ref anchors`() {
        val html = """
            <p>Some text with inline ref [<a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;abstract&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;118701&quot;,&quot;number&quot;:&quot;1&quot;}]});">1</a>].</p>
            <div id="references">
                <h1>REFERENCES</h1>
                <ol id="reference">
                    <li><a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;abstract&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;118701&quot;,&quot;number&quot;:&quot;1&quot;}]});" class="abstract_t">Sibai BM, Gordon T. Risk factors for preeclampsia. Am J Obstet Gynecol 1995; 172:642.</a></li>
                    <li><a href="javascript:appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;abstract&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;118710&quot;,&quot;number&quot;:&quot;2&quot;}]});" class="abstract_t">Smith JA. Postoperative care. Lancet 2000; 355:100.</a></li>
                </ol>
            </div>
        """.trimIndent()

        val (modifiedHtml, refs) = HtmlNormalizer.normalizeReferences(html)

        assertEquals(2, refs.size)
        val ref1 = refs[1]!!
        assertEquals(1, ref1.number)
        assertEquals("118701", ref1.abstractId)
        assertEquals("Sibai BM, Gordon T. Risk factors for preeclampsia. Am J Obstet Gynecol 1995; 172:642.", ref1.citation)
        assertEquals("Sibai BM, Gordon T", ref1.authors)
        assertEquals("Risk factors for preeclampsia", ref1.title)
        assertEquals("Am J Obstet Gynecol 1995; 172:642.", ref1.journal)

        // Check anchor injection
        assert(modifiedHtml.contains("""id="ref_1""""))
        assert(modifiedHtml.contains("""name="ref_1""""))
        assert(modifiedHtml.contains("""id="ref_2""""))
    }

    @Test
    fun `normalizeReferences returns unchanged when no reference list is present`() {
        val html = "<p>No references here</p>"
        val (modifiedHtml, refs) = HtmlNormalizer.normalizeReferences(html)
        assertEquals(html, modifiedHtml)
        assertEquals(0, refs.size)
    }
}
