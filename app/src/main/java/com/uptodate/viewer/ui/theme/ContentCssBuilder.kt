package com.uptodate.viewer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt

/**
 * Port of the Python CSSBuilder — generates the full CSS `<style>` block
 * for topic and outline HTML rendering in WebView.
 */
class ContentCssBuilder(
    private val isDark: Boolean,
    private val textColor: Color,
    private val bgColor: Color,
    private val linkColor: Color,
    private val drugColor: Color,
    private val dangerColor: Color,
    private val cautionColor: Color,
    private val gradeColor: Color,
    private val borderColor: Color,
    private val selectionColor: Color,
) {
    private val hexText = textColor.toHex()
    private val hexBg = bgColor.toHex()
    private val hexLink = linkColor.toHex()
    private val hexDrug = drugColor.toHex()
    private val hexDanger = dangerColor.toHex()
    private val hexCaution = cautionColor.toHex()
    private val hexGrade = gradeColor.toHex()
    private val hexBorder = borderColor.toHex()
    private val hexSelection = selectionColor.toHex()

    fun build(): String = """
        <style>
            ${resetAndBase()}
            ${layoutContainers()}
            ${headings()}
            ${links()}
            ${contributors()}
            ${bulletLists()}
            ${tables()}
            ${references()}
            ${drugMonograph()}
            ${patientEducation()}
            ${calculatorStyles()}
            ${outlineSidebar()}
            ${scrollbarStyles()}
        </style>
    """.trimIndent()

    fun buildOutline(): String = """
        <style>
            ${resetAndBase()}
            ${outlineSidebar()}
        </style>
    """.trimIndent()

    private fun resetAndBase(): String = """
        * { box-sizing: border-box; }
        body {
            margin: 0; padding: 16px;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            font-size: 16px; line-height: 1.6;
            color: $hexText; background-color: $hexBg;
            -webkit-font-smoothing: antialiased;
        }
        ::selection { background: $hexSelection; }
    """

    private fun layoutContainers(): String = """
        .utdArticleSection { margin-bottom: 1.2em; }
        #topicContent { max-width: 100%; }
    """

    private fun headings(): String = """
        h1, .topic-title { font-size: 1.6em; font-weight: 700; margin: 0.8em 0 0.4em; line-height: 1.3; }
        h2, .h2 { font-size: 1.35em; font-weight: 600; margin: 0.8em 0 0.3em; }
        h3, .h3 { font-size: 1.15em; font-weight: 600; margin: 0.7em 0 0.3em; }
        h4, .h4 { font-size: 1.05em; font-weight: 600; margin: 0.6em 0 0.2em; }
        h5, .h5 { font-size: 1em; font-weight: 600; margin: 0.5em 0 0.2em; }
        h6, .h6 { font-size: 0.95em; font-weight: 600; margin: 0.5em 0 0.2em; }
    """

    private fun links(): String = """
        a { color: $hexLink; text-decoration: none; }
        a:hover { text-decoration: underline; }
        a.drug { color: $hexDrug; }
        a.grade { color: $hexGrade; }
        .drug { color: $hexDrug; }
        .danger { color: $hexDanger; }
        .caution { color: $hexCaution; }
        .warning-text { color: $hexDanger; font-weight: 600; }
    """

    private fun contributors(): String = """
        .meta-links-row { font-size: 0.85em; margin: 0.5em 0 1em; padding: 4px 0; border-bottom: 1px solid $hexBorder; }
        .meta-links-row a { margin-right: 4px; }
        .meta-separator { display: inline-block; width: 1px; height: 12px; background: $hexBorder; margin: 0 8px; vertical-align: middle; }
        #topicContributors, #topicDisclosures { font-size: 0.9em; margin: 0.5em 0; padding: 8px; background: ${hexBg}80; border-left: 3px solid $hexLink; }
        .contributor-group-title { font-weight: 600; margin-top: 8px; }
        .contributor-list { list-style: none; padding: 0; margin: 4px 0; }
        .contributor-list li { margin: 4px 0; }
        .contributor-name { font-weight: 500; }
        .contributor-associations { font-size: 0.9em; color: ${hexText}99; }
        .contributor-disclosure { font-style: italic; font-size: 0.9em; color: ${hexText}AA; }
        #literatureReviewDate { font-size: 0.85em; color: ${hexText}88; margin: 4px 0; }
    """

    private fun bulletLists(): String = """
        .bulletIndent1 { margin-left: 1.5em; }
        .bulletIndent2 { margin-left: 3em; }
        .bulletIndent3 { margin-left: 4.5em; }
        .glyph { margin-right: 4px; }
        ul, ol { padding-left: 1.5em; margin: 0.3em 0; }
        li { margin: 2px 0; }
    """

    private fun tables(): String = """
        table { width: 100%; border-collapse: collapse; margin: 0.5em 0; font-size: 0.9em; }
        th, td { border: 1px solid $hexBorder; padding: 6px 8px; text-align: left; }
        th { font-weight: 600; background: ${hexText}15; }
        .subtitle1 { font-size: 0.95em; }
        .subtitle2 { font-size: 0.85em; color: ${hexText}AA; }
        .indent1 { padding-left: 1.5em; }
        .indent2 { padding-left: 3em; }
    """

    private fun references(): String = """
        #references { font-size: 0.9em; margin-top: 1.5em; }
        #reference { margin: 4px 0; padding: 4px; border-left: 2px solid $hexBorder; }
    """

    private fun drugMonograph(): String = """
        .lexiSectionElem { margin: 0.5em 0; }
        #drugTitle { font-size: 1.4em; font-weight: 700; }
        .drugH1 { font-size: 1.2em; font-weight: 600; margin-top: 0.8em; }
        .coi { color: $hexDanger; }
        .war { color: $hexDanger; }
        .collapsible { background: ${hexText}08; border-radius: 4px; padding: 4px 8px; }
        .ref-callout-list { font-size: 0.9em; }
    """

    private fun patientEducation(): String = """
        #disclaimer { font-size: 0.8em; color: ${hexText}88; margin-top: 1em; }
        #topicRetrievedDate { font-size: 0.8em; color: ${hexText}88; }
    """

    private fun calculatorStyles(): String = """
        #mc3k, .medCalc* { font-size: 0.95em; }
        #calc_main { padding: 8px; background: ${hexText}08; border-radius: 4px; }
    """

    private fun outlineSidebar(): String = """
        body.outline-mode { padding: 8px; font-size: 14px; }
        .topic-outline { margin: 0; }
        .topic-outline ul { list-style: none; padding-left: 12px; margin: 0; }
        .topic-outline li { margin: 3px 0; }
        .topic-outline a { color: $hexText; text-decoration: none; display: block; padding: 2px 4px; border-radius: 2px; }
        .topic-outline a:hover { background: ${hexText}12; color: $hexLink; }
    """

    private fun scrollbarStyles(): String = """
        ::-webkit-scrollbar { width: 6px; }
        ::-webkit-scrollbar-track { background: transparent; }
        ::-webkit-scrollbar-thumb { background: ${hexBorder}66; border-radius: 3px; }
        ::-webkit-scrollbar-thumb:hover { background: ${hexBorder}AA; }
    """
}

fun Color.toHex(): String {
    val argb = toArgb()
    val hex = "%06x".format(argb and 0x00FFFFFF)
    return "#$hex"
}
