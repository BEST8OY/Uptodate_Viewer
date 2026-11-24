package com.clinref.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Vertically scrollable (by default) empty state so compact-height viewports and open IMEs never clip.
 */
@Composable
fun ClinRefEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    contentPadding: PaddingValues = PaddingValues(32.dp),
    scrollable: Boolean = true,
    fillViewport: Boolean = true,
    useBadge: Boolean = false,
    badgeSize: Dp = 64.dp,
    badgeContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    badgeContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = if (useBadge) 28.dp else 48.dp,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    titleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    action: (@Composable ColumnScope.() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .then(if (fillViewport) Modifier.fillMaxSize() else Modifier)
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (useBadge) {
                Surface(
                    shape = CircleShape,
                    color = badgeContainerColor,
                    modifier = Modifier.size(badgeSize)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = badgeContentColor,
                            modifier = Modifier.size(iconSize)
                        )
                    }
                }
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(iconSize)
                )
            }

            Text(
                text = title,
                style = titleStyle,
                color = titleColor,
                textAlign = TextAlign.Center
            )

            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            if (action != null) {
                action()
            }
        }
    }
}

/**
 * Vertically scrollable (by default) error state with optional retry action.
 */
@Composable
fun ClinRefErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    useTonalRetryButton: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(32.dp),
    scrollable: Boolean = true,
    fillViewport: Boolean = true
) {
    Box(
        modifier = modifier
            .then(if (fillViewport) Modifier.fillMaxSize() else Modifier)
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = "Error",
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            if (onRetry != null) {
                if (useTonalRetryButton) {
                    FilledTonalButton(
                        onClick = onRetry,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Text("Retry", fontWeight = FontWeight.Medium)
                    }
                } else {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry")
                    }
                }
            }
        }
    }
}
