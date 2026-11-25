package com.clinref.app.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clinref.app.ui.adaptive.AdaptiveTokens
import com.clinref.app.ui.adaptive.LocalWindowAdaptiveInfo
import com.clinref.app.ui.common.ClinRefAdaptiveSheet

/**
 * GRADE recommendation explanation presented as a bottom sheet,
 * mirroring the official UpToDate grade pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradeSheet(
    data: GradeSheetData,
    onDismiss: () -> Unit
) {
    ClinRefAdaptiveSheet(
        onDismiss = onDismiss,
        maxWidth = AdaptiveTokens.MaxSheetReadingWidth,
        containerColor = MaterialTheme.colorScheme.surface,
        showDivider = true,
        title = "Recommendation",
        titleBadge = {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = data.grade,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    ) { _, bottomInset ->
        val adaptiveInfo = LocalWindowAdaptiveInfo.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = adaptiveInfo.horizontalMargin, vertical = 8.dp)
                .padding(bottom = 24.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = data.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(14.dp)
                )
            }

            if (data.tierExplanation.isNotEmpty()) {
                SectionLabel("Explanation")
                SheetParagraph(data.tierExplanation)
                SheetParagraph(data.letterExplanation)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionLabel("Recommendation grades")
            UTD_RECOMMENDATION_GRADE_LEGEND.forEach { (title, description) ->
                LegendItem(title, description)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionLabel("Evidence grades")
            UTD_EVIDENCE_GRADE_LEGEND.forEach { (title, description) ->
                LegendItem(title, description)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SheetParagraph(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun LegendItem(title: String, description: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
