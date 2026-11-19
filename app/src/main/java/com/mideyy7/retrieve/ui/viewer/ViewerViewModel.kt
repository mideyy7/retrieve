package com.mideyy7.retrieve.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mideyy7.retrieve.data.Chunk
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.data.Document
import com.mideyy7.retrieve.retrieveApp
import com.mideyy7.retrieve.ui.ViewerRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One place in a file: a slide/page (with [page]) or a stretch of a recording (with [startMs]). */
data class ViewerPage(val page: Int?, val startMs: Long?, val text: String)

data class ViewerState(val doc: Document, val pages: List<ViewerPage>, val initialIndex: Int)

/**
 * A page's text put back together from its chunks. Long pages were split into windows that share
 * [overlap] words with the previous one (see Chunker), so each later window adds only its tail.
 */
fun joinPageChunks(parts: List<String>, overlap: Int = 40): String =
    parts.withIndex().joinToString(" ") { (i, text) ->
        if (i == 0) text else text.split(' ').drop(overlap).joinToString(" ")
    }.trim()

/**
 * Every page of a PDF in order (1..[pageCount], empty text where nothing was indexed, e.g. a picture
 * slide), or every timed stretch of a recording.
 */
fun viewerPages(type: DocType, chunks: List<Chunk>, pageCount: Int?): List<ViewerPage> {
    if (type == DocType.AUDIO) {
        return chunks.filter { it.startMs != null }.sortedBy { it.startMs }.map { ViewerPage(null, it.startMs, it.text) }
    }
    val byPage = chunks.filter { it.page != null }.groupBy { it.page!! }
    val count = maxOf(pageCount ?: 0, byPage.keys.maxOrNull() ?: 0)
    return (1..count).map { p -> ViewerPage(p, null, joinPageChunks(byPage[p].orEmpty().sortedBy { it.id }.map { it.text })) }
}

class ViewerViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    val route: ViewerRoute = saved.toRoute<ViewerRoute>()

    private val _state = MutableStateFlow<ViewerState?>(null)
    val state: StateFlow<ViewerState?> = _state.asStateFlow()

    private val _missing = MutableStateFlow(false)
    /** The document was deleted while this screen was in the back stack. */
    val missing: StateFlow<Boolean> = _missing.asStateFlow()

    init {
        viewModelScope.launch {
            val a = app.retrieveApp
            val doc = a.database.documents().byId(route.docId)
            if (doc == null) {
                _missing.value = true
                return@launch
            }
            val chunks = a.database.chunks().forDoc(doc.id)
            val count = if (doc.type == DocType.AUDIO) null else a.slides.pageCount(doc.id)
            val pages = viewerPages(doc.type, chunks, count)
            val start = pages.indexOfFirst {
                if (route.startMs >= 0) it.startMs == route.startMs else it.page == route.page
            }.coerceAtLeast(0)
            _state.value = ViewerState(doc, pages, start)
        }
    }
}
