package com.mideyy7.retrieve.ui.search

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mideyy7.retrieve.retrieveApp
import com.mideyy7.retrieve.search.IndexState
import com.mideyy7.retrieve.search.SearchOutcome
import com.mideyy7.retrieve.search.SearchRepository
import com.mideyy7.retrieve.search.SearchResult
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    /** Time for this query in the app (index + Room lookup). Informational; benchmarks come from Phase 7. */
    val tookMs: Long? = null,
    /** Syntax error with column, e.g. "expected ')' to close '(' at column 1". */
    val error: String? = null,
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app.retrieveApp.keywordIndex
    private val repo = SearchRepository(app.retrieveApp.database, store)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val indexState: StateFlow<IndexState> = store.state

    val ui: StateFlow<SearchUiState> =
        combine(_query.debounce(150).distinctUntilChanged(), store.state) { q, _ -> q }
            .mapLatest { q ->
                if (q.isBlank()) {
                    SearchUiState(q)
                } else {
                    val start = SystemClock.elapsedRealtime()
                    when (val outcome = repo.search(q)) {
                        is SearchOutcome.Results -> SearchUiState(q, outcome.results, SystemClock.elapsedRealtime() - start)
                        is SearchOutcome.SyntaxError -> SearchUiState(q, error = outcome.message)
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun onQueryChange(q: String) {
        _query.value = q
    }
}
