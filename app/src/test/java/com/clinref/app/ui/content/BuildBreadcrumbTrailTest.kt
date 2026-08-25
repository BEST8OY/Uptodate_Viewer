package com.clinref.app.ui.content

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildBreadcrumbTrailTest {

    private val outline = listOf(
        OutlineSection(id = "s0", title = "Warfarin", depth = 0),
        OutlineSection(id = "s1", title = "Dosing", depth = 1),
        OutlineSection(id = "s2", title = "Perioperative", depth = 2),
        OutlineSection(id = "g1", title = "Table 1", depth = 2, sectionType = SectionType.GRAPHIC),
        OutlineSection(id = "r1", title = "Related topic", depth = 1, sectionType = SectionType.RELATED)
    )

    @Test
    fun deepTopicReturnsAncestorChain() {
        assertEquals(
            listOf("Warfarin", "Dosing", "Perioperative"),
            buildBreadcrumbTrail(outline, "s2")
        )
    }

    @Test
    fun topLevelSectionReturnsSingleEntry() {
        assertEquals(listOf("Warfarin"), buildBreadcrumbTrail(outline, "s0"))
    }

    @Test
    fun nonTopicLeafRendersAsLoneTitle() {
        assertEquals(listOf("Table 1"), buildBreadcrumbTrail(outline, "g1"))
        assertEquals(listOf("Related topic"), buildBreadcrumbTrail(outline, "r1"))
    }

    @Test
    fun unknownOrNullActiveIdYieldsEmpty() {
        assertEquals(emptyList<String>(), buildBreadcrumbTrail(outline, "missing"))
        assertEquals(emptyList<String>(), buildBreadcrumbTrail(outline, null))
        assertEquals(emptyList<String>(), buildBreadcrumbTrail(emptyList(), "s0"))
    }

    @Test
    fun unrelatedIntermediateSectionsAreSkipped() {
        val sparse = listOf(
            OutlineSection(id = "a", title = "Root", depth = 0),
            OutlineSection(id = "noise", title = "Graphic noise", depth = 1, sectionType = SectionType.GRAPHIC),
            OutlineSection(id = "b", title = "Child", depth = 1),
            OutlineSection(id = "c", title = "Grandchild", depth = 2)
        )
        assertEquals(
            listOf("Root", "Child", "Grandchild"),
            buildBreadcrumbTrail(sparse, "c")
        )
    }
}
