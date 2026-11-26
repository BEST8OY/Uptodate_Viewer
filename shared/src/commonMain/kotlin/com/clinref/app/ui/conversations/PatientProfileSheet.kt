package com.clinref.app.ui.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.ui.adaptive.AdaptiveTokens
import com.clinref.app.ui.adaptive.LocalWindowAdaptiveInfo
import com.clinref.app.ui.common.ClinRefAdaptiveSheet

private val StringListSaver = listSaver<List<String>, String>(
    save = { it },
    restore = { it.toList() }
)

private fun List<String>.addUniqueTrimmed(raw: CharSequence): List<String> {
    val trimmed = raw.toString().trim()
    if (trimmed.isBlank() || any { it.equals(trimmed, ignoreCase = true) }) return this
    return this + trimmed
}

/**
 * Modal bottom sheet for capturing or editing patient clinical context.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientProfileSheet(
    onDismiss: () -> Unit,
    onStart: (PatientProfile) -> Unit,
    initialProfile: PatientProfile = PatientProfile()
) {
    val ageState = rememberSaveable(initialProfile, saver = TextFieldState.Saver) {
        TextFieldState(initialProfile.age)
    }
    var sex by rememberSaveable(initialProfile) { mutableStateOf(initialProfile.sex) }
    var conditions by rememberSaveable(initialProfile, stateSaver = StringListSaver) {
        mutableStateOf(initialProfile.conditions)
    }
    var medications by rememberSaveable(initialProfile, stateSaver = StringListSaver) {
        mutableStateOf(initialProfile.medications)
    }
    var allergies by rememberSaveable(initialProfile, stateSaver = StringListSaver) {
        mutableStateOf(initialProfile.allergies)
    }
    val notesState = rememberSaveable(initialProfile, saver = TextFieldState.Saver) {
        TextFieldState(initialProfile.notes)
    }

    val conditionInputState = rememberTextFieldState()
    val medicationInputState = rememberTextFieldState()
    val allergyInputState = rememberTextFieldState()

    ClinRefAdaptiveSheet(
        onDismiss = onDismiss,
        title = "Patient Profile",
        maxWidth = AdaptiveTokens.MaxSheetCompactWidth,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        showDivider = true
    ) { dismiss, bottomInset ->
        val adaptiveInfo = LocalWindowAdaptiveInfo.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = adaptiveInfo.horizontalMargin)
                .padding(bottom = 24.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Optional — helps tailor clinical answers to the patient context.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Age & Sex Row
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Demographics",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedTextField(
                    state = ageState,
                    label = { Text("Age") },
                    placeholder = { Text("e.g. 54 or 8 months") },
                    modifier = Modifier.fillMaxWidth(),
                    lineLimits = TextFieldLineLimits.SingleLine
                )

                // Sex selector
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Biological Sex",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val options = listOf("Female", "Male", "Other")
                        options.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = sex.equals(option, ignoreCase = true),
                                onClick = {
                                    sex = if (sex.equals(option, ignoreCase = true)) "" else option
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                            ) {
                                Text(option, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // Conditions
            ChipInputSection(
                label = "Conditions",
                placeholder = "e.g. Diabetes, Hypertension",
                state = conditionInputState,
                items = conditions,
                onAdd = {
                    if (conditionInputState.text.isNotBlank()) {
                        conditions = conditions.addUniqueTrimmed(conditionInputState.text)
                        conditionInputState.clearText()
                    }
                },
                onRemove = { toRemove -> conditions = conditions.filterNot { it == toRemove } }
            )

            // Medications
            ChipInputSection(
                label = "Medications",
                placeholder = "e.g. Metformin, Lisinopril",
                state = medicationInputState,
                items = medications,
                onAdd = {
                    if (medicationInputState.text.isNotBlank()) {
                        medications = medications.addUniqueTrimmed(medicationInputState.text)
                        medicationInputState.clearText()
                    }
                },
                onRemove = { toRemove -> medications = medications.filterNot { it == toRemove } }
            )

            // Allergies
            ChipInputSection(
                label = "Allergies",
                placeholder = "e.g. Penicillin, Sulfa",
                state = allergyInputState,
                items = allergies,
                onAdd = {
                    if (allergyInputState.text.isNotBlank()) {
                        allergies = allergies.addUniqueTrimmed(allergyInputState.text)
                        allergyInputState.clearText()
                    }
                },
                onRemove = { toRemove -> allergies = allergies.filterNot { it == toRemove } }
            )

            // Notes
            OutlinedTextField(
                state = notesState,
                label = { Text("Notes") },
                placeholder = { Text("Additional clinical context...") },
                modifier = Modifier.fillMaxWidth(),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 4)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Start button
            Button(
                onClick = {
                    onStart(
                        PatientProfile(
                            age = ageState.text.toString().trim(),
                            sex = sex.trim(),
                            conditions = conditions.addUniqueTrimmed(conditionInputState.text),
                            medications = medications.addUniqueTrimmed(medicationInputState.text),
                            allergies = allergies.addUniqueTrimmed(allergyInputState.text),
                            notes = notesState.text.toString().trim()
                        )
                    )
                    dismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text("Start Consultation")
            }
        }
    }
}
