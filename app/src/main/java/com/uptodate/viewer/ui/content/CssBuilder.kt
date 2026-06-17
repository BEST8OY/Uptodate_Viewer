package com.uptodate.viewer.ui.content

class CssBuilder(private val colors: ThemeColors) {

    fun build(vararg sections: String): String {
        return "<style>\n${sections.joinToString("\n")}\n</style>"
    }

    fun resetAndBase(): String = """
/* Reset & Base */
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
html { scroll-behavior: smooth; }

body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    font-size: 16px;
    line-height: 1.7;
    color: ${colors.text};
    background: ${colors.bg};
    -webkit-font-smoothing: antialiased;
    text-rendering: optimizeLegibility;
}

::selection { background: ${colors.selection}; color: ${colors.onPrimary}; }

strong, b { font-weight: 600; }
i, em { font-style: italic; }
sup { font-size: 0.7em; vertical-align: super; }
sub { font-size: 0.7em; vertical-align: sub; }
img { max-width: 100%; height: auto; display: block; }

.visuallyHidden, #formulinkBodyPlaceholder { display: none !important; }
.view { display: none; }
""".trimIndent()

    fun layoutContainers(): String = """
/* Layout Containers */
.utdArticleSection, #topicContent, #topicContentCalculator {
    max-width: 860px;
    margin: 0 auto;
    padding: 32px 40px 64px;
}
#topicWhatsNewContainer { margin: 16px 0; min-height: 4px; }
#topicText { margin-top: 12px; }
#topicText p { margin: 14px 0; text-align: left; }
""".trimIndent()

    fun headings(): String = """
/* Headings */
h1.h1, h1.topic-title {
    font-size: 1.5rem;
    font-weight: 700;
    color: ${colors.primary};
    border-bottom: 3px solid ${colors.primary};
    padding-bottom: 8px;
    margin: 36px 0 16px;
    letter-spacing: -0.01em;
}

h1.topic-title {
    font-size: 2rem;
    margin-top: 0;
    margin-bottom: 20px;
}

h2.h2 {
    font-size: 1.2rem;
    font-weight: 600;
    color: ${colors.heading};
    padding: 8px 14px;
    margin: 32px 0 14px;
    border-left: 4px solid ${colors.primary};
    background: ${colors.surface};
    border-radius: 0 6px 6px 0;
}

h3.h3 {
    font-size: 1.05rem;
    font-weight: 600;
    color: ${colors.heading};
    margin: 24px 0 12px;
}

h4.h4 {
    font-size: 0.95rem;
    font-weight: 600;
    color: ${colors.textSecondary};
    font-style: italic;
    margin: 20px 0 10px;
}

h5.h5 {
    font-size: 0.9rem;
    font-weight: 600;
    color: ${colors.textSecondary};
    margin: 16px 0 8px;
}

h6.h6 {
    font-size: 0.85rem;
    font-weight: 600;
    color: ${colors.textTertiary};
    margin: 14px 0 6px;
}

#topicTitle {
    font-size: 2rem;
    font-weight: 700;
    color: ${colors.primary};
    line-height: 1.25;
    margin-bottom: 20px;
    letter-spacing: -0.02em;
}
""".trimIndent()

    fun links(): String = """
/* Links */
a { color: ${colors.primary}; text-decoration: none; transition: color 0.15s; }
a:hover { text-decoration: underline; }

.medical, .medical_review, .abstract_t, .external, .contributor { color: ${colors.primary}; }
.abstract_t { font-weight: 500; }

.drug, .drug_general, .drug_patient, .drug_pediatric {
    color: ${colors.drug};
    font-weight: 600;
}

.grade {
    display: inline-block;
    padding: 2px 8px;
    border-radius: 4px;
    background: ${colors.grade};
    color: ${colors.onPrimary};
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
    background: ${colors.surface};
    border: 1px solid ${colors.border};
    border-radius: 4px;
    color: ${colors.primary};
    font-size: 0.8rem;
    font-weight: 600;
    text-decoration: none;
    transition: all 0.15s;
}

.graphic:hover, .graphic_table:hover, .graphic_figure:hover {
    background: ${colors.primary};
    color: ${colors.bg};
    border-color: ${colors.primary};
    text-decoration: none;
}
""".trimIndent()

    fun contributors(): String = """
/* Contributors Block */
#topicContributors {
    margin: 16px 0;
    padding: 16px 20px;
    background: ${colors.surface};
    border-radius: 8px;
    border: 1px solid ${colors.border};
}

#topicDisclosures {
    margin: 16px 0;
    padding: 16px 20px;
    background: ${colors.surface};
    border-radius: 8px;
    border: 1px solid ${colors.border};
}

#topicContributors dl { margin: 0; }

#topicContributors dt {
    display: block;
    font-size: 0.7rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: ${colors.textTertiary};
    margin-top: 12px;
}

#topicContributors dt:first-child { margin-top: 0; }

#topicContributors dd {
    display: inline;
    margin: 0;
    font-size: 0.9rem;
    color: ${colors.text};
}

#topicContributors dd::after { content: ", "; color: ${colors.textTertiary}; }
#topicContributors dd:last-of-type::after { content: ""; }
#topicContributors a { color: ${colors.primary}; font-weight: 500; }

.disclosureLink, #reviewProcess, #literatureReviewDate {
    display: block;
    margin: 10px 0;
    padding: 12px 16px;
    background: ${colors.surface};
    border: 1px solid ${colors.border};
    border-radius: 6px;
    font-size: 0.875rem;
    color: ${colors.textSecondary};
}

.policy { color: ${colors.primary}; font-weight: 600; }
.emphasis { font-weight: 600; color: ${colors.text}; }

.meta-links-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0;
    margin: 16px 0;
    font-size: 0.9rem;
    font-weight: 600;
}

.meta-links-row a { color: ${colors.primary}; padding: 6px 0; }
.meta-links-row a:hover { text-decoration: underline; }

.meta-separator {
    display: inline-block;
    width: 1px;
    height: 14px;
    background: ${colors.border};
    margin: 0 14px;
}

.contributor-group-title {
    font-size: 0.7rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: ${colors.textTertiary};
    margin: 16px 0 8px;
}

.contributor-group-title:first-child { margin-top: 0; }
.contributor-list { list-style: none; padding: 0; margin: 0; }
.contributor-list li { margin-bottom: 12px; }
.contributor-list li:last-child { margin-bottom: 0; }

.contributor-name {
    font-weight: 600;
    color: ${colors.heading};
    font-size: 0.9rem;
}

.contributor-associations, .contributor-disclosure {
    font-size: 0.85rem;
    color: ${colors.textSecondary};
    margin-top: 2px;
    line-height: 1.5;
}
""".trimIndent()

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
    color: ${colors.primary};
    font-weight: 700;
    font-size: 0.9em;
}

.bulletIndent2 .glyph { left: 32px; color: ${colors.textSecondary}; }
.bulletIndent3 .glyph { left: 56px; color: ${colors.textTertiary}; }
.utd-adt-pathwys { font-size: 0.9rem; }
""".trimIndent()

    fun tables(): String = """
/* Tables */
table {
    width: 100%;
    border-collapse: collapse;
    margin: 20px 0;
    font-size: 0.9rem;
    border: 1px solid ${colors.border};
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
}

th, td {
    padding: 12px 14px;
    text-align: left;
    border: 1px solid ${colors.border};
    vertical-align: top;
}

th {
    background: ${colors.surface};
    color: ${colors.heading};
    font-weight: 600;
}

.graphic table, .figure table {
    width: 100%;
    border-collapse: collapse;
    margin: 16px 0;
    font-size: 0.875rem;
    border: 1px solid ${colors.border};
}

.graphic td, .figure td {
    padding: 10px 12px;
    border: 1px solid ${colors.border};
    vertical-align: top;
    line-height: 1.5;
}

.subtitle1 {
    background: ${colors.primary} !important;
    color: ${colors.onPrimary} !important;
    font-weight: 600;
    text-align: center;
    padding: 10px 12px;
}

.subtitle2, .subtitle2_left {
    background: ${colors.surface} !important;
    color: ${colors.heading};
    font-weight: 600;
    padding: 8px 12px;
}

.subtitle2 { text-align: center; }
.subtitle2_left { text-align: left; }

.indent1 { padding-left: 24px !important; font-weight: 500; }
.indent2 { padding-left: 40px !important; }

.divider_bottom { border-bottom: 2px solid ${colors.borderEmphasis} !important; }
.divider_top { border-top: 2px solid ${colors.borderEmphasis} !important; }

.nowrap_whitespace { white-space: nowrap; }
.extra_spacing_top { margin-top: 12px; }

.graphic tbody tr:nth-child(even) td:not(.subtitle1):not(.subtitle2):not(.subtitle2_left) {
    background: ${colors.surface};
}
""".trimIndent()

    fun references(): String = """
/* References */
#references {
    margin-top: 48px;
    padding-top: 24px;
    border-top: 2px solid ${colors.border};
}

#references h1 {
    font-size: 0.7rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.1em;
    color: ${colors.textTertiary};
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
    color: ${colors.textSecondary};
    font-size: 0.85rem;
    line-height: 1.55;
}

#reference li::before {
    content: counter(ref) ".";
    position: absolute;
    left: 0;
    width: 24px;
    text-align: right;
    color: ${colors.textTertiary};
    font-weight: 500;
}

#topicVersionRevision {
    margin-top: 40px;
    padding-top: 16px;
    text-align: center;
    font-size: 0.78rem;
    color: ${colors.textTertiary};
    border-top: 1px solid ${colors.border};
}
""".trimIndent()

    fun drugMonograph(): String = """
/* Drug Monograph Styles */
.lexiSectionElem {
    margin: 20px 0;
    padding-bottom: 16px;
    border-bottom: 1px solid ${colors.border};
}

.lexiElementsLeft {
    font-size: 0.9rem;
    color: ${colors.textSecondary};
}

#drugTitle {
    font-size: 1.6rem;
    font-weight: 700;
    color: ${colors.drug};
    margin-bottom: 12px;
}

#contributorsSectionWeb {
    font-size: 0.8rem;
    color: ${colors.textSecondary};
    margin-bottom: 8px;
}

#lexiTitleImg { display: none; }

.lexiAdditionalInfoAndAbbr {
    font-size: 0.85rem;
    color: ${colors.textSecondary};
    line-height: 1.6;
    padding: 14px 18px;
    background: ${colors.surface};
    border: 1px solid ${colors.border};
    border-radius: 6px;
    margin-top: 12px;
}

.drugH1 {
    display: block;
    font-size: 1.05rem;
    font-weight: 700;
    color: ${colors.drug};
    margin: 28px 0 12px;
    padding: 10px 0 10px 14px;
    border-left: 4px solid ${colors.drug};
    background: ${colors.surface};
    border-radius: 0 6px 6px 0;
}

.drugH1Div { margin: 0 0 14px; }
.drugH1Div ul { margin: 10px 0 0 20px; padding: 0; }
.drugH1Div li { margin: 6px 0; line-height: 1.6; }

.block { margin: 16px 0; }
.block p { margin: 10px 0; }

.collapsible-indication, .collapsible {
    border: 1px solid ${colors.border};
    border-radius: 6px;
    overflow: hidden;
    margin: 14px 0;
}

.collapsible-indication-title, .collapsible-title {
    display: block;
    padding: 12px 16px;
    background: ${colors.surface};
    font-weight: 600;
    color: ${colors.heading};
    cursor: pointer;
    transition: background 0.15s;
}

.collapsible-indication-title:hover { background: ${colors.surface}; }
.collapsible-indication-wrap, .collapsible-wrap { padding: 14px 18px; line-height: 1.65; }

.ref-callout-list { display: inline; white-space: nowrap; }

.ref-callout-list-link {
    display: inline;
    padding: 1px 6px;
    font-size: 0.75rem;
    font-weight: 600;
    background: ${colors.surface};
    border: 1px solid ${colors.border};
    border-radius: 3px;
    color: ${colors.primary};
    text-decoration: none;
    transition: all 0.15s;
    white-space: nowrap;
}

.ref-callout-list-link:hover {
    background: ${colors.primary};
    color: ${colors.bg};
    border-color: ${colors.primary};
    text-decoration: none;
}

.drugBrandNames { font-size: 0.9rem; }
.drugBrandNames ul { list-style: none; padding: 0; margin: 8px 0; }
.drugBrandNames li { padding: 8px 0; border-bottom: 1px solid ${colors.border}; }
.drugBrandNames li:last-child { border-bottom: none; }

.block.coi .drugH1 {
    color: ${colors.danger};
    border-left-color: ${colors.danger};
}

.block.war .drugH1 {
    color: ${colors.caution};
    border-left-color: ${colors.caution};
}
""".trimIndent()

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
    border: 1px solid ${colors.border};
    border-radius: 8px;
    background: ${colors.surface};
    font-size: 0.85rem;
    line-height: 1.65;
    color: ${colors.textSecondary};
}

#topicRetrievedDate {
    margin-top: 16px;
    font-size: 0.85rem;
    color: ${colors.textSecondary};
}
""".trimIndent()

    fun calculator(): String = """
/* Calculator Styles */
#topicContentCalculator { padding-top: 24px; }
#mc3k { font-family: inherit; color: ${colors.text}; overflow-x: auto; -webkit-overflow-scrolling: touch; }

.medCalcFontTitleBox {
    display: block;
    font-size: 1.4rem;
    font-weight: 700;
    color: ${colors.primary};
    padding: 16px 0;
}

.medCalcFontIO { font-size: 1rem; font-weight: 700; color: ${colors.primary}; }
.medCalcFontCCTabBold, .medCalcFontOneBold { font-size: 0.95rem; font-weight: 600; color: ${colors.heading}; }
.medCalcFontOne { font-size: 0.9rem; color: ${colors.text}; }

.medCalcFontSelect {
    padding: 8px 12px;
    border: 1px solid ${colors.border};
    border-radius: 6px;
    background: ${colors.surface};
    color: ${colors.text};
    min-width: 240px;
    font-size: 0.9rem;
}

.medCalcFontResultParam { font-weight: 600; color: ${colors.heading}; }

.medCalcResultBox {
    background: ${colors.surface};
    border: 2px solid ${colors.primary};
    border-radius: 8px;
    padding: 8px;
}

.medCalcFormuliBox {
    background: ${colors.surface};
    border: 1px solid ${colors.border};
    border-radius: 6px;
    padding: 12px 16px;
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
}

.medCalcFormuliBoxWhite {
    background: ${colors.bg};
    border: 1px solid ${colors.border};
    border-radius: 6px;
    padding: 12px 16px;
    width: 100%;
}

.medCalcFontFormuli {
    font-family: "SF Mono", Monaco, Inconsolata, "Fira Mono", monospace;
    font-size: 0.85rem;
    color: ${colors.text};
    white-space: nowrap;
}

.medCalcFontRef { font-size: 0.85rem; color: ${colors.textSecondary}; }

.medCalcFontTwo {
    font-size: 0.85rem;
    color: ${colors.textSecondary};
    line-height: 1.6;
}

.medCalcFontTwo .header { font-weight: 700; color: ${colors.heading}; margin-bottom: 8px; }
.medCalcFontTwo .copy { font-size: 0.8rem; margin-top: 12px; }
.medCalcDisclaimerLink { color: ${colors.primary}; }

#mc3k table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
#mc3k td, #mc3k th { padding: 10px 12px; }
#mc3k td[bgcolor], #mc3k tr[bgcolor] td { background-color: ${colors.surface} !important; }
#mc3k table[border] { border: 1px solid ${colors.border}; }
#mc3k table[border] td, #mc3k table[border] th { border: 1px solid ${colors.border}; }

#calc_main { overflow-x: auto; -webkit-overflow-scrolling: touch; }
#calc_main table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
#calc_main td, #calc_main th { padding: 10px 12px; border: 1px solid ${colors.border}; }
#calc_main tr:first-child td { background: ${colors.surface} !important; }

#calc_tables_above_notes { margin-top: 20px; }
#pretextrefs { margin-top: 16px; min-height: 8px; }
#calc_notes ul { margin: 12px 0 12px 24px; }
#calc_notes li { margin: 8px 0; }
#calc_refs ol { margin: 12px 0 12px 24px; }
#calc_refs li { margin: 8px 0; }

#printDisclaimer, #disclaimerCalculator {
    margin-top: 24px;
    padding: 18px 20px;
    border: 1px solid ${colors.border};
    border-radius: 8px;
    background: ${colors.surface};
    font-size: 0.85rem;
    line-height: 1.6;
}

#calc_main input[type="number"] {
    padding: 8px 12px;
    border: 1px solid ${colors.border};
    border-radius: 6px;
    background: ${colors.bg};
    color: ${colors.text};
    font-size: 1rem;
    max-width: 100%;
    transition: border-color 0.15s;
}

#calc_main input[type="number"]:focus {
    outline: none;
    border-color: ${colors.primary};
    box-shadow: 0 0 0 2px ${colors.primary}33;
}

#calc_main input[readonly] { background: ${colors.surface}; font-weight: 600; }

#calc_buttons input[type="submit"],
#calc_buttons input[type="button"],
#calc_buttons input[type="reset"] {
    padding: 10px 24px;
    border: 1px solid ${colors.border};
    border-radius: 6px;
    background: ${colors.surface};
    color: ${colors.text};
    font-size: 0.9rem;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.15s;
}

#calc_buttons input[type="submit"]:hover,
#calc_buttons input[type="button"]:hover {
    background: ${colors.primary};
    color: ${colors.bg};
    border-color: ${colors.primary};
}

#calc_buttons input[type="reset"]:hover {
    background: ${colors.surface};
    color: ${colors.danger};
    border-color: ${colors.danger};
}
""".trimIndent()

    fun outlineSidebar(): String = """
/* Outline Sidebar Mode */
body.outline-mode {
    background: ${colors.surface};
    padding: 16px 14px;
    font-size: 14px;
}

body.outline-mode .topic-outline { padding: 0; }

body.outline-mode h2 {
    font-size: 0.7rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: ${colors.textTertiary};
    margin: 0 0 12px;
    padding-bottom: 10px;
    border-bottom: 2px solid ${colors.border};
}

body.outline-mode ul { list-style: none; padding: 0; margin: 0; }

body.outline-mode a {
    display: block;
    padding: 8px 12px;
    color: ${colors.text};
    border-radius: 5px;
    border-left: 3px solid transparent;
    transition: all 0.12s ease;
    font-size: 0.85rem;
}

body.outline-mode a:hover {
    background: ${colors.bg};
    border-left-color: ${colors.primary};
    color: ${colors.primary};
    text-decoration: none;
}

body.outline-mode ul ul {
    margin-left: 12px;
    padding-left: 12px;
    border-left: 1px solid ${colors.border};
}

body.outline-mode ul ul a {
    font-size: 0.8rem;
    color: ${colors.textSecondary};
    padding: 6px 10px;
}
""".trimIndent()

    fun graphicViewer(): String = """
/* Graphic Viewer Styles */
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    font-size: 15px;
    line-height: 1.65;
    color: ${colors.text};
    background: ${colors.bg};
    -webkit-font-smoothing: antialiased;
}

.graphic_view {
    padding: 24px;
    display: flex;
    flex-direction: column;
    align-items: stretch;
}
.figure { margin: 0; text-align: center; width: 100% !important; }

img {
    max-width: 100%;
    height: auto;
}

.ttl {
    font-size: 1.125rem;
    font-weight: 600;
    color: ${colors.heading};
    margin-bottom: 16px;
    padding-bottom: 12px;
    border-bottom: 2px solid ${colors.primary};
    text-align: center;
    width: 100%;
}

.cntnt {
    margin-bottom: 16px;
    width: 100%;
    max-width: 100%;
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
}

.cntnt img {
    display: block;
    margin: 0 auto;
    max-width: 100% !important;
    height: auto !important;
    border: 1px solid ${colors.border};
    border-radius: 4px;
}

/* Graphic Tables */
.cntnt table {
    width: auto;
    max-width: 100%;
    border-collapse: collapse;
    font-size: 0.875rem;
    border: 1px solid ${colors.border};
    margin: 0 auto;
}

.cntnt td {
    padding: 10px 12px;
    border: 1px solid ${colors.border};
    vertical-align: top;
    line-height: 1.5;
}

.subtitle1 {
    background: ${colors.primary} !important;
    color: ${colors.onPrimary} !important;
    font-weight: 600;
    text-align: center;
    padding: 10px 12px;
}

.subtitle1_single, .subtitle1_left {
    background: ${colors.primary} !important;
    color: ${colors.onPrimary} !important;
    font-weight: 600;
    text-align: left;
    padding: 10px 12px;
}

.subtitle2, .subtitle2_left {
    background: ${colors.surface} !important;
    color: ${colors.heading};
    font-weight: 600;
    padding: 8px 12px;
}

.subtitle2 { text-align: center; }
.subtitle2_left { text-align: left; }

.indent1 { padding-left: 24px !important; font-weight: 500; }
.indent2 { padding-left: 40px !important; color: ${colors.textSecondary}; }

tr.border_bottom_thick td { border-bottom: 2px solid ${colors.borderEmphasis} !important; }
tr.border_top_thick td { border-top: 2px solid ${colors.borderEmphasis} !important; }
.border_right_thick { border-right: 2px solid ${colors.borderEmphasis} !important; }

.highlight_blue_text { background: #e3f2fd !important; }
.highlight_lght_orange_text { background: #fff3e0 !important; }

.subtitle2_left_white { background: #fff !important; color: ${colors.heading}; font-weight: 600; padding: 8px 12px; text-align: left; }

.sublist1_start, .sublist1 {
    padding-left: 24px !important;
    font-weight: 500;
    border-top: none !important;
}
.sublist1_start { border-top: 1px solid ${colors.border} !important; }

.sublist_other_start, .sublist_other {
    border-top: none !important;
    color: ${colors.textSecondary};
    font-size: 0.9em;
}
.sublist_other_start {
    border-top: 1px solid ${colors.border} !important;
    color: ${colors.text};
    font-size: 1em;
}

.divider_bottom { border-bottom: 2px solid ${colors.borderEmphasis} !important; }
.divider_top { border-top: 2px solid ${colors.borderEmphasis} !important; }

.nowrap_whitespace { white-space: nowrap; }
.extra_spacing_top { margin-top: 12px; }

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

.cntnt td strong { font-weight: 600; color: ${colors.heading}; }
.cntnt td em { font-style: italic; color: ${colors.textSecondary}; }

/* Legend & footnotes */
.graphic_lgnd {
    font-size: 0.875rem;
    color: ${colors.text};
    margin-top: 12px;
    padding: 12px;
    background: ${colors.surface};
    border: 1px solid ${colors.border};
    border-radius: 4px;
    line-height: 1.6;
}

.graphic_footnotes {
    font-size: 0.8125rem;
    color: ${colors.textSecondary};
    margin-top: 14px;
    padding-top: 10px;
    border-top: 1px solid ${colors.border};
    line-height: 1.5;
}

.graphic_reference {
    font-size: 0.8rem;
    color: ${colors.textTertiary};
    margin-top: 10px;
    line-height: 1.5;
}
.graphic_reference ol { margin: 8px 0 0 20px; padding: 0; }
.graphic_reference li { margin: 4px 0; }

#graphicVersion {
    font-size: 0.75rem;
    color: ${colors.textTertiary};
    margin-top: 16px;
    padding-top: 10px;
    border-top: 1px solid ${colors.border};
}

a { color: ${colors.primary}; text-decoration: none; }
a:hover { text-decoration: underline; }
""".trimIndent()
}
