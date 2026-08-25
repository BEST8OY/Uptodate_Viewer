package com.clinref.app.ui.content

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AccessiblePlainTooltip(text: String) {
    // b/496338253: tooltip text is not announced by screen readers without these semantics
    PlainTooltip(
        modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Assertive
            paneTitle = text
        }
    ) {
        Text(text)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ContentFloatingToolbar(
    showSearch: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isFavorite: Boolean,
    outlineEnabled: Boolean,
    searchFieldState: TextFieldState,
    searchResultCount: Int,
    searchResultIndex: Int,
    onBackClick: () -> Unit,
    onForwardClick: () -> Unit,
    onHomeClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onOutlineClick: () -> Unit,
    onSearchClick: () -> Unit,
    onSearchPrevious: () -> Unit,
    onSearchNext: () -> Unit,
    onSearchClose: () -> Unit,
    searchFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val vibrantColors = FloatingToolbarDefaults.vibrantFloatingToolbarColors()
    val motionScheme = MaterialTheme.motionScheme
    val textCharSequence = searchFieldState.text
    val isQueryNotEmpty = textCharSequence.isNotEmpty()

    AnimatedContent(
        targetState = showSearch,
        transitionSpec = {
            fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
                    fadeOut(motionScheme.defaultEffectsSpec())
        },
        label = "SearchToolbarTransition",
        modifier = modifier
    ) { isSearching ->
        if (isSearching) {
            HorizontalFloatingToolbar(
                expanded = true,
                colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    // Explicit bounded height (public token, 64.dp): the library only enforces
                    // this as a min height, but the fillMaxHeight children need a fixed bound.
                    // The FAB-variant nav toolbar is naturally taller (medium-FAB sized).
                    .fillMaxWidth()
                    .height(FloatingToolbarDefaults.ContainerSize),
                content = {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 12.dp)
                            .testTag("toolbar_search_field"),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (textCharSequence.isEmpty()) {
                            Text(
                                text = "Find in page",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        BasicTextField(
                            state = searchFieldState,
                            lineLimits = TextFieldLineLimits.SingleLine,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Search
                            ),
                            onKeyboardAction = {
                                if (isQueryNotEmpty) {
                                    onSearchNext()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester)
                                .focusProperties { canFocus = isSearching }
                        )
                    }

                    if (isQueryNotEmpty) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = "${if (searchResultCount > 0) searchResultIndex + 1 else 0}/$searchResultCount",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .testTag("toolbar_result_count")
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    }

                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = { AccessiblePlainTooltip("Find previous") },
                        state = rememberTooltipState(),
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        IconButton(
                            onClick = onSearchPrevious,
                            enabled = isQueryNotEmpty,
                            modifier = Modifier
                                .fillMaxHeight()
                                .testTag("toolbar_find_prev")
                                .focusProperties { canFocus = isSearching }
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "Find Previous",
                                modifier = Modifier.size(24.dp),
                                tint = LocalContentColor.current
                            )
                        }
                    }

                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = { AccessiblePlainTooltip("Find next") },
                        state = rememberTooltipState(),
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        IconButton(
                            onClick = onSearchNext,
                            enabled = isQueryNotEmpty,
                            modifier = Modifier
                                .fillMaxHeight()
                                .testTag("toolbar_find_next")
                                .focusProperties { canFocus = isSearching }
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Find Next",
                                modifier = Modifier.size(24.dp),
                                tint = LocalContentColor.current
                            )
                        }
                    }

                    VerticalDivider(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .height(20.dp)
                            .padding(horizontal = 2.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = { AccessiblePlainTooltip("Close search") },
                        state = rememberTooltipState(),
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        IconButton(
                            onClick = { onSearchClose() },
                            modifier = Modifier
                                .fillMaxHeight()
                                .testTag("toolbar_search_close")
                                .focusProperties { canFocus = isSearching }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        } else {
            HorizontalFloatingToolbar(
                expanded = true,
                floatingActionButton = {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = { AccessiblePlainTooltip("Search in document") },
                        state = rememberTooltipState()
                    ) {
                        FloatingToolbarDefaults.VibrantFloatingActionButton(
                            onClick = onSearchClick,
                            shape = CircleShape,
                            modifier = Modifier
                                .testTag("toolbar_search_fab")
                                .focusProperties { canFocus = !isSearching }
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    }
                },
                colors = vibrantColors,
                content = {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = { AccessiblePlainTooltip("Back") },
                        state = rememberTooltipState()
                    ) {
                        IconButton(
                            onClick = onBackClick,
                            enabled = canGoBack,
                            modifier = Modifier
                                .testTag("toolbar_back")
                                .focusProperties { canFocus = !isSearching }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = { AccessiblePlainTooltip("Forward") },
                        state = rememberTooltipState()
                    ) {
                        IconButton(
                            onClick = onForwardClick,
                            enabled = canGoForward,
                            modifier = Modifier
                                .testTag("toolbar_forward")
                                .focusProperties { canFocus = !isSearching }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                        }
                    }
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = { AccessiblePlainTooltip("Home / Contents") },
                        state = rememberTooltipState()
                    ) {
                        IconButton(
                            onClick = onHomeClick,
                            modifier = Modifier
                                .testTag("toolbar_home")
                                .focusProperties { canFocus = !isSearching }
                        ) {
                            Icon(Icons.Default.Home, contentDescription = "Contents")
                        }
                    }
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = { AccessiblePlainTooltip("Outline") },
                        state = rememberTooltipState()
                    ) {
                        IconButton(
                            onClick = onOutlineClick,
                            enabled = outlineEnabled,
                            modifier = Modifier
                                .testTag("toolbar_outline")
                                .focusProperties { canFocus = !isSearching }
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = "Outline")
                        }
                    }
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = { AccessiblePlainTooltip(if (isFavorite) "Remove favorite" else "Add favorite") },
                        state = rememberTooltipState()
                    ) {
                        IconButton(
                            onClick = onFavoriteClick,
                            modifier = Modifier
                                .testTag("toolbar_favorite")
                                .focusProperties { canFocus = !isSearching }
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites"
                            )
                        }
                    }
                }
            )
        }
    }
}
