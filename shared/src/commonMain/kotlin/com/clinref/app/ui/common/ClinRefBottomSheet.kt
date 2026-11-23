package com.clinref.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Standardized ClinRef modal bottom sheet shell.
 *
 * Ensures consistent Material 3 styling, explicit [maxWidth] constraint via [sheetMaxWidth],
 * accessible header hierarchy with title truncation, and graceful exit animations
 * via an injected `dismiss()` callback that hides the sheet before unmounting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClinRefBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    titleBadge: (@Composable () -> Unit)? = null,
    maxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    showDragHandle: Boolean = true,
    showCloseButton: Boolean = true,
    showDivider: Boolean = false,
    sheetGesturesEnabled: Boolean = true,
    sheetState: SheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    ),
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    var isDismissing by remember { mutableStateOf(false) }

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
        dragHandle = if (showDragHandle) {
            { BottomSheetDefaults.DragHandle() }
        } else null,
        sheetGesturesEnabled = sheetGesturesEnabled,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
        ) {
            if (title != null || showCloseButton || titleBadge != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 20.dp,
                            end = 20.dp,
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
                        IconButton(onClick = animatedDismiss) {
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
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
            }

            content(animatedDismiss)
        }
    }
}
