package com.mideyy7.retrieve.ui.module

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mideyy7.retrieve.data.ModuleCodes
import com.mideyy7.retrieve.data.Weeks
import com.mideyy7.retrieve.ui.components.MoveDialog
import com.mideyy7.retrieve.ui.components.TextInputDialog
import com.mideyy7.retrieve.ui.library.DocumentRow
import com.mideyy7.retrieve.ui.library.DocumentStatus

enum class RowAction { OPEN, RETRY, RENAME, MOVE, DELETE }

enum class WeekAction { SEARCH, ADD_FILES }

private sealed interface ModuleDialog {
    data class Delete(val row: DocumentRow) : ModuleDialog
    data class Rename(val row: DocumentRow) : ModuleDialog
    data class Move(val row: DocumentRow) : ModuleDialog
    data object EditTitle : ModuleDialog
    data object RenameModule : ModuleDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleScreen(
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onImport: (week: Int?) -> Unit,
    onRenamed: (newCode: String) -> Unit,
    modifier: Modifier = Modifier,
    vm: ModuleViewModel = viewModel(),
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val toggled by vm.toggled.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val existingModules by vm.existingModules.collectAsStateWithLifecycle()
    val renamedTo by vm.renamedTo.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<ModuleDialog?>(null) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(renamedTo) { renamedTo?.let(onRenamed) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Column {
                        Text(vm.code)
                        title?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Module options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (title == null) "Add title" else "Edit title") },
                                onClick = { menu = false; dialog = ModuleDialog.EditTitle },
                            )
                            DropdownMenuItem(text = { Text("Rename module code") }, onClick = { menu = false; dialog = ModuleDialog.RenameModule })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onImport(null) }) { Text("Add files") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        ModuleContent(
            code = vm.code,
            rows = rows,
            filter = filter,
            toggled = toggled,
            onFilterChange = vm::setFilter,
            onToggleWeek = vm::toggleWeek,
            onSearch = onSearch,
            onWeekAction = { week, action ->
                when (action) {
                    WeekAction.SEARCH -> onSearch(scopeQuery(vm.code, week))
                    WeekAction.ADD_FILES -> onImport(week)
                }
            },
            onRowAction = { row, action ->
                when (action) {
                    RowAction.RETRY -> vm.retry(row.summary.id)
                    RowAction.DELETE -> dialog = ModuleDialog.Delete(row)
                    RowAction.RENAME -> dialog = ModuleDialog.Rename(row)
                    RowAction.MOVE -> dialog = ModuleDialog.Move(row)
                    RowAction.OPEN -> Unit
                }
            },
            modifier = Modifier.padding(padding),
        )
    }

    val dismiss = { dialog = null }
    when (val d = dialog) {
        null -> Unit
        is ModuleDialog.Delete -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Delete ${d.row.summary.name}?") },
            text = { Text("Its pages are removed from search. The original file on your phone isn't touched.") },
            confirmButton = {
                TextButton(onClick = { dismiss(); vm.delete(d.row.summary.id, d.row.summary.name) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )
        is ModuleDialog.Rename -> TextInputDialog(
            title = "Rename file",
            label = "Name",
            initial = d.row.summary.name,
            confirmLabel = "Rename",
            check = { if (it.isBlank()) "Name can't be empty" else null },
            onConfirm = { dismiss(); vm.renameDocument(d.row.summary.id, it) },
            onDismiss = dismiss,
        )
        is ModuleDialog.Move -> MoveDialog(
            name = d.row.summary.name,
            module = d.row.summary.module,
            week = d.row.summary.week,
            existingModules = existingModules,
            onConfirm = { module, week -> dismiss(); vm.moveDocument(d.row.summary.id, d.row.summary.name, module, week) },
            onDismiss = dismiss,
        )
        ModuleDialog.EditTitle -> TextInputDialog(
            title = "Title for ${vm.code}",
            label = "e.g. Algorithms and Data Structures",
            initial = title.orEmpty(),
            confirmLabel = "Save",
            note = { if (it.isBlank()) "Leave empty to remove the title." else null },
            onConfirm = { dismiss(); vm.setTitle(it) },
            onDismiss = dismiss,
        )
        ModuleDialog.RenameModule -> TextInputDialog(
            title = "Rename ${vm.code}",
            label = "Module code",
            initial = vm.code,
            confirmLabel = "Rename",
            check = { text ->
                when (ModuleCodes.normalize(text)) {
                    null -> "Letters, digits, - . _ and no spaces"
                    vm.code -> "That's the current code"
                    else -> null
                }
            },
            note = { text ->
                ModuleCodes.normalize(text)?.takeIf { it in existingModules && it != vm.code }
                    ?.let { "$it already exists: ${vm.code} will be merged into it." }
            },
            onConfirm = { dismiss(); vm.renameModule(ModuleCodes.normalize(it)!!) },
            onDismiss = dismiss,
        )
    }
}

/** Stateless module page: search entry, optional filter, and documents grouped by week. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModuleContent(
    code: String,
    rows: List<DocumentRow>?,
    filter: String,
    toggled: Map<Int, Boolean>,
    onFilterChange: (String) -> Unit,
    onToggleWeek: (week: Int, currentlyExpanded: Boolean) -> Unit,
    onSearch: (String) -> Unit,
    onWeekAction: (Int, WeekAction) -> Unit,
    onRowAction: (DocumentRow, RowAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows == null) {
        Box(modifier.fillMaxSize())
        return
    }
    if (rows.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No documents in $code.")
        }
        return
    }
    val groups = groupByWeek(rows, filter)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "header") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(moduleHeadline(rows), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedCard(onClick = { onSearch(scopeQuery(code)) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Text("Search $code")
                    }
                }
                if (rows.size > FOLD_THRESHOLD) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = onFilterChange,
                        label = { Text("Find a file") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (groups.isEmpty()) {
            item(key = "no-match") { Text("No file names match.", Modifier.padding(16.dp)) }
        }
        groups.forEach { group ->
            val expanded = isExpanded(group.week, rows.size, filter, toggled)
            stickyHeader(key = "week-${group.week}") {
                WeekHeader(group, expanded, onToggle = { onToggleWeek(group.week, expanded) }, onAction = { onWeekAction(group.week, it) })
            }
            if (expanded) {
                items(group.rows, key = { it.summary.id }) { row ->
                    DocumentRowView(row, onAction = { onRowAction(row, it) })
                }
            }
        }
    }
}

@Composable
private fun WeekHeader(group: WeekGroup, expanded: Boolean, onToggle: () -> Unit, onAction: (WeekAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "Fold" else "Unfold", onClick = onToggle)
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
                Text(
                    Weeks.label(group.week),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp).weight(1f).semantics { heading() },
                )
                Text("${group.rows.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "${Weeks.label(group.week)} options")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (group.week == Weeks.WHOLE_MODULE) "Search whole-module material" else "Search this week") },
                            onClick = { menu = false; onAction(WeekAction.SEARCH) },
                        )
                        DropdownMenuItem(text = { Text("Add files here") }, onClick = { menu = false; onAction(WeekAction.ADD_FILES) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentRowView(row: DocumentRow, onAction: (RowAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.summary.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            DocumentStatus(row, onRetry = { onAction(RowAction.RETRY) })
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "${row.summary.name} options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onAction(RowAction.RENAME) })
                DropdownMenuItem(text = { Text("Move to…") }, onClick = { menu = false; onAction(RowAction.MOVE) })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onAction(RowAction.DELETE) })
            }
        }
    }
}
