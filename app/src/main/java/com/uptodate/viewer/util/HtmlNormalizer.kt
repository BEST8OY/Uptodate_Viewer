package com.uptodate.viewer.util

import com.uptodate.viewer.data.Contributor
import com.uptodate.viewer.data.ContributorGroup
import com.uptodate.viewer.ui.content.OutlineSection

object HtmlNormalizer {

    fun normalizeHeaders(html: String): String {
        if (html.isEmpty()) return ""

        var result = html

        result = Regex(
            """<p([^>]*class="headingAnchor"[^>]*)>(.*?)</p>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).replace(result) { match ->
            convertHeading(match.groupValues[1], match.groupValues[2]) ?: match.value
        }

        result = result.replace(
            Regex("""<div id="topicTitle">(.*?)</div>""", setOf(RegexOption.DOT_MATCHES_ALL)),
            """<h1 class="topic-title">$1</h1>"""
        )

        return result
    }

    fun injectMetaLinks(html: String, contributors: List<ContributorGroup>? = null): String {
        if (html.isEmpty()) return ""

        var result = html
        result = removeReviewProcess(result)
        result = insertMetaLinksRow(result)
        result = insertContributors(result, contributors)
        result = insertDisclosures(result, contributors)
        result = hideLiteratureReviewDate(result)
        result = removeDisclosureLink(result)
        return result
    }

    private fun convertHeading(attrs: String, content: String): String? {
        val level = Regex("""class="(h[1-6])"""").find(content)?.groupValues?.get(1) ?: return null
        val elemId = Regex("""id="([^"]+)""").find(attrs)?.let { """id="${it.groupValues[1]}"""" } ?: ""

        val textMatch = Regex(
            """<span[^>]*class="h[1-6]"[^>]*>(.*?)</span>""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(content)

        return if (textMatch != null) {
            val headerText = textMatch.groupValues[1]
            val remaining = content.substring(textMatch.range.last + 1)
                .replace(Regex("""^<span[^>]*class="headingEndMark"[^>]*>.*?</span>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
                .trim()
            val tag = """<$level $elemId class="$level">$headerText</$level>"""
            if (remaining.isNotEmpty()) "$tag\n<p>$remaining</p>" else tag
        } else {
            val headerText = Regex("""<[^>]+>""").replace(content, "").trim()
            """<$level $elemId class="$level">$headerText</$level>"""
        }
    }

    private fun removeReviewProcess(html: String): String =
        html.replace(Regex("""<div id="reviewProcess">.*?</div>""", setOf(RegexOption.DOT_MATCHES_ALL)), "")

    private fun insertMetaLinksRow(html: String): String {
        val links = buildMetaLinksHtml()
        return when {
            """<h1 class="topic-title">""" in html -> {
                val idx = html.indexOf("</h1>")
                if (idx >= 0) html.substring(0, idx + 5) + links + html.substring(idx + 5) else html
            }
            """<div id="topicTitle">""" in html -> {
                html.replace(Regex("""<div id="topicTitle">.*?</div>""", setOf(RegexOption.DOT_MATCHES_ALL))) { "${it.value}$links" }
            }
            else -> html
        }
    }

    private fun insertContributors(html: String, contributors: List<ContributorGroup>?): String {
        val contribHtml = contributors?.let { buildContributorsHtml(it) } ?: return hideExistingContributors(html)

        return when {
            """<dl id="topicContributors">""" in html ->
                html.replace(Regex("""<dl id="topicContributors">.*?</dl>""", setOf(RegexOption.DOT_MATCHES_ALL)), contribHtml)
            """<div id="topicContributors">""" in html ->
                html.replace(Regex("""<div id="topicContributors">.*?</div>""", setOf(RegexOption.DOT_MATCHES_ALL)), contribHtml)
            else -> {
                val links = buildMetaLinksHtml()
                html.replaceLiteral(links, "$links$contribHtml")
            }
        }
    }

    private fun insertDisclosures(html: String, contributors: List<ContributorGroup>?): String {
        val discHtml = contributors?.let { buildDisclosuresHtml(it) } ?: return html
        val contribHtml = buildContributorsHtml(contributors)

        return if (contribHtml in html) {
            html.replaceLiteral(contribHtml, "$contribHtml$discHtml")
        } else {
            val links = buildMetaLinksHtml()
            html.replaceLiteral(links, "$links$discHtml")
        }
    }

    private fun hideExistingContributors(html: String): String =
        html.replace("""<dl id="topicContributors">""", """<dl id="topicContributors" style="display:none">""")

    private fun hideLiteratureReviewDate(html: String): String =
        html.replace("""<div id="literatureReviewDate">""", """<div id="literatureReviewDate" style="display:none">""")

    private fun removeDisclosureLink(html: String): String =
        html.replace(Regex("""<p class="disclosureLink">.*?</p>""", setOf(RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("""<span class="emphasis">Literature review current through:</span>.*?&#124;&#160;""", setOf(RegexOption.DOT_MATCHES_ALL)), "")

    private fun buildMetaLinksHtml(): String = """
        <div class="meta-links-row">
            <a href="#" onclick="var el = document.getElementById('topicContributors'); if(el) el.style.display = el.style.display === 'none' ? 'block' : 'none'; return false;">Contributors</a>
            <span class="meta-separator"></span>
            <a href="#" onclick="var el = document.getElementById('topicDisclosures'); if(el) el.style.display = el.style.display === 'none' ? 'block' : 'none'; return false;">Disclosures</a>
            <span class="meta-separator"></span>
            <a href="#" onclick="var el = document.getElementById('literatureReviewDate'); if(el) el.style.display = el.style.display === 'none' ? 'block' : 'none'; return false;">Date</a>
        </div>
    """.trimIndent()

    private fun buildContributorsHtml(groups: List<ContributorGroup>): String = buildString {
        append("""<div id="topicContributors" style="display:none">""")
        for (group in groups) {
            if (group.headingTitle.isNotEmpty()) append("""<div class="contributor-group-title">${group.headingTitle}</div>""")
            append("""<ul class="contributor-list">""")
            for (person in group.contributorList) {
                append("""<li><div class="contributor-name">${person.name}</div>""")
                if (person.associations.isNotEmpty()) {
                    append("""<div class="contributor-associations">${person.associations.joinToString("<br>")}</div>""")
                }
                append("</li>")
            }
            append("</ul>")
        }
        append("</div>")
    }

    private fun buildDisclosuresHtml(groups: List<ContributorGroup>): String = buildString {
        append("""<div id="topicDisclosures" style="display:none">""")
        append("""<div class="contributor-group-title">Contributor Disclosures</div>""")
        append("""<ul class="contributor-list">""")
        for (group in groups) {
            for (person in group.contributorList) {
                append("""<li><div class="contributor-name">${person.name}</div>""")
                if (person.disclosure.isNotEmpty()) {
                    append("""<div class="contributor-disclosure">${person.disclosure}</div>""")
                }
                append("</li>")
            }
        }
        append("</ul>")
        append("</div>")
    }

    fun parseOutline(html: String): List<OutlineSection> {
        if (html.isEmpty()) return emptyList()

        val sections = mutableListOf<OutlineSection>()
        val sectionPattern = Regex(
            """<li[^>]*><a[^>]*href="javascript:appAction\(\{[^}]*"section"\s*:\s*"([^"]+)"[^}]*\}\);"[^>]*>(?:<span>)?(.*?)(?:</span>)?</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        var depth = 0
        var i = 0
        val lines = html.replace("\n", " ")

        while (i < lines.length) {
            if (lines.startsWith("<ul", i)) {
                depth++
                i += 3
            } else if (lines.startsWith("</ul>", i)) {
                depth = (depth - 1).coerceAtLeast(0)
                i += 5
            } else {
                val match = sectionPattern.find(lines, i)
                if (match != null && match.range.first == i) {
                    val sectionId = match.groupValues[1]
                    val title = match.groupValues[2].trim()
                    if (title.isNotEmpty()) {
                        sections.add(OutlineSection(id = sectionId, title = title, depth = depth))
                    }
                    i = match.range.last + 1
                } else {
                    i++
                }
            }
        }

        return sections
    }

    private fun String.replaceLiteral(old: String, new: String): String {
        if (old.isEmpty()) return this
        var result = this
        var idx = result.indexOf(old)
        while (idx >= 0) {
            result = result.substring(0, idx) + new + result.substring(idx + old.length)
            idx = result.indexOf(old, idx + new.length)
        }
        return result
    }
}
