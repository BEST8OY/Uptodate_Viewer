package com.clinref.app.ui.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clinref.app.domain.ai.PatientProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientProfileSheet(
    onDismiss: () -> Unit,
    onStart: (PatientProfile) -> Unit
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    val ageState = rememberTextFieldState()
    var sex by remember { mutableStateOf("") }
    var sexExpanded by remember { mutableStateOf(false) }
    val conditions = remember { mutableStateListOf<String>() }
    val medications = remember { mutableStateListOf<String>() }
    val allergies = remember { mutableStateListOf<String>() }
    val notesState = rememberTextFieldState()

    val conditionInputState = rememberTextFieldState()
    val medicationInputState = rememberTextFieldState()
    val allergyInputState = rememberTextFieldState()

    val sexOptions = listOf("Male", "Female", "Other", "")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Patient Profile",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Optional — helps tailor clinical answers to the patient context.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Age
            OutlinedTextField(
                state = ageState,
                label = { Text("Age") },
                placeholder = { Text("e.g. 45") },
                modifier = Modifier.fillMaxWidth(),
                lineLimits = TextFieldLineLimits.SingleLine
            )

            // Sex
            ExposedDropdownMenuBox(
                expanded = sexExpanded,
                onExpandedChange = { sexExpanded = it }
            ) {
                OutlinedTextField(
                    value = sex.ifEmpty { "Select" },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Sex") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sexExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = sexExpanded,
                    onDismissRequest = { sexExpanded = false }
                ) {
                    sexOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.ifEmpty { "None" }) },
                            onClick = {
                                sex = option
                                sexExpanded = false
                            }
                        )
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
                    val text = conditionInputState.text.toString().trim()
                    if (text.isNotBlank()) {
                        conditions.add(text)
                        conditionInputState.clearText()
                    }
                },
                onRemove = { conditions.remove(it) }
            )

            // Medications
            ChipInputSection(
                label = "Medications",
                placeholder = "e.g. Metformin, Lisinopril",
                state = medicationInputState,
                items = medications,
                onAdd = {
                    val text = medicationInputState.text.toString().trim()
                    if (text.isNotBlank()) {
                        medications.add(text)
                        medicationInputState.clearText()
                    }
                },
                onRemove = { medications.remove(it) }
            )

            // Allergies
            ChipInputSection(
                label = "Allergies",
                placeholder = "e.g. Penicillin, Sulfa",
                state = allergyInputState,
                items = allergies,
                onAdd = {
                    val text = allergyInputState.text.toString().trim()
                    if (text.isNotBlank()) {
                        allergies.add(text)
                        allergyInputState.clearText()
                    }
                },
                onRemove = { allergies.remove(it) }
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
                            conditions = conditions.toList(),
                            medications = medications.toList(),
                            allergies = allergies.toList(),
                            notes = notesState.text.toString().trim()
                        )
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start Conversation")
            }
        }
    }
}
