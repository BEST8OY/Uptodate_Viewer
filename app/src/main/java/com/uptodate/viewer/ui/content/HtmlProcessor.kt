package com.uptodate.viewer.ui.content

import com.uptodate.viewer.domain.model.ContributorGroup

object HtmlProcessor {

    fun normalizeHeaders(html: String): String {
        if (html.isEmpty()) return ""

        // Convert <p class="headingAnchor">...<span class="hX">TEXT</span>...</p> -> <hX id="...">TEXT</hX>
        val headingPattern = Regex(
            """<p([^>]*class="headingAnchor"[^>]*)>(.*?)</p>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        var result = html.replace(headingPattern) { match ->
            val attrs = match.groupValues[1]
            val content = match.groupValues[2]

            val hMatch = Regex("""class="(h[1-6])"?""").find(content) ?: return@replace match.value
            val level = hMatch.groupValues[1]

            val idMatch = Regex("""id="([^"]+)"""").find(attrs)
            val elemId = if (idMatch != null) """id="${idMatch.groupValues[1]}"""" else ""

            val textMatch = Regex("""<span[^>]*class="h[1-6]"[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL).find(content)
            if (textMatch != null) {
                val headerText = textMatch.groupValues[1]
                val remaining = content.substring(textMatch.range.last + 1).trim()
                val cleanedRemaining = remaining.replace(Regex("""^<span[^>]*class="headingEndMark"[^>]*>.*?</span>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "").trim()
                val headerTag = "<$level $elemId class=\"$level\">$headerText</$level>"
                if (cleanedRemaining.isNotEmpty()) "$headerTag\n<p>$cleanedRemaining</p>" else headerTag
            } else {
                val headerText = content.replace(Regex("<[^>]+>"), "").trim()
                "<$level $elemId class=\"$level\">$headerText</$level>"
            }
        }

        // Convert <div id="topicTitle">TITLE</div> -> <h1 class="topic-title">TITLE</h1>
        result = result.replace(
            Regex("""<div id="topicTitle">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL),
            """<h1 class="topic-title">$1</h1>"""
        )

        return result
    }

    fun injectMetaLinks(html: String, contributors: List<ContributorGroup>?): String {
        if (html.isEmpty()) return ""

        var result = html

        // Remove reviewProcess div
        result = result.replace(
            Regex("""<div id="reviewProcess">.*?</div>""", RegexOption.DOT_MATCHES_ALL),
            ""
        )

        // Build contributors/disclosures HTML
        val cHtml = buildContributorsHtml(contributors)
        val dHtml = buildDisclosuresHtml(contributors)

        // Build meta links row
        val metaLinks = buildString {
            appendLine("""<div class="meta-links-row">""")
            appendLine("""    <a href="#" onclick="var el = document.getElementById('topicContributors'); if(el) el.style.display = el.style.display === 'none' ? 'block' : 'none'; return false;">Contributors</a>""")
            appendLine("""    <span class="meta-separator"></span>""")
            appendLine("""    <a href="#" onclick="var el = document.getElementById('topicDisclosures'); if(el) el.style.display = el.style.display === 'none' ? 'block' : 'none'; return false;">Disclosures</a>""")
            appendLine("""    <span class="meta-separator"></span>""")
            appendLine("""    <a href="#" onclick="var el = document.getElementById('literatureReviewDate'); if(el) el.style.display = el.style.display === 'none' ? 'block' : 'none'; return false;">Date</a>""")
            appendLine("</div>")
        }

        // Insert after topic title
        val h1Match = Regex("""(<h1 class="topic-title">.*?</h1>)""", RegexOption.DOT_MATCHES_ALL).find(result)
        if (h1Match != null) {
            result = result.replace(h1Match.groupValues[1], h1Match.groupValues[1] + metaLinks)
        }

        // Replace existing contributors/disclosures or append
        if (cHtml.isNotEmpty()) {
            result = result.replace(Regex("""<dl id="topicContributors">.*?</dl>""", RegexOption.DOT_MATCHES_ALL), cHtml)
            if (!result.contains("id=\"topicContributors\"")) {
                result = result.replace(Regex("""<div id="topicContributors" style="display:none">.*?</div>""", RegexOption.DOT_MATCHES_ALL), cHtml)
            }
        }

        if (dHtml.isNotEmpty()) {
            if (!result.contains("id=\"topicDisclosures\"")) {
                result = result.replace(metaLinks, metaLinks + dHtml)
            }
        }

        // Hide date
        result = result.replace(
            Regex("""<div id="literatureReviewDate">"""),
            """<div id="literatureReviewDate" style="display:none">"""
        )

        // Clean literature review date text
        result = result.replace(
            Regex("""<span class="emphasis">Literature review current through:</span>.*?&#124;&#160;""", RegexOption.DOT_MATCHES_ALL),
            ""
        )

        // Remove disclosure link
        result = result.replace(Regex("""<p class="disclosureLink">.*?</p>""", RegexOption.DOT_MATCHES_ALL), "")

        return result
    }

    private fun buildContributorsHtml(contributors: List<ContributorGroup>?): String {
        if (contributors.isNullOrEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("""<div id="topicContributors" style="display:none">""")
        for (group in contributors) {
            val title = group.headingTitle ?: ""
            if (title.isNotEmpty()) {
                sb.appendLine("""<div class="contributor-group-title">${escapeHtml(title)}</div>""")
            }
            sb.appendLine("""<ul class="contributor-list">""")
            for (person in group.contributors) {
                val name = escapeHtml(person.name ?: "")
                sb.appendLine("""<li><div class="contributor-name">$name</div>""")
                val associations = person.associations?.filterNotNull()
                if (!associations.isNullOrEmpty()) {
                    sb.appendLine("""<div class="contributor-associations">${associations.joinToString("<br>")}</div>""")
                }
                sb.appendLine("</li>")
            }
            sb.appendLine("</ul>")
        }
        sb.appendLine("</div>")
        return sb.toString()
    }

    private fun buildDisclosuresHtml(contributors: List<ContributorGroup>?): String {
        if (contributors.isNullOrEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("""<div id="topicDisclosures" style="display:none">""")
        sb.appendLine("""<div class="contributor-group-title">Contributor Disclosures</div>""")
        sb.appendLine("""<ul class="contributor-list">""")
        for (group in contributors) {
            for (person in group.contributors) {
                val name = escapeHtml(person.name ?: "")
                val disclosure = person.disclosure ?: ""
                sb.appendLine("""<li><div class="contributor-name">$name</div>""")
                if (disclosure.isNotEmpty()) {
                    sb.appendLine("""<div class="contributor-disclosure">${escapeHtml(disclosure)}</div>""")
                }
                sb.appendLine("</li>")
            }
        }
        sb.appendLine("</ul>")
        sb.appendLine("</div>")
        return sb.toString()
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    /**
     * Extract graphics pagination JSON actions from HTML and replace
     * javascript:appAction(...) with appaction:// URLs.
     * Returns (processedHtml, actionsMap)
     */
    fun extractActions(html: String): Pair<String, MutableMap<String, String>> {
        val actions = mutableMapOf<String, String>()
        var counter = 0
        val result = html.replace(
            Regex("""href="javascript:appAction\((.*?)\);?""""),
            { match ->
                val jsCall = match.groupValues[1]
                val actionId = "action_$counter"
                counter++
                actions[actionId] = jsCall
                """href="appaction://$actionId" """
            }
        )
        return Pair(result, actions)
    }
}
