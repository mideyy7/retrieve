package com.mideyy7.retrieve.ui.search

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mideyy7.retrieve.retrieveApp
import com.mideyy7.retrieve.search.IndexState
import com.mideyy7.retrieve.search.SearchOutcome
import com.mideyy7.retrieve.search.SearchRepository
import com.mideyy7.retrieve.search.SearchResult
import com.mideyy7.retrieve.ui.theme.assignModuleHues
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    /** Time for this query in the app (index + Room lookup). Informational; benchmarks come from Phase 7. */
    val tookMs: Long? = null,
    /** Syntax error with column, e.g. "expected ')' to close '(' at column 1". */
    val error: String? = null,
    /** Keyword + semantic fusion was used (plain question with embeddings available). */
    val hybrid: Boolean = false,
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val store = app.retrieveApp.keywordIndex
    private val repo = SearchRepository(
        app.retrieveApp.database, store, app.retrieveApp.semanticIndex, { app.retrieveApp.embedder.embed(it) },
    )

    /** Kept in SavedStateHandle so the query survives the process being killed in the background. */
    private val _query = saved.getMutableStateFlow(KEY_QUERY, "")
    val query: StateFlow<String> = _query.asStateFlow()

    val indexState: StateFlow<IndexState> = store.state

    private val modules = app.retrieveApp.database.documents().observeModules()

    /** Most recently imported modules, offered as one-tap scopes while the query is empty. */
    val recentModules: StateFlow<List<String>> =
        modules.map { list -> list.take(6).map { it.module } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Each module's colour, shared by every screen (this view model lives as long as the Activity). */
    val moduleHues: StateFlow<Map<String, Int>> =
        modules.map { list -> assignModuleHues(list.map { it.module }) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val ui: StateFlow<SearchUiState> =
        combine(_query.debounce(150).distinctUntilChanged(), store.state) { q, _ -> q }
            .mapLatest { q ->
                if (q.isBlank()) {
                    SearchUiState(q)
                } else {
                    val start = SystemClock.elapsedRealtime()
                    when (val outcome = repo.search(q)) {
                        is SearchOutcome.Results ->
                            SearchUiState(q, outcome.results, SystemClock.elapsedRealtime() - start, hybrid = outcome.hybrid)
                        is SearchOutcome.SyntaxError -> SearchUiState(q, error = outcome.message)
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun onQueryChange(q: String) {
        _query.value = q
    }

    /** Narrows the current query, e.g. after tapping a module or week on a result. */
    fun addFilter(key: String, value: String) {
        _query.value = withFilter(_query.value, key, value)
    }

    private companion object {
        const val KEY_QUERY = "query"
    }
}
