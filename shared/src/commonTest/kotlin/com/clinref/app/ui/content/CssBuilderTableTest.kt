package com.clinref.app.ui.content

import kotlin.test.Test
import kotlin.test.assertTrue

class CssBuilderTableTest {

    @Test
    fun documentCss_includesSharedTableClasses() {
        val css = CssBuilder(ThemeColors.light()).buildDocumentCss()

        // Alignment
        assertTrue(css.contains(".centered,"), "Document CSS should include centered class")
        assertTrue(css.contains(".align_middle,"), "Document CSS should include align_middle class")

        // Highlighting / Zebra striping
        assertTrue(css.contains(".highlight_lght_gray_text,"), "Document CSS should include highlight_lght_gray_text")
        assertTrue(css.contains(".highlight_gray_text,"), "Document CSS should include highlight_gray_text")

        // Subtitles tier 2 & 3
        assertTrue(css.contains(".subtitle2_single {"), "Document CSS should include subtitle2_single")
        assertTrue(css.contains(".subtitle3_left_align,"), "Document CSS should include subtitle3_left_align")
        assertTrue(css.contains(".subtitle1_single_white {"), "Document CSS should include subtitle1_single_white")

        // Hierarchy indents
        assertTrue(css.contains(".indent3 {"), "Document CSS should include indent3")
        assertTrue(css.contains(".sublist2,"), "Document CSS should include sublist2")

        // Alerts & Accents
        assertTrue(css.contains(".red,"), "Document CSS should include red alert class")
        assertTrue(css.contains(".arrow,"), "Document CSS should include arrow class")
        assertTrue(css.contains(".primarybox_left {"), "Document CSS should include primarybox_left")
    }

    @Test
    fun graphicPopupCss_includesTableRulesAndListFormats() {
        val css = CssBuilder(ThemeColors.dark()).buildGraphicPopupCss()

        // Alignment
        assertTrue(css.contains(".centered,"), "Graphic CSS should include centered class")

        // Highlights
        assertTrue(css.contains(".highlight_lght_gray_text,"), "Graphic CSS should include highlight_lght_gray_text")

        // Subtitles
        assertTrue(css.contains(".subtitle3_left_align,"), "Graphic CSS should include subtitle3_left_align")
        assertTrue(css.contains(".subtitle2_single {"), "Graphic CSS should include subtitle2_single")

        // List formats in table cells
        assertTrue(css.contains("ol.numbers_to_nine,"), "Graphic CSS should include numbers_to_nine list format")
        assertTrue(css.contains("ol.list_upper_alpha {"), "Graphic CSS should include list_upper_alpha format")
        assertTrue(css.contains("ol.list_lower_alpha {"), "Graphic CSS should include list_lower_alpha format")

        // Red alert
        assertTrue(css.contains(".red,"), "Graphic CSS should include red alert")
    }
}
