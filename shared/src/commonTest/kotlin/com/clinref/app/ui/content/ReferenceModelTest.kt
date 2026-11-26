package com.clinref.app.ui.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReferenceModelTest {

    @Test
    fun `fromGradeId with null or empty defaults to generic GRADE recommendation overview NOT Grade 1A`() {
        val fromNull = GradeSheetData.fromGradeId(null)
        val fromEmpty = GradeSheetData.fromGradeId("")
        val fromBlank = GradeSheetData.fromGradeId("   ")
        val fromUnknown = GradeSheetData.fromGradeId("unknown_999")

        assertEquals("GRADE Recommendation", fromNull.grade)
        assertEquals(GradeSheetData.GENERIC_RECOMMENDATION, fromNull)
        assertEquals(GradeSheetData.GENERIC_RECOMMENDATION, fromEmpty)
        assertEquals(GradeSheetData.GENERIC_RECOMMENDATION, fromBlank)
        assertEquals(GradeSheetData.GENERIC_RECOMMENDATION, fromUnknown)

        // Must NEVER default to Grade 1A
        assertNotEquals("Grade 1A", fromNull.grade)
        assertNotEquals("Grade 1A", fromEmpty.grade)
        assertNotEquals("Grade 1A", fromUnknown.grade)
    }

    @Test
    fun `fromGradeId correctly maps known GRADE numbers`() {
        assertEquals("Grade 1A", GradeSheetData.fromGradeId("1").grade)
        assertEquals("Grade 1B", GradeSheetData.fromGradeId("2").grade)
        assertEquals("Grade 1C", GradeSheetData.fromGradeId("3").grade)
        assertEquals("Grade 2A", GradeSheetData.fromGradeId("4").grade)
        assertEquals("Grade 2B", GradeSheetData.fromGradeId("5").grade)
        assertEquals("Grade 2C", GradeSheetData.fromGradeId("6").grade)
    }

    @Test
    fun `ContentSheet sealed interface enforces mutual exclusivity`() {
        val refData = ReferenceSheetData(
            references = listOf(
                ReferenceItem(number = 1, abstractId = "100", citation = "Author et al. 2024", journal = "NEJM")
            )
        )
        val gradeData = GradeSheetData.fromGradeId("1")

        val sheet1: ContentSheet = ContentSheet.References(refData)
        val sheet2: ContentSheet = ContentSheet.Grade(gradeData)

        assertTrue(sheet1 is ContentSheet.References)
        assertTrue(sheet2 is ContentSheet.Grade)
        assertEquals(refData, sheet1.data)
        assertEquals(gradeData, sheet2.data)
    }
}
