package com.clinref.app.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clinref.app.ui.adaptive.AdaptiveTokens
import com.clinref.app.ui.adaptive.LocalWindowAdaptiveInfo
import com.clinref.app.ui.common.ClinRefAdaptiveSheet
import com.clinref.app.ui.common.setPlainText
import com.clinref.app.util.UrlUtils
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferenceSheet(
    data: ReferenceSheetData,
    onDismiss: () -> Unit,
    onJumpTo: (Int) -> Unit = {},
    onOpenUrl: (String) -> Unit = {}
) {
    ClinRefAdaptiveSheet(
        onDismiss = onDismiss,
        title = if (data.references.size == 1) "Reference" else "References (${data.references.size})",
        maxWidth = AdaptiveTokens.MaxSheetReadingWidth,
        containerColor = MaterialTheme.colorScheme.surface,
        showDivider = true
    ) { dismiss, bottomInset ->
        val adaptiveInfo = LocalWindowAdaptiveInfo.current
        ReferenceSheetContent(
            data = data,
            onDismiss = dismiss,
            onJumpTo = onJumpTo,
            onOpenUrl = onOpenUrl,
            bottomInset = bottomInset,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(horizontal = adaptiveInfo.horizontalMargin)
        )
    }
}

@Composable
private fun ReferenceSheetContent(
    data: ReferenceSheetData,
    onDismiss: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onOpenUrl: (String) -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboard.current
    var copiedRefKey by remember(data) { mutableStateOf<String?>(null) }
    var copyTrigger by remember(data) { mutableIntStateOf(0) }

    LaunchedEffect(copiedRefKey, copyTrigger) {
        if (copiedRefKey != null) {
            delay(2000)
            copiedRefKey = null
        }
    }

    fun copyCitation(refKey: String, citation: String) {
        clipboard.setPlainText(citation)
        copiedRefKey = refKey
        copyTrigger++
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(data.references, key = { idx, ref -> "${idx}_${ref.number}_${ref.citation.hashCode()}" }) { _, ref ->
            val refKey = "${ref.number}_${ref.citation.hashCode()}"
            ReferenceItemView(
                ref = ref,
                isCopied = copiedRefKey == refKey,
                onJumpTo = {
                    onJumpTo(ref.number)
                    onDismiss()
                },
                onOpenPubMed = {
                    val url = ref.pubmedUrl.ifEmpty {
                        "https://pubmed.ncbi.nlm.nih.gov/?term=" + UrlUtils.urlEncode(ref.citation)
                    }
                    onOpenUrl(url)
                },
                onOpenScholar = {
                    onOpenUrl("https://scholar.google.com/scholar?q=" + UrlUtils.urlEncode(ref.citation))
                },
                onCopy = { copyCitation(refKey, ref.citation) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReferenceItemView(
    ref: ReferenceItem,
    isCopied: Boolean,
    onJumpTo: () -> Unit,
    onOpenPubMed: () -> Unit,
    onOpenScholar: () -> Unit,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (ref.number > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "[${ref.number}]",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                if (ref.journal.isNotBlank()) {
                    Text(
                        text = ref.journal,
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (ref.title.isNotBlank()) {
                Text(
                    text = ref.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (ref.authors.isNotBlank()) {
                Text(
                    text = ref.authors,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (ref.title.isBlank() && ref.authors.isBlank()) {
                Text(
                    text = ref.citation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (ref.number > 0) {
                    FilledTonalButton(
                        onClick = onJumpTo,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Jump to in Article", style = MaterialTheme.typography.labelMedium)
                    }
                }

                OutlinedButton(
                    onClick = onOpenPubMed,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("PubMed", style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = onOpenScholar,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Scholar", style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isCopied) "Copied!" else "Copy", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
