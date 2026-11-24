package com.clinref.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.clinref.app.ui.adaptive.LocalWindowAdaptiveInfo

/**
 * Adaptive top app bar that automatically switches to a compact single-row [TopAppBar]
 * on compact-height viewports (such as landscape phones) to preserve vertical screen space,
 * renders [MediumFlexibleTopAppBar] with native collapsing subtitle slot when a subtitle is present,
 * and renders a standard [MediumTopAppBar] on normal viewports when no subtitle is provided.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AdaptiveTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    val adaptiveInfo = LocalWindowAdaptiveInfo.current
    if (adaptiveInfo.isCompactHeight) {
        TopAppBar(
            title = {
                if (subtitle != null) {
                    Column {
                        title()
                        subtitle()
                    }
                } else {
                    title()
                }
            },
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            windowInsets = windowInsets,
            colors = colors,
            scrollBehavior = scrollBehavior
        )
    } else if (subtitle != null) {
        MediumFlexibleTopAppBar(
            title = title,
            subtitle = subtitle,
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            windowInsets = windowInsets,
            colors = colors,
            scrollBehavior = scrollBehavior
        )
    } else {
        MediumTopAppBar(
            title = title,
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            windowInsets = windowInsets,
            colors = colors,
            scrollBehavior = scrollBehavior
        )
    }
}
