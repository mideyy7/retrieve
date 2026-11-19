package com.mideyy7.retrieve.ui.search

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.search.IndexState
import com.mideyy7.retrieve.search.SearchResult
import com.mideyy7.retrieve.search.Snippets

@Composable
fun SearchScreen(modifier: Modifier = Modifier, vm: SearchViewModel = viewModel()) {
    val query by vm.query.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val indexState by vm.indexState.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            singleLine = true,
            label = { Text("Search your lectures") },
            placeholder = { Text("e.g. module:COMP21111 week:4..6 \"unit propagation\" NOT example") },
            isError = ui.error != null && ui.query == query,
            supportingText = if (ui.error != null && ui.query == query) {
                { Text(ui.error!!) }
            } else {
                null
            },
        )
        Text(
            text = statusLine(query, ui, indexState),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (query.isNotBlank() && ui.query == query && ui.error == null && ui.results.isEmpty() && indexState is IndexState.Ready) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Text("No matches. Try fewer words or check spelling.", modifier = Modifier.padding(top = 32.dp))
            }
        } else {
            val terms = remember(ui.query) { Snippets.queryTerms(ui.query) }
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.results, key = { it.hit.chunk.stableKey }) { r -> ResultCard(r, terms) }
            }
        }
    }
}

private fun statusLine(query: String, ui: SearchUiState, state: IndexState): String = when {
    state is IndexState.NotLoaded -> "Preparing index…"
    state is IndexState.Error -> "Index error: ${state.message}"
    query.isBlank() && state is IndexState.Ready -> "${state.stats.chunks} chunks indexed"
    ui.query != query -> "Searching…"
    ui.error != null -> "Fix the query to search"
    else -> "${ui.results.size} results · ${ui.tookMs} ms"
}

@Composable
private fun ResultCard(result: SearchResult, terms: Set<String>) {
    var expanded by rememberSaveable(result.hit.chunk.stableKey) { mutableStateOf(false) }
    val c = result.hit.chunk
    val snippet = remember(c.text, terms) { Snippets.make(c.text, terms) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }.animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${c.module} · week ${c.week} · ${location(result)}", style = MaterialTheme.typography.titleSmall)
            Text(
                if (expanded) highlighted(c.text, Snippets.make(c.text, terms, maxChars = c.text.length).highlights)
                else highlighted(snippet.text, snippet.highlights),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${result.hit.docName} · score ${"%.2f".format(result.score)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun location(r: SearchResult): String {
    val c = r.hit.chunk
    return when {
        c.startMs != null -> "%d:%02d".format(c.startMs / 60_000, (c.startMs / 1000) % 60)
        r.hit.docType == DocType.SLIDES -> "slide ${c.page}"
        else -> "page ${c.page}"
    }
}

private fun highlighted(text: String, ranges: List<IntRange>) = buildAnnotatedString {
    append(text)
    ranges.forEach { addStyle(SpanStyle(fontWeight = FontWeight.Bold), it.first, it.last + 1) }
}
