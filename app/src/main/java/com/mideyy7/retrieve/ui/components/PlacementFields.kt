package com.mideyy7.retrieve.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mideyy7.retrieve.data.Weeks

/**
 * Module code field with the existing modules offered as chips underneath, so a typo doesn't
 * quietly create a second module next to the real one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModuleField(
    value: String,
    onValueChange: (String) -> Unit,
    existing: List<String>,
    modifier: Modifier = Modifier,
    label: String = "Module (e.g. COMP26120)",
    isError: Boolean = false,
) {
    val typed = value.trim().uppercase()
    val suggestions = existing.filter { it.startsWith(typed) && it != typed }.take(6)
    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            isError = isError,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        )
        if (suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { code -> SuggestionChip(onClick = { onValueChange(code) }, label = { Text(code) }) }
            }
        }
    }
}

/** Week number, or the "Whole module" chip for week 0. */
@Composable
fun WeekField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, isError: Boolean = false) {
    val whole = value == Weeks.WHOLE_MODULE.toString()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = if (whole) "" else value,
            onValueChange = onValueChange,
            label = { Text("Week") },
            singleLine = true,
            isError = isError,
            enabled = !whole,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(96.dp),
        )
        FilterChip(
            selected = whole,
            onClick = { onValueChange(if (whole) "" else Weeks.WHOLE_MODULE.toString()) },
            label = { Text(Weeks.label(Weeks.WHOLE_MODULE)) },
        )
    }
}
