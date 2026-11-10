package com.clinref.app.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.clinref.app.ui.adaptive.LocalIsWideScreen
import com.clinref.app.util.UrlUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferenceSheet(
    data: ReferenceDialogData,
    onDismiss: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val isWideScreen = LocalIsWideScreen.current

    if (isWideScreen) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                ReferenceSheetContent(
                    data = data,
                    onDismiss = onDismiss,
                    onJumpTo = onJumpTo,
                    onOpenUrl = onOpenUrl,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
    } else {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            ReferenceSheetContent(
                data = data,
                onDismiss = onDismiss,
                onJumpTo = onJumpTo,
                onOpenUrl = onOpenUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun ReferenceSheetContent(
    data: ReferenceDialogData,
    onDismiss: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var copiedRefNumber by remember { mutableStateOf<Int?>(null) }

    fun copyCitation(ref: ReferenceItem) {
        clipboardManager.setText(AnnotatedString(ref.citation))
        copiedRefNumber = ref.number
        coroutineScope.launch {
            delay(2000)
            if (copiedRefNumber == ref.number) {
                copiedRefNumber = null
            }
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (data.references.size == 1) "Reference" else "References (${data.references.size})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(data.references) { ref ->
                ReferenceItemView(
                    ref = ref,
                    isCopied = copiedRefNumber == ref.number,
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
                    onCopy = { copyCitation(ref) }
                )
            }
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
                        maxLines = 1
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
                    FilledTonalButton(onClick = onJumpTo) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Jump to in Article", style = MaterialTheme.typography.labelMedium)
                    }
                }

                OutlinedButton(onClick = onOpenPubMed) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("PubMed", style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(onClick = onOpenScholar) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Scholar", style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(onClick = onCopy) {
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
