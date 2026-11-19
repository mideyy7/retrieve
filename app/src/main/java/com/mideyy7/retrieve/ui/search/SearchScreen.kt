package com.mideyy7.retrieve.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mideyy7.retrieve.R
import com.mideyy7.retrieve.search.IndexState
import com.mideyy7.retrieve.search.SearchResult
import com.mideyy7.retrieve.search.Snippets
import com.mideyy7.retrieve.ui.components.citation
import com.mideyy7.retrieve.ui.components.copyText
import com.mideyy7.retrieve.ui.theme.Mono
import com.mideyy7.retrieve.ui.theme.moduleColor
import kotlinx.coroutines.launch

/** Content is capped at this width so lines stay readable on a tablet. */
private val MaxContentWidth = 840.dp

@Composable
fun SearchScreen(
    onOpenResult: (SearchResult, query: String) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    vm: SearchViewModel = viewModel(),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val indexState by vm.indexState.collectAsStateWithLifecycle()
    val modules by vm.recentModules.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var help by rememberSaveable { mutableStateOf(false) }

    // The field keeps its own TextFieldValue (text + cursor). When the query is changed from
    // elsewhere (a module page, a breadcrumb, a tip), put the cursor at the end so typing continues it.
    var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(query) {
        if (query != field.text) field = TextFieldValue(query, TextRange(query.length))
    }

    val actions = remember(vm) {
        ResultActions(
            onOpen = { r ->
                vm.commit()
                keyboard?.hide()
                onOpenResult(r, vm.query.value)
            },
            onCopy = { r ->
                vm.commit()
                val c = r.hit.chunk
                if (copyText(context, "Citation", citation(c.module, c.week, r.hit.docName, r.hit.docType, c.page, c.startMs))) {
                    scope.launch { snackbar.showSnackbar("Copied where this is") }
                }
            },
            onFilter = vm::addFilter,
        )
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val home = query.isBlank()
        // On the home screen the logo and search bar sit a little above the middle, like a search
        // engine's front page; once there's a query the bar moves to the top to make room for results.
        val homeTop = maxHeight * 0.16f
        Column(
            Modifier.align(Alignment.TopCenter).widthIn(max = MaxContentWidth).fillMaxWidth().fillMaxHeight()
                .padding(horizontal = 16.dp).padding(top = 52.dp),
        ) {
            AnimatedVisibility(visible = home) {
                Logo(Modifier.fillMaxWidth().padding(top = homeTop, bottom = 28.dp))
            }
            SearchField(
                value = field,
                onValueChange = {
                    field = it
                    vm.onQueryChange(it.text)
                },
                error = ui.error.takeIf { ui.query == query }
                    ?: (indexState as? IndexState.Error)?.let { "Search isn't available: ${it.message}" },
                onSearch = {
                    vm.commit()
                    keyboard?.hide()
                },
                onClear = {
                    vm.onQueryChange("")
                    focus.requestFocus()
                },
                modifier = Modifier.focusRequester(focus),
            )
            val searching = query.isNotBlank() && ui.query != query
            Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp)) {
                if (searching || indexState is IndexState.NotLoaded) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            when {
                home -> StartContent(
                    modules = modules,
                    recent = recent,
                    hasLibrary = library.files > 0,
                    onModule = { vm.addFilter("module", it) },
                    onRecent = { vm.onQueryChange(it) },
                    onRemoveRecent = vm::removeRecent,
                    onClearRecent = vm::clearRecent,
                    onImport = onImport,
                )
                ui.query != query || ui.error != null -> Unit
                ui.results.isEmpty() && indexState is IndexState.Ready -> NoResults(query, onHelp = { help = true })
                else -> Results(ui, actions)
            }
        }
        IconButton(onClick = { help = true }, modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)) {
            Icon(painterResource(R.drawable.ic_help), contentDescription = "Search tips", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (help) {
        SearchHelpSheet(
            module = modules.firstOrNull(),
            onTry = {
                help = false
                vm.onQueryChange(it)
            },
            onDismiss = { help = false },
        )
    }
}

/** The app's mark (the launcher icon's slide and magnifier) and its name. */
@Composable
private fun Logo(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            // The foreground is drawn for a 108dp adaptive-icon canvas; scaled up, its art fills the tile.
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(84.dp))
        }
        Text(
            "Retrieve",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

@Composable
private fun SearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    error: String?,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        // Typed queries are code-like (module:COMP21111 week:4..6), so they're set in mono as on the site.
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono),
        placeholder = { Text("Search slides, notes and lectures", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = {
            Row {
                if (value.text.isNotEmpty()) {
                    IconButton(onClick = onClear) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                }
                IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
            }
        },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartContent(
    modules: List<String>,
    recent: List<String>,
    hasLibrary: Boolean,
    onModule: (String) -> Unit,
    onRecent: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onImport: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (!hasLibrary) {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.large).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Start with one week", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "Pick the PDFs for a week of lectures, or the captions file (.vtt) of a recording. They're read and indexed on your device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Button(onClick = onImport) { Text("Import files") }
            }
        }
        if (modules.isNotEmpty()) {
            // Module shortcuts, like a browser's new-tab tiles: tap one to search inside it.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                modules.forEach { module -> ModuleTile(module, onClick = { onModule(module) }) }
            }
        }
        if (recent.isNotEmpty()) {
            Section("Recent", action = { TextButton(onClick = onClearRecent) { Text("Clear") } }) {
                Column {
                    recent.forEach { q ->
                        Row(
                            Modifier.fillMaxWidth().clip12().clickable { onRecent(q) }.padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(R.drawable.ic_history), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Text(q, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { onRemoveRecent(q) }) {
                                Icon(Icons.Filled.Close, "Remove $q from recent", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A module as a round, coloured folder with its code underneath. */
@Composable
private fun ModuleTile(module: String, onClick: () -> Unit) {
    val color = moduleColor(module)
    Column(
        Modifier.width(96.dp).clip12().clickable(onClickLabel = "Search only $module", onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(60.dp).background(color.container, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_folder), contentDescription = null, tint = color.accent, modifier = Modifier.size(28.dp))
        }
        Text(module, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Section(title: String, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            action?.invoke()
        }
        content()
    }
}

@Composable
private fun NoResults(query: String, onHelp: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Nothing for “${query.trim()}”", style = MaterialTheme.typography.titleMedium)
        Text(
            "Try fewer words, check the spelling, or remove a filter.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onHelp) { Text("Search tips") }
    }
}

@Composable
private fun Results(ui: SearchUiState, actions: ResultActions) {
    val terms = remember(ui.query) { Snippets.queryTerms(ui.query) }
    val list = rememberLazyListState()
    // A new search starts at its best match, not wherever the previous list was scrolled to.
    LaunchedEffect(ui.query) { list.scrollToItem(0) }
    LazyColumn(state = list, contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "summary") { Summary(ui) }
        itemsIndexed(ui.results, key = { _, r -> r.hit.chunk.stableKey }) { i, r ->
            if (i == 0) BestMatchCard(r, terms, actions) else ResultRow(r, terms, actions)
            if (i == 0 && ui.results.size > 1) {
                Spacer(Modifier.size(6.dp))
                Text("More results", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun Summary(ui: SearchUiState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${ui.results.size} result${if (ui.results.size == 1) "" else "s"} in ${ui.tookMs} ms",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ui.hybrid) {
            Row(
                Modifier.background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(painterResource(R.drawable.ic_sparkle), null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(12.dp))
                Text("words + meaning", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

private fun Modifier.clip12() = this.then(Modifier.clip(RoundedCornerShape(12.dp)))
