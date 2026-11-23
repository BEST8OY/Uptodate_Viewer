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
        assertTrue(css.contains(".primarybox_center {"), "Document CSS should include primarybox_center")
        assertTrue(css.contains(".primarybox_key {"), "Document CSS should include primarybox_key")
        assertTrue(css.contains(".large_checkmark {"), "Document CSS should include large_checkmark")
        assertTrue(css.contains("table.container {"), "Document CSS should include table.container")
        assertTrue(css.contains(".border_left_thick,"), "Document CSS should include border_left_thick")
        assertTrue(css.contains(".border_bottom_white,"), "Document CSS should include border_bottom_white")
    }

    @Test
    fun graphicPopupCss_includesTableRulesAndListFormats() {
        val css = CssBuilder(ThemeColors.dark()).buildGraphicPopupCss()

        // Alignment
        assertTrue(css.contains(".centered,"), "Graphic CSS should include centered class")
        assertTrue(css.contains(".align_top,"), "Graphic CSS should include align_top class")
        assertTrue(css.contains(".cntnt td, .cntnt th {"), "Graphic CSS should include cell left alignment")

        // Highlights
        assertTrue(css.contains(".highlight_lght_gray_text,"), "Graphic CSS should include highlight_lght_gray_text")
        assertTrue(css.contains(".highlight_sub3_gray_text,"), "Graphic CSS should include highlight_sub3_gray_text")
        assertTrue(css.contains(".highlight_red_text,"), "Graphic CSS should include highlight_red_text")
        assertTrue(css.contains(".highlight_yellow_text,"), "Graphic CSS should include highlight_yellow_text")
        assertTrue(css.contains(".highlight_green_text,"), "Graphic CSS should include highlight_green_text")

        // Subtitles
        assertTrue(css.contains(".subtitle3_left_align,"), "Graphic CSS should include subtitle3_left_align")
        assertTrue(css.contains(".subtitle2_single {"), "Graphic CSS should include subtitle2_single")

        // List formats in table cells
        assertTrue(css.contains("ol.numbers_to_nine,"), "Graphic CSS should include numbers_to_nine list format")
        assertTrue(css.contains("ol.list_upper_alpha {"), "Graphic CSS should include list_upper_alpha format")
        assertTrue(css.contains("ol.list_lower_alpha {"), "Graphic CSS should include list_lower_alpha format")
        assertTrue(css.contains("ol.list_upper_roman {"), "Graphic CSS should include list_upper_roman format")
        assertTrue(css.contains("ul.decimal_heading {"), "Graphic CSS should include ul.decimal_heading bullet format")
        assertTrue(css.contains("ul.list_second_level {"), "Graphic CSS should include list_second_level circle format")
        assertTrue(css.contains("li.none {"), "Graphic CSS should include li.none unstyled format")

        // Red alert
        assertTrue(css.contains(".red,"), "Graphic CSS should include red alert")
    }

    @Test
    fun css_includesDarkModeContrastAndResponsiveFixes() {
        val lightCss = CssBuilder(ThemeColors.light()).buildDocumentCss()
        val darkCss = CssBuilder(ThemeColors.dark()).buildDocumentCss()

        assertTrue(lightCss.contains("color-scheme: light;"), "Light CSS should set color-scheme: light")
        assertTrue(darkCss.contains("color-scheme: dark;"), "Dark CSS should set color-scheme: dark")
        assertTrue(darkCss.contains("--clinical-green:"), "CSS should declare --clinical-green")
        assertTrue(darkCss.contains("--on-caution: #000000;"), "CSS should declare --on-caution")
        assertTrue(darkCss.contains("[id] { scroll-margin-top: 24px; }"), "CSS should include scroll margin for anchors")
        assertTrue(darkCss.contains("ul, ol { padding-left: 24px;"), "CSS should preserve list indentation")
        assertTrue(darkCss.contains("img { max-width: 100%;"), "CSS should constrain images to 100% max width")
        assertTrue(darkCss.contains("min-width: 481px) and (max-width: 599px)"), "CSS should use non-overlapping 481-599px media query")
        assertTrue(darkCss.contains("a[href^=\"http://\"]::after"), "Print CSS should only append URL for external links")
    }
}
