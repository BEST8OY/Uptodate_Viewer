package com.clinref.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clinref.app.ui.adaptive.BottomSafeInsetsExcludingIme
import com.clinref.app.ui.adaptive.HorizontalSafeInsets
import com.clinref.app.ui.adaptive.LocalWindowAdaptiveInfo
import com.clinref.app.ui.adaptive.ProvidePaneAdaptiveInfo
import com.clinref.app.ui.adaptive.TopSafeInsets
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Standardized ClinRef modal presentation shell.
 *
 * Renders as a centered M3 [Dialog] on large windows (`>= 840dp` outer window width and
 * `>= 480dp` outer window height, preserving full-width readability even when invoked from
 * narrow supporting-pane splits) and as an edge-to-edge [ModalBottomSheet] on Compact/Medium
 * windows (`< 840dp`) or compact-height windows (`< 480dp`, e.g., phone landscape).
 *
 * Ensures consistent Material 3 styling, explicit [maxWidth] constraint, accessible header
 * hierarchy with title truncation, and graceful exit animations via an injected `dismiss()`
 * callback, while forwarding `bottomInset` so scrollable children clear bottom system bars.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClinRefAdaptiveSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    titleBadge: (@Composable () -> Unit)? = null,
    maxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    heightFraction: Float? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    showDragHandle: Boolean = true,
    showCloseButton: Boolean = true,
    showDivider: Boolean = false,
    sheetGesturesEnabled: Boolean = true,
    sheetState: SheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    ),
    content: @Composable ColumnScope.(dismiss: () -> Unit, bottomInset: Dp) -> Unit
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    var isDismissing by remember { mutableStateOf(false) }
    val adaptiveInfo = LocalWindowAdaptiveInfo.current
    val useDialog = adaptiveInfo.isLargeWindow
    val effectiveHeightFraction = heightFraction?.let {
        if (adaptiveInfo.isWindowCompactHeight) maxOf(it, 0.95f) else it
    }

    if (useDialog) {
        val immediateDismiss: () -> Unit = {
            if (!isDismissing) {
                isDismissing = true
                currentOnDismiss()
            }
        }

        val maxDialogWidth = minOf(
            maxWidth,
            (adaptiveInfo.windowWidthDp - 48.dp).coerceAtLeast(280.dp)
        )
        val maxDialogHeight = (adaptiveInfo.windowHeightDp - 48.dp).coerceAtLeast(280.dp)

        Dialog(
            onDismissRequest = immediateDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier.imePadding(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = modifier
                        .widthIn(max = maxDialogWidth)
                        .fillMaxWidth()
                        .heightIn(max = maxDialogHeight)
                        .then(
                            if (effectiveHeightFraction != null) Modifier.fillMaxHeight(effectiveHeightFraction)
                            else Modifier
                        ),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = containerColor,
                    tonalElevation = 6.dp
                ) {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val dialogWidth = this@BoxWithConstraints.maxWidth
                        val dialogHeight = this@BoxWithConstraints.maxHeight
                        ProvidePaneAdaptiveInfo(
                            paneWidthDp = dialogWidth,
                            paneHeightDp = dialogHeight
                        ) {
                            val dialogAdaptiveInfo = LocalWindowAdaptiveInfo.current
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (effectiveHeightFraction != null) Modifier.fillMaxSize()
                                        else Modifier
                                    )
                            ) {
                                SheetHeader(
                                    title = title,
                                    subtitle = subtitle,
                                    titleBadge = titleBadge,
                                    horizontalMargin = dialogAdaptiveInfo.horizontalMargin,
                                    showDragHandle = false,
                                    showCloseButton = true,
                                    showDivider = showDivider,
                                    onClose = immediateDismiss
                                )
                                content(immediateDismiss, 0.dp)
                            }
                        }
                    }
                }
            }
        }
    } else {
        val bottomInset = BottomSafeInsetsExcludingIme
            .asPaddingValues()
            .calculateBottomPadding()

        val animatedDismiss: () -> Unit = {
            if (!isDismissing) {
                isDismissing = true
                scope.launch {
                    try {
                        sheetState.hide()
                    } finally {
                        if (isActive) {
                            currentOnDismiss()
                        }
                    }
                }
            }
        }

        ModalBottomSheet(
            onDismissRequest = {
                if (!isDismissing) {
                    isDismissing = true
                    currentOnDismiss()
                }
            },
            sheetState = sheetState,
            sheetMaxWidth = maxWidth,
            containerColor = containerColor,
            contentWindowInsets = { TopSafeInsets },
            dragHandle = if (showDragHandle) {
                { BottomSheetDefaults.DragHandle() }
            } else null,
            sheetGesturesEnabled = sheetGesturesEnabled,
            modifier = modifier
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (effectiveHeightFraction != null) Modifier.fillMaxHeight(effectiveHeightFraction)
                        else Modifier
                    )
                    .windowInsetsPadding(HorizontalSafeInsets)
                    .imePadding()
            ) {
                SheetHeader(
                    title = title,
                    subtitle = subtitle,
                    titleBadge = titleBadge,
                    horizontalMargin = adaptiveInfo.horizontalMargin,
                    showDragHandle = showDragHandle,
                    showCloseButton = showCloseButton,
                    showDivider = showDivider,
                    onClose = animatedDismiss
                )
                content(animatedDismiss, bottomInset)
            }
        }
    }
}

@Composable
private fun SheetHeader(
    title: String?,
    subtitle: String?,
    titleBadge: (@Composable () -> Unit)?,
    horizontalMargin: Dp,
    showDragHandle: Boolean,
    showCloseButton: Boolean,
    showDivider: Boolean,
    onClose: () -> Unit
) {
    if (title != null || showCloseButton || titleBadge != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = horizontalMargin,
                    end = horizontalMargin,
                    top = if (showDragHandle) 4.dp else 12.dp,
                    bottom = 4.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            titleBadge?.invoke()
            if (title != null) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            if (showCloseButton) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = horizontalMargin, vertical = 8.dp)
            )
        }
    }
}
