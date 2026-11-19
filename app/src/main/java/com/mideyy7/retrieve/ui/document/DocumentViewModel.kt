package com.mideyy7.retrieve.ui.document

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mideyy7.retrieve.data.Chunk
import com.mideyy7.retrieve.data.Document
import com.mideyy7.retrieve.data.Weeks
import com.mideyy7.retrieve.retrieveApp
import com.mideyy7.retrieve.ui.DocumentRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One page (or slide) of a document as listed on its page: the start of its text and whether OCR read it. */
data class PageRow(val page: Int?, val startMs: Long?, val preview: String, val ocr: Boolean, val parts: Int)

/** Chunks of one page are listed once, in page order. */
fun pageRows(chunks: List<Chunk>): List<PageRow> =
    chunks.groupBy { it.page to it.startMs }.map { (key, parts) ->
        val text = parts.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim()
        PageRow(key.first, key.second, text.take(160), parts.any { it.ocr }, parts.size)
    }

class DocumentViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    val docId: Long = saved.toRoute<DocumentRoute>().id

    private val db = app.retrieveApp.database
    private val library = app.retrieveApp.library

    /** Null while loading and after the document is deleted. */
    val document: StateFlow<Document?> =
        db.documents().observeById(docId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pages: StateFlow<List<PageRow>?> =
        db.chunks().observeForDoc(docId).map(::pageRows).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val existingModules: StateFlow<List<String>> =
        db.documents().observeModules()
            .map { modules -> modules.map { it.module }.sorted() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun retry() = launchReporting { library.retry(docId) }

    fun rename(name: String) = launchReporting { library.renameDocument(docId, name) }

    fun move(module: String, week: Int) = launchReporting("Moved to $module › ${Weeks.label(week)}") {
        library.moveDocument(docId, module, week)
    }

    fun delete() = launchReporting {
        library.delete(docId)
        _deleted.value = true
    }

    fun messageShown() {
        _message.value = null
    }

    private fun launchReporting(success: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            _message.value = try {
                block()
                success
            } catch (e: Exception) {
                e.message ?: "Something went wrong"
            }
        }
    }
}
