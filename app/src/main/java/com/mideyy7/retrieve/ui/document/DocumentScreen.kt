package com.mideyy7.retrieve.ui.document

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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.data.Document
import com.mideyy7.retrieve.data.DocumentSummary
import com.mideyy7.retrieve.data.Weeks
import com.mideyy7.retrieve.ui.components.MoveDialog
import com.mideyy7.retrieve.ui.components.TextInputDialog
import com.mideyy7.retrieve.ui.library.DocumentRow
import com.mideyy7.retrieve.ui.library.DocumentStatus
import com.mideyy7.retrieve.ui.library.label
import java.text.DateFormat
import java.util.Date

private enum class DocDialog { RENAME, MOVE, DELETE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    onBack: () -> Unit,
    onOpenPage: (docId: Long, page: Int?, startMs: Long?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    vm: DocumentViewModel = viewModel(),
) {
    val doc by vm.document.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val existingModules by vm.existingModules.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<DocDialog?>(null) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(deleted) { if (deleted) onBack() }
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
                title = { Text(doc?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }, enabled = doc != null) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Document options")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; dialog = DocDialog.RENAME })
                            DropdownMenuItem(text = { Text("Move to…") }, onClick = { menu = false; dialog = DocDialog.MOVE })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; dialog = DocDialog.DELETE })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        doc?.let { d ->
            DocumentContent(d, pages, onRetry = vm::retry, onOpenPage = { p -> onOpenPage(d.id, p.page, p.startMs) }, modifier = Modifier.padding(padding))
        }
    }

    val d = doc ?: return
    val dismiss = { dialog = null }
    when (dialog) {
        null -> Unit
        DocDialog.RENAME -> TextInputDialog(
            title = "Rename file", label = "Name", initial = d.name, confirmLabel = "Rename",
            check = { if (it.isBlank()) "Name can't be empty" else null },
            onConfirm = { dismiss(); vm.rename(it) }, onDismiss = dismiss,
        )
        DocDialog.MOVE -> MoveDialog(
            name = d.name, module = d.module, week = d.week, existingModules = existingModules,
            onConfirm = { m, w -> dismiss(); vm.move(m, w) }, onDismiss = dismiss,
        )
        DocDialog.DELETE -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Delete ${d.name}?") },
            text = { Text("Its pages are removed from search. The original file on your phone isn't touched.") },
            confirmButton = { TextButton(onClick = { dismiss(); vm.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )
    }
}

/** Stateless: where the document sits, its indexing state, and its pages with the start of their text. */
@Composable
fun DocumentContent(
    doc: Document,
    pages: List<PageRow>?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPage: (PageRow) -> Unit = {},
) {
    val chunkCount = pages?.sumOf { it.parts } ?: 0
    val ocrPages = pages?.count { it.ocr } ?: 0
    val row = DocumentRow(DocumentSummary(doc.id, doc.name, doc.module, doc.week, doc.type, doc.status, doc.error, chunkCount), null)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${doc.module} › ${Weeks.label(doc.week)} › ${doc.type.label()}", style = MaterialTheme.typography.titleSmall)
                DocumentStatus(row, onRetry = onRetry)
                val details = buildList {
                    pages?.let { add("${it.size} page${if (it.size == 1) "" else "s"}") }
                    if (ocrPages > 0) add("$ocrPages read with OCR")
                    add("imported ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(doc.importedAt))}")
                }
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
        items(pages.orEmpty(), key = { "${it.page}:${it.startMs}" }) { p -> PageRowView(p, doc.type, onClick = { onOpenPage(p) }) }
    }
}

@Composable
private fun PageRowView(p: PageRow, type: DocType, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val where = when {
                p.startMs != null -> "%d:%02d".format(p.startMs / 60_000, (p.startMs / 1000) % 60)
                type == DocType.SLIDES -> "Slide ${p.page}"
                else -> "Page ${p.page}"
            }
            Text(where, style = MaterialTheme.typography.labelLarge)
            if (p.ocr) Text("OCR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        Text(
            p.preview.ifEmpty { "(no text)" },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
