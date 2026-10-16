package com.clinref.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

/**
 * A custom [Scene] that displays the conversation ([firstEntry]) and clinical evidence
 * reader ([secondEntry]) side-by-side in an adaptive 42% / 58% supporting-pane layout
 * on expanded screens (>= 840dp).
 */
data class SupportingPaneScene<T : Any>(
    override val key: Any,
    override val previousEntries: List<NavEntry<T>>,
    val firstEntry: NavEntry<T>,
    val secondEntry: NavEntry<T>
) : Scene<T> {
    override val entries: List<NavEntry<T>> = listOf(firstEntry, secondEntry)
    override val content: @Composable (() -> Unit) = {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight()
            ) {
                firstEntry.Content()
            }
            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            Box(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight()
            ) {
                secondEntry.Content()
            }
        }
    }
}

/**
 * A custom [OverlayScene] that renders the clinical evidence reader ([entry]) in-window
 * on compact screens (< 840dp) while preserving [overlaidEntries] (the conversation) composed
 * in memory underneath to prevent LazyColumn scroll position loss or measured height clamping.
 */
data class InWindowOverlayScene<T : Any>(
    override val key: Any,
    override val previousEntries: List<NavEntry<T>>,
    override val overlaidEntries: List<NavEntry<T>>,
    val entry: NavEntry<T>
) : OverlayScene<T> {
    override val entries: List<NavEntry<T>> = listOf(entry)
    override val content: @Composable (() -> Unit) = {
        AnimatedVisibility(
            visible = true,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                entry.Content()
            }
        }
    }
}

/**
 * Adaptive Navigation 3 [SceneStrategy] for clinical evidence reading:
 * - On expanded screens (>= 840dp): Renders [SupportingPaneScene] (42% Chat / 58% Reader).
 * - On compact screens (< 840dp): Renders [InWindowOverlayScene] keeping [ChatRoute] alive underneath.
 * - For non-reader routes: Returns null to delegate to [androidx.navigation3.scene.SinglePaneSceneStrategy].
 */
class AdaptiveReaderSceneStrategy<T : Any>(
    val isExpanded: Boolean
) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (entries.size < 2) return null
        val lastEntry = entries.lastOrNull() ?: return null
        if (lastEntry.contentKey !is ContentRoute) return null
        val chatEntry = entries.findLast { it.contentKey is ChatRoute } ?: return null

        return if (isExpanded) {
            SupportingPaneScene(
                key = Pair(chatEntry.contentKey, lastEntry.contentKey),
                previousEntries = entries.dropLast(1),
                firstEntry = chatEntry,
                secondEntry = lastEntry
            )
        } else {
            InWindowOverlayScene(
                key = lastEntry.contentKey,
                previousEntries = entries.dropLast(1),
                overlaidEntries = entries.dropLast(1),
                entry = lastEntry
            )
        }
    }
}
