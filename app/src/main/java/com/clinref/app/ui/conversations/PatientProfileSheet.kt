package com.clinref.app.ui.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PatientProfileSheet(
    onDismiss: () -> Unit,
    onStart: (PatientProfile) -> Unit
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)


    var age by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf("") }
    var sexExpanded by remember { mutableStateOf(false) }
    val conditions = remember { mutableStateListOf<String>() }
    val medications = remember { mutableStateListOf<String>() }
    val allergies = remember { mutableStateListOf<String>() }
    var notes by remember { mutableStateOf("") }

    var conditionInput by remember { mutableStateOf("") }
    var medicationInput by remember { mutableStateOf("") }
    var allergyInput by remember { mutableStateOf("") }

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
                value = age,
                onValueChange = { age = it },
                label = { Text("Age") },
                placeholder = { Text("e.g. 45") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
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
                input = conditionInput,
                onInputChange = { conditionInput = it },
                items = conditions,
                onAdd = {
                    if (conditionInput.isNotBlank()) {
                        conditions.add(conditionInput.trim())
                        conditionInput = ""
                    }
                },
                onRemove = { conditions.remove(it) }
            )

            // Medications
            ChipInputSection(
                label = "Medications",
                placeholder = "e.g. Metformin, Lisinopril",
                input = medicationInput,
                onInputChange = { medicationInput = it },
                items = medications,
                onAdd = {
                    if (medicationInput.isNotBlank()) {
                        medications.add(medicationInput.trim())
                        medicationInput = ""
                    }
                },
                onRemove = { medications.remove(it) }
            )

            // Allergies
            ChipInputSection(
                label = "Allergies",
                placeholder = "e.g. Penicillin, Sulfa",
                input = allergyInput,
                onInputChange = { allergyInput = it },
                items = allergies,
                onAdd = {
                    if (allergyInput.isNotBlank()) {
                        allergies.add(allergyInput.trim())
                        allergyInput = ""
                    }
                },
                onRemove = { allergies.remove(it) }
            )

            // Notes
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                placeholder = { Text("Additional clinical context...") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Start button
            Button(
                onClick = {
                    onStart(
                        PatientProfile(
                            age = age.trim(),
                            sex = sex.trim(),
                            conditions = conditions.toList(),
                            medications = medications.toList(),
                            allergies = allergies.toList(),
                            notes = notes.trim()
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipInputSection(
    label: String,
    placeholder: String,
    input: String,
    onInputChange: (String) -> Unit,
    items: List<String>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    Column {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                placeholder = { Text(placeholder) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add $label")
            }
        }
        if (items.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items.forEach { item ->
                    FilterChip(
                        selected = true,
                        onClick = { },
                        label = { Text(item) },
                        trailingIcon = {
                            IconButton(
                                onClick = { onRemove(item) },
                                modifier = Modifier.height(18.dp).width(18.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove $item",
                                    modifier = Modifier.height(14.dp)
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}
