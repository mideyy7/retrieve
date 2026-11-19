package com.mideyy7.retrieve.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mideyy7.retrieve.data.DocStatus
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.nativebridge.NativeBridge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel = viewModel()) {
    val documents by vm.documents.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Storage Access Framework: the system file picker. We get content:// URIs, not file paths,
    // and no storage permission is needed.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.onFilesPicked(uris)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    TextButton(onClick = vm::reindexAll) { Text("Re-index all") }
                    TextButton(onClick = vm::exportChunks) { Text("Export chunks") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                Text("Import PDFs")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (documents.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No documents yet. Import lecture slides or PDFs to start.")
                    Text("Native engine ${NativeBridge.nativeVersion()}", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(documents, key = { it.summary.id }) { row -> DocumentCard(row, onDelete = { vm.delete(row.summary.id) }) }
            }
        }
    }

    pending.firstOrNull()?.let { item ->
        ImportDialog(
            item = item,
            onConfirm = { module, week, type -> vm.confirmImport(item, module, week, type) },
            onSkip = { vm.skipImport(item) },
        )
    }
}

@Composable
private fun DocumentCard(row: DocumentRow, onDelete: () -> Unit) {
    val d = row.summary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.name, style = MaterialTheme.typography.titleMedium)
            Text("${d.module} · week ${d.week} · ${d.type.label()}", style = MaterialTheme.typography.bodyMedium)
            when (d.status) {
                DocStatus.READY -> Text("${d.chunkCount} chunks", style = MaterialTheme.typography.bodySmall)
                DocStatus.PENDING -> Text("Queued", style = MaterialTheme.typography.bodySmall)
                DocStatus.INDEXING -> {
                    val p = row.progress
                    if (p != null && p.total > 0) {
                        Text("Reading page ${p.done} of ${p.total}", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text("Indexing", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                DocStatus.FAILED -> Text(
                    "Failed: ${d.error ?: "unknown error"}. Import the file again to retry.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

fun DocType.label() = when (this) {
    DocType.SLIDES -> "Slides"
    DocType.PDF -> "PDF"
    DocType.AUDIO -> "Recording"
}
