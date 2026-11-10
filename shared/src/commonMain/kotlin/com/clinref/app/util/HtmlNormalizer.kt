package com.clinref.app.util

import com.clinref.app.data.Contributor
import com.clinref.app.data.ContributorGroup
import com.clinref.app.ui.content.OutlineSection
import com.clinref.app.ui.content.ReferenceItem
import com.clinref.app.ui.content.SectionType

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

    fun unescapeHtmlEntities(text: String): String = text
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace("&#160;", " ")
        .replace("&ndash;", "–")
        .replace("&mdash;", "—")
        .replace("&hellip;", "…")

    fun normalizeReferences(html: String): Pair<String, Map<Int, ReferenceItem>> {
        if (html.isEmpty()) return html to emptyMap()

        val refPattern = Regex("""<ol[^>]*id=["']reference["'][^>]*>(.*?)</ol>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val match = refPattern.find(html) ?: return html to emptyMap()

        val innerHtml = match.groupValues[1]
        val liPattern = Regex("""<li([^>]*)>(.*?)</li>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val numPattern = Regex("""&quot;number&quot;\s*:\s*&quot;(\d+)&quot;""")
        val idPattern = Regex("""&quot;id&quot;\s*:\s*&quot;([^&]+)&quot;""")

        val references = mutableMapOf<Int, ReferenceItem>()
        var index = 0

        val newInnerHtml = liPattern.replace(innerHtml) { liMatch ->
            index++
            val rawAttrs = liMatch.groupValues[1]
            val content = liMatch.groupValues[2]

            val number = numPattern.find(content)?.groupValues?.get(1)?.toIntOrNull() ?: index
            val abstractId = idPattern.find(content)?.groupValues?.get(1)

            val cleanCitation = unescapeHtmlEntities(
                content.replace(Regex("<[^>]+>"), " ").replace(Regex("""\s+"""), " ").trim()
            )

            val (authors, title, journal) = decomposeCitation(cleanCitation)
            val pubmedUrl = "https://pubmed.ncbi.nlm.nih.gov/?term=" + UrlUtils.urlEncode(cleanCitation)

            val item = ReferenceItem(
                number = number,
                abstractId = abstractId,
                citation = cleanCitation,
                title = title,
                authors = authors,
                journal = journal,
                pubmedUrl = pubmedUrl
            )
            references[number] = item

            """<li id="ref_$number" data-ref="$number"$rawAttrs><a name="ref_$number"></a><a name="ref-$number"></a>$content</li>"""
        }

        val updatedHtml = html.substring(0, match.range.first) +
                html.substring(match.range.first, match.range.last + 1).replace(innerHtml, newInnerHtml) +
                html.substring(match.range.last + 1)

        return updatedHtml to references
    }

    private fun decomposeCitation(citation: String): Triple<String, String, String> {
        val parts = citation.split(Regex("""\.\s+(?=[A-Z])"""))
        return when {
            parts.size >= 3 -> Triple(
                parts[0].trim(),
                parts[1].trim(),
                parts.drop(2).joinToString(". ").trim()
            )
            parts.size == 2 -> Triple(
                parts[0].trim(),
                parts[1].trim(),
                ""
            )
            else -> Triple(
                "",
                citation.trim(),
                ""
            )
        }
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
            <a href="#" onclick="var c=document.getElementById('topicContributors');var d=document.getElementById('topicDisclosures');var t=document.getElementById('literatureReviewDate');var wasOpen=c&&c.style.display!=='none';c&&(c.style.display='none');d&&(d.style.display='none');t&&(t.style.display='none');if(!wasOpen&&c)c.style.display='block';return false;">Contributors</a>
            <span class="meta-separator"></span>
            <a href="#" onclick="var c=document.getElementById('topicContributors');var d=document.getElementById('topicDisclosures');var t=document.getElementById('literatureReviewDate');var wasOpen=d&&d.style.display!=='none';c&&(c.style.display='none');d&&(d.style.display='none');t&&(t.style.display='none');if(!wasOpen&&d)d.style.display='block';return false;">Disclosures</a>
            <span class="meta-separator"></span>
            <a href="#" onclick="var c=document.getElementById('topicContributors');var d=document.getElementById('topicDisclosures');var t=document.getElementById('literatureReviewDate');var wasOpen=t&&t.style.display!=='none';c&&(c.style.display='none');d&&(d.style.display='none');t&&(t.style.display='none');if(!wasOpen&&t)t.style.display='block';return false;">Date</a>
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

        val rawSections = mutableListOf<OutlineSection>()
        val rawSectionIsSr = mutableListOf<Boolean>()
        val liPattern = Regex(
            """<li[^>]*><a[^>]*href="javascript:appAction\(([^)]+)\);"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val sectionPattern = Regex("""&quot;section&quot;\s*:\s*&quot;([^&]+)&quot;""")
        val idPattern = Regex("""&quot;id&quot;\s*:\s*&quot;([^&]+)&quot;""")
        val typePattern = Regex("""&quot;(assetType|type)&quot;\s*:\s*&quot;([^&]+)&quot;""")
        val subtypePattern = Regex("""&quot;subtype&quot;\s*:\s*&quot;([^&]+)&quot;""")
        val labelPattern = Regex("""&quot;label&quot;\s*:\s*&quot;([^&]+)&quot;""")
        val legacyHyphenRegex = Regex("""<span[^>]*class="legacyTopicViewHyphen"[^>]*>.*?</span>""", RegexOption.IGNORE_CASE)
        val stripTagsRegex = Regex("<[^>]+>")

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
                val match = liPattern.find(lines, i)
                if (match != null && match.range.first == i) {
                    val fullLiTag = match.value.substringBefore('>')
                    val isSrButton = fullLiTag.contains("sr-button", ignoreCase = true)
                    val hrefContent = match.groupValues[1]
                    val rawTitle = match.groupValues[2]
                    val title = rawTitle
                        .replace(legacyHyphenRegex, "")
                        .replace(stripTagsRegex, "")
                        .trim()

                    if (title.isNotEmpty()) {
                        val sectionMatch = sectionPattern.find(hrefContent)
                        val idMatch = idPattern.find(hrefContent)
                        val typeMatch = typePattern.find(hrefContent)
                        val assetType = typeMatch?.groupValues?.get(2) ?: ""
                        val graphicSubtype = subtypePattern.find(hrefContent)?.groupValues?.get(1) ?: ""
                        val graphicLabel = labelPattern.find(hrefContent)?.groupValues?.get(1) ?: ""

                        val id = sectionMatch?.groupValues?.get(1)
                            ?: idMatch?.groupValues?.get(1)
                            ?: ""

                        if (id.isNotEmpty()) {
                            val isScrollable = sectionMatch != null
                            val actionJson = if (!isScrollable) {
                                hrefContent
                                    .replace("&quot;", "\"")
                                    .replace("&amp;", "&")
                            } else null
                            val sectionType = when {
                                assetType == "graphic" -> SectionType.GRAPHIC
                                !isScrollable -> SectionType.RELATED
                                else -> SectionType.TOPIC
                            }
                            val displayTitle = if (sectionType == SectionType.GRAPHIC && graphicLabel.isNotEmpty()) {
                                "$graphicLabel - $title"
                            } else {
                                title
                            }
                            rawSections.add(OutlineSection(
                                id = id,
                                title = displayTitle,
                                depth = depth,
                                actionJson = actionJson,
                                sectionType = sectionType,
                                graphicSubtype = if (sectionType == SectionType.GRAPHIC) graphicSubtype else "",
                                graphicLabel = if (sectionType == SectionType.GRAPHIC) graphicLabel else ""
                            ))
                            rawSectionIsSr.add(isSrButton)
                        }
                    }
                    i = match.range.last + 1
                } else {
                    i++
                }
            }
        }

        // A section rendered both in-flow and as an sr-button shortcut keeps the in-flow entry.
        val nonSrSectionIds = rawSections
            .filterIndexed { idx, sec -> !rawSectionIsSr[idx] && sec.sectionType == SectionType.TOPIC }
            .map { it.id }
            .toSet()

        val sections = mutableListOf<OutlineSection>()
        val seenKeys = mutableSetOf<Pair<SectionType, String>>()
        for (idx in rawSections.indices) {
            val sec = rawSections[idx]
            if (rawSectionIsSr[idx] && sec.sectionType == SectionType.TOPIC && sec.id in nonSrSectionIds) {
                continue
            }
            if (seenKeys.add(sec.sectionType to sec.id)) {
                sections.add(sec)
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
