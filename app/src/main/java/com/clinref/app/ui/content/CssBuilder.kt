package com.clinref.app.ui.content

class CssBuilder(private val colors: ThemeColors) {

    private fun hexToRgba(hex: String, alpha: Float): String {
        val clean = hex.removePrefix("#")
        val (r, g, b) = when (clean.length) {
            3 -> listOf(
                clean[0].toString().repeat(2).toInt(16),
                clean[1].toString().repeat(2).toInt(16),
                clean[2].toString().repeat(2).toInt(16),
            )
            6 -> listOf(
                clean.substring(0, 2).toInt(16),
                clean.substring(2, 4).toInt(16),
                clean.substring(4, 6).toInt(16),
            )
            else -> return "rgba(0, 0, 0, $alpha)"
        }
        return "rgba($r, $g, $b, $alpha)"
    }

    fun buildDocumentCss(): String {
        val sections = listOf(
            rootVariables(),
            resetAndBase(),
            layoutContainers(),
            headings(),
            links(),
            contributors(),
            bulletLists(),
            tables(),
            references(),
            drugMonograph(),
            patientEducation(),
            calculator(),
            responsive(),
            printStyles(),
        )
        return "<style>\n${sections.joinToString("\n")}\n</style>"
    }

    fun buildGraphicPopupCss(): String {
        val sections = listOf(
            rootVariables(),
            graphicReset(),
            graphicLayout(),
            sharedTableStyles(),
            graphicTableSpecifics(),
            graphicNavigation(),
            responsive(),
            printStyles(),
        )
        return sections.joinToString("\n")
    }

    // ── Theme tokens as CSS custom properties ────────────────────────

    private fun rootVariables(): String = """
/* Theme Tokens */
:root {
    --bg: ${colors.bg};
    --surface: ${colors.surface};
    --text: ${colors.text};
    --text-secondary: ${colors.textSecondary};
    --text-tertiary: ${colors.textTertiary};
    --border: ${colors.border};
    --border-emphasis: ${colors.borderEmphasis};
    --primary: ${colors.primary};
    --on-primary: ${colors.onPrimary};
    --heading: ${colors.heading};
    --drug: ${colors.drug};
    --danger: ${colors.danger};
    --caution: ${colors.caution};
    --grade: ${colors.grade};
    --selection: ${colors.selection};
    --primary-container: ${colors.primaryContainer};
    --on-primary-container: ${colors.onPrimaryContainer};
    --tertiary-container: ${colors.tertiaryContainer};
    --on-tertiary-container: ${colors.onTertiaryContainer};
}
""".trimIndent()

    // ── Shared table/typography styles (used by both entry points) ──

    private fun sharedTableStyles(): String = """
/* Shared Table & Typography Styles */
.subtitle1 {
    background: var(--primary) !important;
    color: var(--on-primary) !important;
    font-weight: 600;
    text-align: center;
    padding: 10px 12px;
}

.subtitle2, .subtitle2_left {
    background: var(--surface) !important;
    color: var(--heading);
    font-weight: 600;
    padding: 8px 12px;
}

.subtitle2 { text-align: center; }
.subtitle2_left { text-align: left; }

.indent1 { padding-left: 24px !important; font-weight: 500; }
.indent2 { padding-left: 40px !important; }

.divider_bottom { border-bottom: 2px solid var(--border-emphasis) !important; }
.divider_top { border-top: 2px solid var(--border-emphasis) !important; }

.nowrap_whitespace { white-space: nowrap; }
.extra_spacing_top { margin-top: 12px; }
""".trimIndent()

    // ── Reset & Base ─────────────────────────────────────────────────

    fun resetAndBase(): String = """
/* Reset & Base */
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
html { scroll-behavior: smooth; }

body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    font-size: 16px;
    line-height: 1.6;
    color: var(--text);
    background: var(--bg);
    -webkit-font-smoothing: antialiased;
    text-rendering: optimizeLegibility;
}

::selection { background: var(--selection); color: var(--on-primary); }

strong, b { font-weight: 600; }
i, em { font-style: italic; }
sup { font-size: 0.7em; vertical-align: super; }
sub { font-size: 0.7em; vertical-align: sub; }
img { height: auto !important; display: block; }

a:focus-visible, button:focus-visible, input:focus-visible, select:focus-visible {
    outline: 2px solid var(--primary);
    outline-offset: 2px;
}

.visuallyHidden, #formulinkBodyPlaceholder {
    position: absolute !important;
    width: 1px !important;
    height: 1px !important;
    padding: 0 !important;
    margin: -1px !important;
    overflow: hidden !important;
    clip: rect(0, 0, 0, 0) !important;
    white-space: nowrap !important;
    border: 0 !important;
}
.view { display: none; }
""".trimIndent()

    // ── Layout ───────────────────────────────────────────────────────

    fun layoutContainers(): String = """
/* Layout Containers */
.utdArticleSection, #topicContent, #topicContentCalculator {
    max-width: 860px;
    margin: 0 auto;
    padding: 24px 20px 48px;
    overflow-x: auto;
}
#topicWhatsNewContainer { margin: 16px 0; min-height: 4px; }
#topicText { margin-top: 12px; }
#topicText p { margin: 14px 0; text-align: left; }

@media (min-width: 600px) {
    .utdArticleSection, #topicContent, #topicContentCalculator {
        padding: 32px 40px 64px;
    }
}
""".trimIndent()

    // ── Headings ─────────────────────────────────────────────────────

    fun headings(): String = """
/* Headings */
h1 {
    font-size: 1.5rem;
    font-weight: 700;
    line-height: 1.3;
    color: var(--primary);
    border-bottom: 3px solid var(--primary);
    padding-bottom: 8px;
    margin: 32px 0 16px;
    letter-spacing: -0.01em;
}

h1.topic-title {
    font-size: 1.75rem;
    margin-top: 0;
    margin-bottom: 20px;
}

h2.h2 {
    font-size: 1.25rem;
    font-weight: 600;
    line-height: 1.4;
    color: var(--heading);
    padding: 8px 14px;
    margin: 28px 0 14px;
    border-left: 4px solid var(--primary);
    background: var(--surface);
    border-radius: 0 6px 6px 0;
}

h3.h3 {
    font-size: 1.1rem;
    font-weight: 600;
    line-height: 1.4;
    color: var(--heading);
    margin: 24px 0 10px;
}

h4.h4 {
    font-size: 1rem;
    font-weight: 600;
    line-height: 1.4;
    color: var(--text-secondary);
    margin: 20px 0 8px;
}

h5.h5 {
    font-size: 0.9rem;
    font-weight: 600;
    line-height: 1.4;
    color: var(--text-secondary);
    margin: 16px 0 6px;
}

h6.h6 {
    font-size: 0.85rem;
    font-weight: 600;
    line-height: 1.4;
    color: var(--text-tertiary);
    margin: 14px 0 6px;
}
""".trimIndent()

    // ── Links ────────────────────────────────────────────────────────

    fun links(): String = """
/* Links */
a { color: var(--primary); text-decoration: none; transition: color 0.15s; }
a:hover { text-decoration: underline; }

.medical, .medical_review, .abstract_t, .external, .contributor { color: var(--primary); }
.abstract_t { font-weight: 500; }

.drug, .drug_general, .drug_patient, .drug_pediatric {
    color: var(--drug);
    font-weight: 600;
}

.grade {
    display: inline-block;
    padding: 2px 8px;
    border-radius: 4px;
    background: var(--grade);
    color: var(--on-primary);
    font-size: 0.75rem;
    font-weight: 700;
    text-decoration: none;
    vertical-align: middle;
}
.grade:hover { opacity: 0.9; text-decoration: none; }

.graphic, .graphic_table, .graphic_figure {
    display: inline-flex;
    align-items: center;
    padding: 3px 10px;
    margin: 0 3px;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 4px;
    color: var(--primary);
    font-size: 0.8rem;
    font-weight: 600;
    text-decoration: none;
    transition: background 0.15s, color 0.15s, border-color 0.15s;
}

.graphic:hover, .graphic_table:hover, .graphic_figure:hover {
    background: var(--primary);
    color: var(--bg);
    border-color: var(--primary);
    text-decoration: none;
}
""".trimIndent()

    // ── Contributors ─────────────────────────────────────────────────

    fun contributors(): String = """
/* Contributors Block */
#topicContributors {
    margin: 16px 0;
    padding: 16px 20px;
    background: var(--surface);
    border-radius: 8px;
    border: 1px solid var(--border);
}

#topicDisclosures {
    margin: 16px 0;
    padding: 16px 20px;
    background: var(--surface);
    border-radius: 8px;
    border: 1px solid var(--border);
}

#topicContributors dl { margin: 0; }

#topicContributors dt {
    display: block;
    font-size: 0.7rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--text-tertiary);
    margin-top: 12px;
}

#topicContributors dt:first-child { margin-top: 0; }

#topicContributors dd {
    display: inline;
    margin: 0;
    font-size: 0.9rem;
    color: var(--text);
}

#topicContributors a { color: var(--primary); font-weight: 500; }

.disclosureLink, #reviewProcess, #literatureReviewDate {
    display: block;
    margin: 10px 0;
    padding: 12px 16px;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 6px;
    font-size: 0.875rem;
    color: var(--text-secondary);
}

.policy { color: var(--primary); font-weight: 600; }
.emphasis { font-weight: 600; color: var(--text); }

.meta-links-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0;
    margin: 16px 0;
    font-size: 0.9rem;
    font-weight: 600;
}

.meta-links-row a { color: var(--primary); padding: 6px 0; }
.meta-links-row a:hover { text-decoration: underline; }

.meta-separator {
    display: inline-block;
    width: 1px;
    height: 14px;
    background: var(--border);
    margin: 0 14px;
}

.contributor-group-title {
    font-size: 0.7rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--text-tertiary);
    margin: 16px 0 8px;
}

.contributor-group-title:first-child { margin-top: 0; }
.contributor-list { list-style: none; padding: 0; margin: 0; }
.contributor-list li { margin-bottom: 12px; }
.contributor-list li:last-child { margin-bottom: 0; }

.contributor-name {
    font-weight: 600;
    color: var(--heading);
    font-size: 0.9rem;
}

.contributor-associations, .contributor-disclosure {
    font-size: 0.85rem;
    color: var(--text-secondary);
    margin-top: 2px;
    line-height: 1.5;
}
""".trimIndent()

    // ── Bullet Lists ─────────────────────────────────────────────────

    fun bulletLists(): String = """
/* Bullet Lists */
.bulletIndent1, .bulletIndent2, .bulletIndent3 {
    position: relative;
    margin: 12px 0;
}

.bulletIndent1 { padding-left: 28px; }
.bulletIndent2 { padding-left: 52px; }
.bulletIndent3 { padding-left: 76px; }

.glyph {
    position: absolute;
    left: 8px;
    color: var(--primary);
    font-weight: 700;
    font-size: 0.9em;
}

.bulletIndent2 .glyph { left: 32px; color: var(--text-secondary); }
.bulletIndent3 .glyph { left: 56px; color: var(--text-tertiary); }
.utd-adt-pathwys { font-size: 0.9rem; }
""".trimIndent()

    // ── Tables ───────────────────────────────────────────────────────

    fun tables(): String = """
/* Tables */
table {
    width: 100%;
    border-collapse: collapse;
    margin: 20px 0;
    font-size: 0.9rem;
    border: 1px solid var(--border);
}

th, td {
    padding: 12px 14px;
    text-align: left;
    border: 1px solid var(--border);
    vertical-align: top;
}

th {
    background: var(--surface);
    color: var(--heading);
    font-weight: 600;
}

.graphic table, .figure table {
    margin: 16px 0;
    font-size: 0.875rem;
}

.graphic td, .figure td {
    padding: 10px 12px;
    line-height: 1.5;
}

${sharedTableStyles()}

.graphic tbody tr:nth-child(even) td:not(.subtitle1):not(.subtitle2):not(.subtitle2_left) {
    background: var(--surface);
}
""".trimIndent()

    // ── References ───────────────────────────────────────────────────

    fun references(): String = """
/* References */
#references {
    margin-top: 48px;
    padding-top: 24px;
    border-top: 2px solid var(--border);
}

#references h1 {
    font-size: 0.7rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.1em;
    color: var(--text-tertiary);
    margin: 0 0 16px;
}

#reference {
    list-style: none;
    padding: 0;
    margin: 0;
    counter-reset: ref;
}

#reference li {
    counter-increment: ref;
    position: relative;
    padding-left: 32px;
    margin-bottom: 10px;
    color: var(--text-secondary);
    font-size: 0.85rem;
    line-height: 1.5;
}

#reference li::before {
    content: counter(ref) ".";
    position: absolute;
    left: 0;
    width: 24px;
    text-align: right;
    color: var(--text-tertiary);
    font-weight: 500;
}

#topicVersionRevision {
    margin-top: 40px;
    padding-top: 16px;
    text-align: center;
    font-size: 0.78rem;
    color: var(--text-tertiary);
    border-top: 1px solid var(--border);
}

/* Long references with raw URLs */
.breakAll {
    word-break: break-all;
}
""".trimIndent()

    // ── Drug Monograph ───────────────────────────────────────────────

    fun drugMonograph(): String = """
/* Drug Monograph Styles */
.lexiSectionElem {
    margin: 20px 0;
    padding-bottom: 16px;
    border-bottom: 1px solid var(--border);
}

.lexiElementsLeft {
    font-size: 0.9rem;
    color: var(--text-secondary);
}

#drugTitle {
    font-size: 1.5rem;
    font-weight: 700;
    color: var(--drug);
    margin-bottom: 12px;
}

#contributorsSectionWeb {
    font-size: 0.8rem;
    color: var(--text-secondary);
    margin-bottom: 8px;
}

#lexiTitleImg { display: none; }

.lexiAdditionalInfoAndAbbr {
    font-size: 0.85rem;
    color: var(--text-secondary);
    line-height: 1.6;
    padding: 14px 18px;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 6px;
    margin-top: 12px;
}

.drugH1 {
    display: block;
    font-size: 1rem;
    font-weight: 700;
    color: var(--drug);
    margin: 28px 0 12px;
    padding: 10px 0 10px 14px;
    border-left: 4px solid var(--drug);
    background: var(--surface);
    border-radius: 0 6px 6px 0;
}

.drugH1Div { margin: 0 0 14px; }
.drugH1Div ul { margin: 10px 0 0 20px; padding: 0; }
.drugH1Div li { margin: 6px 0; line-height: 1.6; }

.block { margin: 16px 0; }
.block p { margin: 10px 0; }

.collapsible-indication, .collapsible {
    border: 1px solid var(--border);
    border-radius: 6px;
    overflow: hidden;
    margin: 14px 0;
}

.collapsible-indication-title, .collapsible-title {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 12px 16px;
    background: var(--surface);
    font-weight: 600;
    color: var(--heading);
    cursor: pointer;
    transition: background 0.15s;
    user-select: none;
}

.collapsible-indication-title::after,
.collapsible-title::after {
    content: "\25B6";
    font-size: 0.6em;
    color: var(--text-tertiary);
    margin-left: auto;
    transition: transform 0.2s;
}

.collapsible-indication.open .collapsible-indication-title::after,
.collapsible.open .collapsible-title::after {
    transform: rotate(90deg);
}

.collapsible-indication-title:hover { background: var(--bg); }
.collapsible-indication-wrap, .collapsible-wrap { padding: 14px 18px; line-height: 1.6; }

.ref-callout-list { display: inline; white-space: nowrap; }

.ref-callout-list-link {
    display: inline;
    padding: 1px 6px;
    font-size: 0.75rem;
    font-weight: 600;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 3px;
    color: var(--primary);
    text-decoration: none;
    transition: background 0.15s, color 0.15s, border-color 0.15s;
    white-space: nowrap;
}

.ref-callout-list-link:hover {
    background: var(--primary);
    color: var(--bg);
    border-color: var(--primary);
    text-decoration: none;
}

.drugBrandNames { font-size: 0.9rem; }
.drugBrandNames ul { list-style: none; padding: 0; margin: 8px 0; }
.drugBrandNames li { padding: 8px 0; border-bottom: 1px solid var(--border); }
.drugBrandNames li:last-child { border-bottom: none; }

.block.coi .drugH1 {
    color: var(--danger);
    border-left-color: var(--danger);
}

.block.coi .drugH1::before {
    content: "COI";
    display: inline-block;
    font-size: 0.65rem;
    font-weight: 700;
    letter-spacing: 0.05em;
    background: var(--danger);
    color: var(--on-primary);
    padding: 1px 6px;
    border-radius: 3px;
    margin-right: 8px;
    vertical-align: middle;
}

.block.war .drugH1 {
    color: var(--caution);
    border-left-color: var(--caution);
}

.block.war .drugH1::before {
    content: "WARNING";
    display: inline-block;
    font-size: 0.65rem;
    font-weight: 700;
    letter-spacing: 0.05em;
    background: var(--caution);
    color: #1a1a1a;
    padding: 1px 6px;
    border-radius: 3px;
    margin-right: 8px;
    vertical-align: middle;
}
""".trimIndent()

    // ── Patient Education ────────────────────────────────────────────

    fun patientEducation(): String = """
/* Patient Education */
#disclaimerLink {
    margin: 12px 0 8px;
    font-weight: 600;
    font-size: 0.9rem;
}

#disclaimer, #disclaimerContent {
    margin-top: 24px;
    padding: 18px 20px;
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--surface);
    font-size: 0.85rem;
    line-height: 1.6;
    color: var(--text-secondary);
}

#topicRetrievedDate {
    margin-top: 16px;
    font-size: 0.85rem;
    color: var(--text-secondary);
}
""".trimIndent()

    // ── Calculator ───────────────────────────────────────────────────

    fun calculator(): String = """
/* Calculator Styles */
#topicContentCalculator { padding-top: 24px; }
#mc3k { font-family: inherit; color: var(--text); overflow-x: auto; }

.medCalcFontTitleBox {
    display: block;
    font-size: 1.4rem;
    font-weight: 700;
    color: var(--primary);
    padding: 16px 0;
}

.medCalcFontIO { font-size: 1rem; font-weight: 700; color: var(--primary); }
.medCalcFontCCTabBold, .medCalcFontOneBold { font-size: 0.95rem; font-weight: 600; color: var(--heading); }
.medCalcFontOne { font-size: 0.9rem; color: var(--text); }

.medCalcFontSelect {
    padding: 8px 12px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--surface);
    color: var(--text);
    min-width: 240px;
    font-size: 0.9rem;
}

.medCalcFontResultParam { font-weight: 600; color: var(--heading); }

.medCalcResultBox {
    background: var(--surface);
    border: 2px solid var(--primary);
    border-radius: 8px;
    padding: 8px;
}

.medCalcFormuliBox {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 12px 16px;
    overflow-x: auto;
}

.medCalcFormuliBoxWhite {
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 12px 16px;
    width: 100%;
}

.medCalcFontFormuli {
    font-family: "SF Mono", Monaco, Inconsolata, "Fira Mono", monospace;
    font-size: 0.85rem;
    color: var(--text);
    white-space: nowrap;
}

.medCalcFontRef { font-size: 0.85rem; color: var(--text-secondary); }

.medCalcFontTwo {
    font-size: 0.85rem;
    color: var(--text-secondary);
    line-height: 1.6;
}

.medCalcFontTwo .header { font-weight: 700; color: var(--heading); margin-bottom: 8px; }
.medCalcFontTwo .copy { font-size: 0.8rem; margin-top: 12px; }
.medCalcDisclaimerLink { color: var(--primary); }

#mc3k table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
#mc3k td, #mc3k th { padding: 10px 12px; }
#mc3k td[bgcolor], #mc3k tr[bgcolor] td { background-color: var(--surface) !important; }
#mc3k table[border] { border: 1px solid var(--border); }
#mc3k table[border] td, #mc3k table[border] th { border: 1px solid var(--border); }

#calc_main { overflow-x: auto; }
#calc_main table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
#calc_main td, #calc_main th { padding: 10px 12px; border: 1px solid var(--border); }
#calc_main tr:first-child td { background: var(--surface) !important; }

#calc_tables_above_notes { margin-top: 20px; }
#pretextrefs { margin-top: 16px; min-height: 8px; }
#calc_notes ul { margin: 12px 0 12px 24px; }
#calc_notes li { margin: 8px 0; }
#calc_refs ol { margin: 12px 0 12px 24px; }
#calc_refs li { margin: 8px 0; }

#printDisclaimer, #disclaimerCalculator {
    margin-top: 24px;
    padding: 18px 20px;
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--surface);
    font-size: 0.85rem;
    line-height: 1.6;
}

#calc_main input[type="number"] {
    padding: 8px 12px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--bg);
    color: var(--text);
    font-size: 1rem;
    max-width: 100%;
    transition: border-color 0.15s;
}

#calc_main input[type="number"]:focus {
    outline: none;
    border-color: var(--primary);
    box-shadow: 0 0 0 2px ${hexToRgba(colors.primary, 0.2f)};
}

#calc_main input[readonly] {
    background: var(--surface);
    border: 1px dashed var(--border);
    font-weight: 600;
    color: var(--text-secondary);
    cursor: default;
}

#calc_buttons input[type="submit"],
#calc_buttons input[type="button"],
#calc_buttons input[type="reset"] {
    padding: 10px 24px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--surface);
    color: var(--text);
    font-size: 0.9rem;
    font-weight: 600;
    cursor: pointer;
    transition: background 0.15s, color 0.15s, border-color 0.15s;
}

#calc_buttons input[type="submit"]:hover,
#calc_buttons input[type="button"]:hover {
    background: var(--primary);
    color: var(--bg);
    border-color: var(--primary);
}

#calc_buttons input[type="reset"] {
    color: var(--danger);
    border-color: var(--danger);
}

#calc_buttons input[type="reset"]:hover {
    background: var(--danger);
    color: var(--on-primary);
    border-color: var(--danger);
}
""".trimIndent()

    // ── Graphic Viewer (standalone popup) ────────────────────────────

    private fun graphicReset(): String = """
/* Graphic Viewer Reset */
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    font-size: 15px;
    line-height: 1.6;
    color: var(--text);
    background: var(--bg);
    -webkit-font-smoothing: antialiased;
}

a { color: var(--primary); text-decoration: none; }
a:hover { text-decoration: underline; }
""".trimIndent()

    private fun graphicLayout(): String = """
/* Graphic Viewer Layout */
.graphic_view {
    padding: 24px;
    display: flex;
    flex-direction: column;
    align-items: stretch;
}
.figure { margin: 0; text-align: center; width: 100% !important; }

.cntnt img, .graphic_view img {
    max-width: 100% !important;
    height: auto !important;
}

.ttl {
    font-size: 1.125rem;
    font-weight: 600;
    color: var(--heading);
    margin-bottom: 16px;
    padding-bottom: 12px;
    border-bottom: 2px solid var(--primary);
    text-align: center;
    width: 100%;
}

.cntnt {
    margin-bottom: 16px;
    width: 100%;
    max-width: 100%;
    overflow-x: auto;
}

.cntnt img {
    display: block;
    margin: 0 auto;
    border: 1px solid var(--border);
    border-radius: 4px;
}
""".trimIndent()

    private fun graphicTableSpecifics(): String = """
/* Graphic Table Specifics */
.cntnt table {
    width: auto;
    max-width: 100%;
    border-collapse: collapse;
    font-size: 0.875rem;
    border: 1px solid var(--border);
    margin: 0 auto;
}

.cntnt td {
    padding: 10px 12px;
    border: 1px solid var(--border);
    vertical-align: top;
    line-height: 1.5;
}

.subtitle1_single, .subtitle1_left {
    background: var(--primary) !important;
    color: var(--on-primary) !important;
    font-weight: 600;
    text-align: left;
    padding: 10px 12px;
}

.indent2 { color: var(--text-secondary); }

tr.border_bottom_thick td { border-bottom: 2px solid var(--border-emphasis) !important; }
tr.border_top_thick td { border-top: 2px solid var(--border-emphasis) !important; }
.border_right_thick { border-right: 2px solid var(--border-emphasis) !important; }

.highlight_blue_text { background: var(--primary-container) !important; color: var(--on-primary-container) !important; }
.highlight_lght_orange_text { background: var(--tertiary-container) !important; color: var(--on-tertiary-container) !important; }

.subtitle2_left_white { background: var(--surface) !important; color: var(--heading); font-weight: 600; padding: 8px 12px; text-align: left; }

.sublist1_start, .sublist1 {
    padding-left: 24px !important;
    font-weight: 500;
    border-top: none !important;
}
.sublist1_start { border-top: 1px solid var(--border) !important; }

.sublist_other_start, .sublist_other {
    border-top: none !important;
    color: var(--text-secondary);
    font-size: 0.9em;
}
.sublist_other_start {
    border-top: 1px solid var(--border) !important;
    color: var(--text);
    font-size: 1em;
}

.cntnt td ul, .cntnt td ol {
    margin: 4px 0;
    padding-left: 20px;
    list-style-position: outside;
}
.cntnt td li { margin: 4px 0; padding: 0 0 0 4px; line-height: 1.5; }
.cntnt td ul { list-style-type: disc; }
.cntnt td ol { list-style-type: decimal; }
.cntnt td ul.decimal_heading, .cntnt td ol.decimal_heading { list-style-type: decimal; }
.cntnt td ul.none { list-style-type: none; padding-left: 0; }

.cntnt td p { margin: 0; line-height: 1.5; }
.cntnt td p.extra_spacing_top { margin-top: 16px; }
.cntnt td p + ul, .cntnt td p + ol { margin-top: 4px; }

.cntnt td strong { font-weight: 600; color: var(--heading); }
.cntnt td em { font-style: italic; color: var(--text-secondary); }
""".trimIndent()

    private fun graphicNavigation(): String = """
/* Graphic Viewer Navigation */
.graphic_lgnd {
    font-size: 0.875rem;
    color: var(--text);
    margin-top: 12px;
    padding: 12px;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 4px;
    line-height: 1.6;
}

.graphic_footnotes {
    font-size: 0.8125rem;
    color: var(--text-secondary);
    margin-top: 14px;
    padding-top: 10px;
    border-top: 1px solid var(--border);
    line-height: 1.5;
}

.graphic_reference {
    font-size: 0.8rem;
    color: var(--text-tertiary);
    margin-top: 10px;
    line-height: 1.5;
}
.graphic_reference ol { margin: 8px 0 0 20px; padding: 0; }
.graphic_reference li { margin: 4px 0; }

#graphicVersion {
    font-size: 0.75rem;
    color: var(--text-tertiary);
    margin-top: 16px;
    padding-top: 10px;
    border-top: 1px solid var(--border);
}
""".trimIndent()

    // ── Responsive ───────────────────────────────────────────────────

    private fun responsive(): String = """
/* Responsive */
@media (max-width: 480px) {
    .utdArticleSection, #topicContent, #topicContentCalculator {
        padding: 16px 12px 32px;
    }

    h1 { font-size: 1.3rem; }
    h1.topic-title { font-size: 1.5rem; }
    h2.h2 { font-size: 1.1rem; }

    .grade { font-size: 0.7rem; padding: 1px 6px; }
    .graphic, .graphic_table, .graphic_figure { font-size: 0.75rem; padding: 2px 8px; }

    #topicContributors { padding: 12px 14px; }
    .meta-links-row { font-size: 0.85rem; }

    #calc_main input[type="number"] { font-size: 0.9rem; }
}

@media (min-width: 481px) and (max-width: 768px) {
    .utdArticleSection, #topicContent, #topicContentCalculator {
        padding: 24px 20px 48px;
    }
}
""".trimIndent()

    // ── Print ────────────────────────────────────────────────────────

    private fun printStyles(): String = """
/* Print */
@media print {
    body { background: white; color: black; }

    .collapsible-indication-title, .collapsible-title { cursor: default; }
    .collapsible-indication-title::after, .collapsible-title::after { display: none; }

    #calc_buttons { display: none; }

    a[href]::after { content: " (" attr(href) ")"; font-size: 0.75em; color: #666; }
    a[href^="#"]::after { content: ""; }

    .grade { background: #eee; color: black; }
    .graphic, .graphic_table, .graphic_figure { border-color: #ccc; color: black; }

    .block.coi .drugH1, .block.war .drugH1 { break-inside: avoid; }

    .visuallyHidden { position: absolute !important; width: 1px !important; height: 1px !important; clip: rect(0,0,0,0) !important; }
}
""".trimIndent()
}
