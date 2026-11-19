package com.mideyy7.retrieve.ui.importer

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.data.Weeks
import com.mideyy7.retrieve.ui.components.ModuleField
import com.mideyy7.retrieve.ui.components.WeekField
import com.mideyy7.retrieve.ui.library.label

/** Callbacks for [ImportContent]; one object so the sheet and its tests wire the same set. */
class ImportCallbacks(
    val onDefaultModule: (String) -> Unit = {},
    val onDefaultWeek: (String) -> Unit = {},
    val onRowModule: (uri: String, String) -> Unit = { _, _ -> },
    val onRowWeek: (uri: String, String) -> Unit = { _, _ -> },
    val onRowType: (uri: String, DocType) -> Unit = { _, _ -> },
    val onRemoveRow: (uri: String) -> Unit = {},
    val onImport: () -> Unit = {},
    val onCancel: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(draft: ImportDraft, existingModules: List<String>, callbacks: ImportCallbacks) {
    ModalBottomSheet(onDismissRequest = callbacks.onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ImportContent(draft, existingModules, callbacks)
    }
}

/** Stateless sheet body: "Add to" defaults, one row per file, and Import for the rows that are ready. */
@Composable
fun ImportContent(draft: ImportDraft, existingModules: List<String>, callbacks: ImportCallbacks) {
    val checks = draft.rows.map { it to draft.check(it) }
    val ready = checks.count { it.second.ready }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    // One scrolling list (defaults, then files) above pinned buttons, so the file rows stay
    // reachable on short screens and with the keyboard open.
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        LazyColumn(Modifier.weight(1f, fill = false)) {
            item(key = "defaults") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Import ${draft.rows.size} file${if (draft.rows.size == 1) "" else "s"}", style = MaterialTheme.typography.titleLarge)
                    Text("Add to", style = MaterialTheme.typography.labelLarge)
                    ModuleField(draft.defaultModule, callbacks.onDefaultModule, existingModules)
                    WeekField(draft.defaultWeek, callbacks.onDefaultWeek)
                    Text(
                        "Used for every file whose name doesn't say. Tap a file to change just that one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                }
            }
            items(checks, key = { it.first.uri }) { (row, check) ->
                DraftRowView(
                    row = row,
                    check = check,
                    expanded = editing == row.uri,
                    existingModules = existingModules,
                    onToggle = { editing = if (editing == row.uri) null else row.uri },
                    callbacks = callbacks,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = callbacks.onCancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = callbacks.onImport, enabled = ready > 0) {
                Text(if (ready == draft.rows.size) "Import" else "Import $ready")
            }
        }
    }
}

@Composable
private fun DraftRowView(
    row: DraftRow,
    check: RowCheck,
    expanded: Boolean,
    existingModules: List<String>,
    onToggle: () -> Unit,
    callbacks: ImportCallbacks,
) {
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "Done" else "Edit", onClick = onToggle)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val problem = check.problem
                if (problem != null) {
                    Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(
                        "${check.module} · ${Weeks.label(check.week!!)} · ${row.type.label()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { callbacks.onRemoveRow(row.uri) }) { Icon(Icons.Filled.Close, contentDescription = "Don't import ${row.name}") }
        }
        if (expanded) {
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModuleField(
                    value = check.moduleText,
                    onValueChange = { callbacks.onRowModule(row.uri, it) },
                    existing = existingModules,
                    label = "Module",
                    isError = check.module == null,
                )
                WeekField(check.weekText, { callbacks.onRowWeek(row.uri, it) }, isError = check.week == null)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(DocType.SLIDES, DocType.PDF).forEach { t ->
                        FilterChip(selected = row.type == t, onClick = { callbacks.onRowType(row.uri, t) }, label = { Text(t.label()) })
                    }
                }
            }
        }
    }
}
