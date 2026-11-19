package com.mideyy7.retrieve.ui.search

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.data.Weeks
import com.mideyy7.retrieve.search.IndexState
import com.mideyy7.retrieve.search.SearchResult
import com.mideyy7.retrieve.search.Snippets

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(modifier: Modifier = Modifier, vm: SearchViewModel = viewModel()) {
    val query by vm.query.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val indexState by vm.indexState.collectAsStateWithLifecycle()
    val recentModules by vm.recentModules.collectAsStateWithLifecycle()

    // The field keeps its own TextFieldValue (text + cursor). When the query is changed from
    // elsewhere (a module page, a breadcrumb), put the cursor at the end so typing continues it.
    var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(query) {
        if (query != field.text) {
            field = TextFieldValue(query, TextRange(query.length))
            if (query.isNotBlank()) focus.requestFocus()
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = field,
            onValueChange = {
                field = it
                vm.onQueryChange(it.text)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).focusRequester(focus),
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
        if (query.isBlank() && recentModules.isNotEmpty()) {
            Text("Search in", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recentModules.forEach { module ->
                    SuggestionChip(onClick = { vm.addFilter("module", module) }, label = { Text(module) })
                }
            }
        }
        if (query.isNotBlank() && ui.query == query && ui.error == null && ui.results.isEmpty() && indexState is IndexState.Ready) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Text("No matches. Try fewer words or check spelling.", modifier = Modifier.padding(top = 32.dp))
            }
        } else {
            val terms = remember(ui.query) { Snippets.queryTerms(ui.query) }
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.results, key = { it.hit.chunk.stableKey }) { r -> ResultCard(r, terms, onFilter = vm::addFilter) }
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
private fun ResultCard(result: SearchResult, terms: Set<String>, onFilter: (key: String, value: String) -> Unit) {
    var expanded by rememberSaveable(result.hit.chunk.stableKey) { mutableStateOf(false) }
    val c = result.hit.chunk
    val snippet = remember(c.text, terms) { Snippets.make(c.text, terms) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }.animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Breadcrumb(result, onFilter)
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

/** `COMP26120 › Week 6 › slide 14`. Module and week are tappable and narrow the query to them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Breadcrumb(result: SearchResult, onFilter: (key: String, value: String) -> Unit) {
    val c = result.hit.chunk
    val crumb = MaterialTheme.typography.titleSmall
    val link = MaterialTheme.colorScheme.primary
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text(
            c.module,
            style = crumb,
            color = link,
            modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Search only ${c.module}") { onFilter("module", c.module) },
        )
        Text("›", style = crumb)
        Text(
            Weeks.label(c.week),
            style = crumb,
            color = link,
            // A week means this module's week, so narrow to both.
            modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Search only ${c.module} ${Weeks.label(c.week)}") {
                onFilter("module", c.module)
                onFilter("week", c.week.toString())
            },
        )
        Text("›", style = crumb)
        Text(location(result), style = crumb)
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
