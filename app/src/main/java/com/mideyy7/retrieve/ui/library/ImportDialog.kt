package com.mideyy7.retrieve.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mideyy7.retrieve.data.DocType

/** Asks for module, week and type for one picked file, pre-filled from its filename. */
@Composable
fun ImportDialog(item: PendingImport, onConfirm: (String, Int, DocType) -> Unit, onSkip: () -> Unit) {
    // rememberSaveable keyed on the URI: survives rotation, resets for the next file.
    var module by rememberSaveable(item.uri) { mutableStateOf(item.guess.module.orEmpty()) }
    var week by rememberSaveable(item.uri) { mutableStateOf(item.guess.week?.toString().orEmpty()) }
    var type by rememberSaveable(item.uri) { mutableStateOf(DocType.SLIDES) }

    val weekNumber = week.toIntOrNull()?.takeIf { it in 1..52 }
    val valid = module.isNotBlank() && weekNumber != null

    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = module,
                    onValueChange = { module = it },
                    label = { Text("Module (e.g. COMP26120)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                OutlinedTextField(
                    value = week,
                    onValueChange = { week = it.filter(Char::isDigit).take(2) },
                    label = { Text("Week") },
                    singleLine = true,
                    isError = week.isNotEmpty() && weekNumber == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(DocType.SLIDES, DocType.PDF).forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label()) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(module, weekNumber!!, type) }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip") } },
    )
}
