package com.clinref.app.ui.content

import org.junit.Assert.assertTrue
import org.junit.Test

class CssBuilderTextSelectionTest {

    @Test
    fun documentCss_includesSelectionAndUserSelectRules() {
        val css = CssBuilder(ThemeColors.light()).buildDocumentCss()

        assertTrue("Document CSS should declare -webkit-user-select: text", css.contains("-webkit-user-select: text;"))
        assertTrue("Document CSS should declare user-select: text", css.contains("user-select: text;"))
        assertTrue("Document CSS should declare ::selection pseudo-element", css.contains("::selection {"))
        assertTrue("Document CSS should declare universal *::selection pseudo-element", css.contains("*::selection {"))
        assertTrue("Document CSS should declare ::-moz-selection for Gecko", css.contains("::-moz-selection {"))
        assertTrue("Document CSS should declare universal *::-moz-selection", css.contains("*::-moz-selection {"))
    }

    @Test
    fun graphicPopupCss_includesSelectionAndUserSelectRules() {
        val css = CssBuilder(ThemeColors.dark()).buildGraphicPopupCss()

        assertTrue("Graphic popup CSS should declare -webkit-user-select: text", css.contains("-webkit-user-select: text;"))
        assertTrue("Graphic popup CSS should declare user-select: text", css.contains("user-select: text;"))
        assertTrue("Graphic popup CSS should declare ::selection pseudo-element", css.contains("::selection {"))
        assertTrue("Graphic popup CSS should declare universal *::selection pseudo-element", css.contains("*::selection {"))
        assertTrue("Graphic popup CSS should declare ::-moz-selection", css.contains("::-moz-selection {"))
        assertTrue("Graphic popup CSS should declare universal *::-moz-selection", css.contains("*::-moz-selection {"))
    }
}
