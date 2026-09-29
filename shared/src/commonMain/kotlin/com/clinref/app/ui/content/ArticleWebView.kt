package com.clinref.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Returns JavaScript snippet to scroll to a target section by ID or anchor name.
 */
fun scrollToSectionJs(sectionId: String): String {
    val escaped = sectionId.replace("'", "\\'")
    return """
        (function() {
            var cleanId = '$escaped';
            if (!cleanId || cleanId.toUpperCase() === 'FULL') return;
            function tryScroll(attempts) {
                var el = document.getElementById(cleanId) ||
                         document.querySelector('[id="' + cleanId + '"]') ||
                         document.querySelector('a[name="' + cleanId + '"]');
                if (el) {
                    el.scrollIntoView({behavior: 'smooth', block: 'start'});
                } else if (attempts > 0) {
                    setTimeout(function() { tryScroll(attempts - 1); }, 100);
                }
            }
            tryScroll(10);
        })();
    """.trimIndent()
}

/**
 * Platform-independent controller for clinical article HTML presentation.
 * Coordinates section jumping, in-page search, and action interception.
 */
class ArticleWebViewController {

    var onAction: ((String) -> Unit)? = null
    var onFindResult: ((count: Int, activeMatchOrdinal: Int) -> Unit)? = null

    private var platformScrollToSection: ((String) -> Unit)? = null
    private var platformFindAll: ((String) -> Unit)? = null
    private var platformClearFindMatches: (() -> Unit)? = null
    private var platformFindNext: ((Boolean) -> Unit)? = null

    fun bindPlatform(
        scrollToSection: (String) -> Unit,
        findAll: (String) -> Unit,
        clearFindMatches: () -> Unit,
        findNext: (Boolean) -> Unit
    ) {
        this.platformScrollToSection = scrollToSection
        this.platformFindAll = findAll
        this.platformClearFindMatches = clearFindMatches
        this.platformFindNext = findNext
    }

    fun unbindPlatform() {
        this.platformScrollToSection = null
        this.platformFindAll = null
        this.platformClearFindMatches = null
        this.platformFindNext = null
    }

    fun findAll(query: String) {
        platformFindAll?.invoke(query)
    }

    fun clearFindMatches() {
        platformClearFindMatches?.invoke()
    }

    fun findNext(forward: Boolean) {
        platformFindNext?.invoke(forward)
    }

    fun scrollToSection(sectionId: String) {
        platformScrollToSection?.invoke(sectionId)
    }
}

@Composable
fun rememberArticleWebViewController(): ArticleWebViewController =
    remember { ArticleWebViewController() }

/**
 * Cross-platform HTML article renderer.
 * On Android, uses hardware-accelerated Chrome WebView with nested scrolling and JS bridge.
 * On Desktop JVM, uses SwingPanel + JEditorPane with HTML3.2/4 rendering and action routing.
 */
@Composable
expect fun HtmlContentWebView(
    processedHtml: String?,
    controller: ArticleWebViewController,
    initialSectionId: String? = null,
    modifier: Modifier = Modifier
)
