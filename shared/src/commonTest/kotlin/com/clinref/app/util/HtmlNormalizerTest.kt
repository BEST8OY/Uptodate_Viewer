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
}
