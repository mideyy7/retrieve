package com.mideyy7.retrieve.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.mideyy7.retrieve.data.ModuleCodes
import com.mideyy7.retrieve.data.Weeks

/**
 * One text field and a confirm button. [check] returns an error to show (which disables the
 * button) or null; [note] is shown under the field when there is no error.
 */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    check: (String) -> String? = { null },
    note: (String) -> String? = { null },
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val error = check(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                isError = error != null,
                supportingText = (error ?: note(text))?.let { msg -> { Text(msg) } },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = error == null) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks a module and week for a document. Confirms with a normalised module code and a valid week. */
@Composable
fun MoveDialog(
    name: String,
    module: String,
    week: Int,
    existingModules: List<String>,
    onConfirm: (module: String, week: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var moduleText by rememberSaveable { mutableStateOf(module) }
    var weekText by rememberSaveable { mutableStateOf(week.toString()) }
    val code = ModuleCodes.normalize(moduleText)
    val weekNumber = weekText.toIntOrNull()?.takeIf(Weeks::isValid)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move $name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModuleField(moduleText, { moduleText = it }, existingModules, label = "Module", isError = code == null)
                WeekField(weekText, { weekText = it.filter(Char::isDigit).take(2) }, isError = weekNumber == null)
                if (code != null && code !in existingModules) {
                    Text("$code is a new module.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(code!!, weekNumber!!) }, enabled = code != null && weekNumber != null) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
