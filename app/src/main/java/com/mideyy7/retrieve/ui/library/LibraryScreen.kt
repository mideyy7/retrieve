package com.mideyy7.retrieve.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mideyy7.retrieve.nativebridge.NativeBridge
import com.mideyy7.retrieve.ui.components.SegmentedBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenModule: (String) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    vm: LibraryViewModel = viewModel(),
) {
    val modules by vm.modules.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    // Nested inside the app Scaffold, which already handles system-bar insets.
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text("Library") },
                actions = { LibraryMenu(onReindexAll = vm::reindexAll, onExportChunks = vm::exportChunks) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onImport) {
                Text("Import PDFs")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        ModuleGrid(
            modules = modules,
            onOpenModule = onOpenModule,
            emptyFooter = "Native engine ${NativeBridge.nativeVersion()}",
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun LibraryMenu(onReindexAll: () -> Unit, onExportChunks: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text("Re-index all") }, onClick = { open = false; onReindexAll() })
        DropdownMenuItem(text = { Text("Export chunks") }, onClick = { open = false; onExportChunks() })
    }
}

/** Stateless grid of module cards; [modules] is null while loading. */
@Composable
fun ModuleGrid(
    modules: List<ModuleCard>?,
    onOpenModule: (String) -> Unit,
    modifier: Modifier = Modifier,
    emptyFooter: String? = null,
) {
    when {
        modules == null -> Box(modifier.fillMaxSize())
        modules.isEmpty() -> Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No modules yet", style = MaterialTheme.typography.titleMedium)
                Text("Import a week of lecture slides to start. Module and week are filled in from the file name where possible.")
                emptyFooter?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = modifier.fillMaxSize(),
            // Bottom padding keeps the last row clear of the floating import button.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(modules, key = { it.summary.module }) { card -> ModuleCardView(card, onClick = { onOpenModule(card.summary.module) }) }
        }
    }
}

@Composable
private fun ModuleCardView(card: ModuleCard, onClick: () -> Unit) {
    val m = card.summary
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)) {
        Column(
            Modifier.padding(14.dp).clearAndSetSemantics { contentDescription = card.description },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(m.module, style = MaterialTheme.typography.titleMedium)
            m.title?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${m.docCount} doc${if (m.docCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f, fill = false).heightIn(min = 8.dp))
            SegmentedBar(card.segments.map { segmentColor(it.state) })
            card.status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (m.failedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun segmentColor(state: SegmentState): Color = when (state) {
    SegmentState.READY -> MaterialTheme.colorScheme.primary
    SegmentState.BUSY -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    SegmentState.FAILED -> MaterialTheme.colorScheme.error
}
