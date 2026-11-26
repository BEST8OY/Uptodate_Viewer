package com.clinref.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.clinref.app.ui.adaptive.LocalWindowAdaptiveInfo

val TopLevelRoute.title: String
    get() = when (this) {
        TocRoute -> "Contents"
        HistoryRoute -> "History"
        FavoritesRoute -> "Favorites"
        AiRoute -> "AI"
    }

val TopLevelRoute.icon: ImageVector
    get() = when (this) {
        TocRoute -> Icons.Default.Home
        HistoryRoute -> Icons.AutoMirrored.Filled.List
        FavoritesRoute -> Icons.Default.Favorite
        AiRoute -> Icons.AutoMirrored.Filled.Chat
    }

@Composable
internal fun ClinRefNavRail(
    visible: Boolean,
    currentRoute: NavKey,
    onNavigate: (TopLevelRoute) -> Unit,
    motion: NavMotion,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.effectsSpec) +
            slideInHorizontally(motion.spatialSpec) { -it },
        exit = fadeOut(motion.fastEffectsSpec) +
            slideOutHorizontally(motion.exitSpatialSpec) { -it },
        modifier = modifier
    ) {
        val adaptiveInfo = LocalWindowAdaptiveInfo.current
        NavigationRail(
            windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(if (adaptiveInfo.isCompactHeight) 4.dp else 16.dp))
                topLevelRoutes.forEach { route ->
                    val isSelected = route == currentRoute
                    NavigationRailItem(
                        selected = isSelected,
                        onClick = { onNavigate(route) },
                        icon = {
                            Icon(
                                imageVector = route.icon,
                                contentDescription = route.title
                            )
                        },
                        label = { Text(route.title) },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    )
                }
            }
        }
    }
}

@Composable
internal fun BoxScope.ClinRefBottomBar(
    visible: Boolean,
    currentRoute: NavKey,
    onNavigate: (TopLevelRoute) -> Unit,
    motion: NavMotion,
    modifier: Modifier = Modifier,
    onHeightChanged: (Int) -> Unit = {}
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.effectsSpec) +
            slideInVertically(motion.spatialSpec) { it },
        exit = fadeOut(motion.fastEffectsSpec) +
            slideOutVertically(motion.exitSpatialSpec) { it },
        modifier = modifier
            .align(Alignment.BottomCenter)
            .onSizeChanged { if (it.height > 0) onHeightChanged(it.height) }
    ) {
        NavigationBar(
            windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        ) {
            topLevelRoutes.forEach { route ->
                val isSelected = route == currentRoute
                NavigationBarItem(
                    selected = isSelected,
                    onClick = { onNavigate(route) },
                    icon = {
                        Icon(
                            imageVector = route.icon,
                            contentDescription = route.title
                        )
                    },
                    label = { Text(route.title) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                )
            }
        }
    }
}
