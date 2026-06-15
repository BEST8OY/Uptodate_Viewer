package com.uptodate.viewer.util

object HtmlNormalizer {

    fun normalizeHeaders(html: String): String {
        if (html.isEmpty()) return ""

        var result = html

        val pHeadingPattern = Regex(
            """<p([^>]*class="headingAnchor"[^>]*)>(.*?)</p>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        result = pHeadingPattern.replace(result) { match ->
            val attrs = match.groupValues[1]
            val content = match.groupValues[2]

            val hMatch = Regex("""class="(h[1-6])"""").find(content) ?: return@replace match.value
            val level = hMatch.groupValues[1]

            val idMatch = Regex("""id="([^"]+)""").find(attrs)
            val elemId = if (idMatch != null) """id="${idMatch.groupValues[1]}"""" else ""

            val textMatch = Regex(
                """<span[^>]*class="h[1-6]"[^>]*>(.*?)</span>""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            ).find(content)

            if (textMatch != null) {
                val headerText = textMatch.groupValues[1]
                val remainingContent = content.substring(textMatch.range.last + 1)
                    .replace(Regex("""^<span[^>]*class="headingEndMark"[^>]*>.*?</span>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
                    .trim()

                val headerTag = """<$level $elemId class="$level">$headerText</$level>"""

                if (remainingContent.isNotEmpty()) {
                    "$headerTag\n<p>$remainingContent</p>"
                } else {
                    headerTag
                }
            } else {
                val headerText = Regex("""<[^>]+>""").replace(content, "").trim()
                """<$level $elemId class="$level">$headerText</$level>"""
            }
        }

        result = result.replace(
            Regex("""<div id="topicTitle">(.*?)</div>""", setOf(RegexOption.DOT_MATCHES_ALL)),
            """<h1 class="topic-title">$1</h1>"""
        )

        return result
    }

    fun injectMetaLinks(html: String, contributors: List<Map<String, Any?>>? = null): String {
        if (html.isEmpty()) return ""

        var result = html

        result = result.replace(
            Regex("""<div id="reviewProcess">.*?</div>""", setOf(RegexOption.DOT_MATCHES_ALL)),
            ""
        )

        var contributorsHtml = ""
        var disclosuresHtml = ""

        if (contributors != null) {
            val contribBuilder = StringBuilder()
            contribBuilder.append("""<div id="topicContributors" style="display:none">""")

            for (group in contributors) {
                val title = group["headingTitle"] as? String ?: ""
                if (title.isNotEmpty()) {
                    contribBuilder.append("""<div class="contributor-group-title">$title</div>""")
                }

                @Suppress("UNCHECKED_CAST")
                val contributorList = group["contributorList"] as? List<Map<String, Any?>> ?: emptyList()
                contribBuilder.append("""<ul class="contributor-list">""")
                for (person in contributorList) {
                    val name = person["name"] as? String ?: ""
                    @Suppress("UNCHECKED_CAST")
                    val associations = person["associations"] as? List<String> ?: emptyList()

                    contribBuilder.append("""<li><div class="contributor-name">$name</div>""")
                    if (associations.isNotEmpty()) {
                        contribBuilder.append("""<div class="contributor-associations">${associations.joinToString("<br>")}</div>""")
                    }
                    contribBuilder.append("</li>")
                }
                contribBuilder.append("</ul>")
            }
            contribBuilder.append("</div>")
            contributorsHtml = contribBuilder.toString()

            val discBuilder = StringBuilder()
            discBuilder.append("""<div id="topicDisclosures" style="display:none">""")
            discBuilder.append("""<div class="contributor-group-title">Contributor Disclosures</div>""")
            discBuilder.append("""<ul class="contributor-list">""")
            for (group in contributors) {
                @Suppress("UNCHECKED_CAST")
                val contributorList = group["contributorList"] as? List<Map<String, Any?>> ?: emptyList()
                for (person in contributorList) {
                    val name = person["name"] as? String ?: ""
                    val disclosure = person["disclosure"] as? String ?: ""

                    discBuilder.append("""<li><div class="contributor-name">$name</div>""")
                    if (disclosure.isNotEmpty()) {
                        discBuilder.append("""<div class="contributor-disclosure">$disclosure</div>""")
                    }
                    discBuilder.append("</li>")
                }
            }
            discBuilder.append("</ul>")
            discBuilder.append("</div>")
            disclosuresHtml = discBuilder.toString()
        }

        val metaLinksHtml = """
        <div class="meta-links-row">
            <a href="#" onclick="var el = document.getElementById('topicContributors'); if(el) el.style.display = el.style.display === 'none' ? 'block' : 'none'; return false;">Contributors</a>
            <span class="meta-separator"></span>
            <a href="#" onclick="var el = document.getElementById('topicDisclosures'); if(el) el.style.display = el.style.display === 'none' ? 'block' : 'none'; return false;">Disclosures</a>
            <span class="meta-separator"></span>
            <a href="#" onclick="var el = document.getElementById('literatureReviewDate'); if(el) el.style.display = el.style.display === 'none' ? 'block' : 'none'; return false;">Date</a>
        </div>
        """.trimIndent()

        if ("<h1 class=\"topic-title\">" in result) {
            val firstClose = result.indexOf("</h1>")
            if (firstClose >= 0) {
                result = result.substring(0, firstClose + 5) + metaLinksHtml + result.substring(firstClose + 5)
            }
        } else if ("<div id=\"topicTitle\">" in result) {
            result = result.replace(
                Regex("""<div id="topicTitle">.*?</div>""", setOf(RegexOption.DOT_MATCHES_ALL))
            ) { "${it.value}$metaLinksHtml" }
        }

        if (contributorsHtml.isNotEmpty()) {
            when {
                """<dl id="topicContributors">""" in result -> {
                    result = result.replace(
                        Regex("""<dl id="topicContributors">.*?</dl>""", setOf(RegexOption.DOT_MATCHES_ALL)),
                        contributorsHtml
                    )
                }
                """<div id="topicContributors">""" in result -> {
                    result = result.replace(
                        Regex("""<div id="topicContributors">.*?</div>""", setOf(RegexOption.DOT_MATCHES_ALL)),
                        contributorsHtml
                    )
                }
                else -> {
                    result = result.replace(metaLinksHtml, "$metaLinksHtml$contributorsHtml")
                }
            }
        } else {
            result = result.replace(
                """<dl id="topicContributors">""",
                """<dl id="topicContributors" style="display:none">"""
            )
        }

        if (disclosuresHtml.isNotEmpty()) {
            if (contributorsHtml.isNotEmpty() && contributorsHtml in result) {
                result = result.replace(contributorsHtml, "$contributorsHtml$disclosuresHtml")
            } else {
                result = result.replace(metaLinksHtml, "$metaLinksHtml$disclosuresHtml")
            }
        }

        result = result.replace(
            """<div id="literatureReviewDate">""",
            """<div id="literatureReviewDate" style="display:none">"""
        )

        result = result.replace(
            Regex("""<span class="emphasis">Literature review current through:</span>.*?&#124;&#160;""", setOf(RegexOption.DOT_MATCHES_ALL)),
            ""
        )

        result = result.replace(
            Regex("""<p class="disclosureLink">.*?</p>""", setOf(RegexOption.DOT_MATCHES_ALL)),
            ""
        )

        return result
    }
}
