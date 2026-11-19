package com.mideyy7.retrieve.ui.importer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.ingest.FilenameTagParser
import com.mideyy7.retrieve.ingest.ImportResult
import com.mideyy7.retrieve.retrieveApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The import sheet's state, shared by every screen that can start an import. The draft is kept in
 * SavedStateHandle: Android may kill the app while the system file picker is open.
 */
class ImportViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val repo = app.retrieveApp.imports

    private val _draft = MutableStateFlow(
        saved.get<String>(KEY_DRAFT)?.let { runCatching { Json.decodeFromString<ImportDraft>(it) }.getOrNull() } ?: ImportDraft(),
    )
    val draft: StateFlow<ImportDraft> = _draft.asStateFlow()

    val existingModules: StateFlow<List<String>> =
        app.retrieveApp.database.documents().observeModules()
            .map { modules -> modules.map { it.module }.sorted() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private fun update(f: (ImportDraft) -> ImportDraft) {
        val next = f(_draft.value)
        _draft.value = next
        saved[KEY_DRAFT] = Json.encodeToString(next)
    }

    /** Called before the picker opens, with the module/week of the screen it was opened from. */
    fun prepare(module: String?, week: Int?) {
        if (_draft.value.rows.isEmpty()) update { ImportDraft(defaultModule = module.orEmpty(), defaultWeek = week?.toString().orEmpty()) }
    }

    fun onFilesPicked(uris: List<Uri>) {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    repo.displayName(uri).let { DraftRow(uri.toString(), it, FilenameTagParser.parse(it)).copy(type = typeForFile(it)) }
                }
            }
            update { d -> d.copy(rows = d.rows + rows.filter { r -> d.rows.none { it.uri == r.uri } }) }
        }
    }

    fun setDefaultModule(text: String) = update { it.copy(defaultModule = text) }

    fun setDefaultWeek(text: String) = update { it.copy(defaultWeek = cleanWeek(text)) }

    fun setRowModule(uri: String, text: String) = updateRow(uri) { it.copy(module = text) }

    fun setRowWeek(uri: String, text: String) = updateRow(uri) { it.copy(week = cleanWeek(text)) }

    fun setRowType(uri: String, type: DocType) = updateRow(uri) { it.copy(type = type) }

    fun removeRow(uri: String) = update { d -> d.copy(rows = d.rows.filterNot { it.uri == uri }) }

    fun cancel() = update { ImportDraft() }

    private fun updateRow(uri: String, f: (DraftRow) -> DraftRow) =
        update { d -> d.copy(rows = d.rows.map { if (it.uri == uri) f(it) else it }) }

    /** Imports every ready row. Rows that still need a module or week stay in the sheet. */
    fun importReady() {
        val draft = _draft.value
        val ready = draft.rows.filter { draft.check(it).ready }
        if (ready.isEmpty()) return
        update { d -> d.copy(rows = d.rows - ready.toSet()) }
        viewModelScope.launch {
            var queued = 0
            val notes = mutableListOf<String>()
            for (row in ready) {
                val c = draft.check(row)
                try {
                    when (val r = repo.import(Uri.parse(row.uri), row.name, c.module!!, c.week!!, row.type)) {
                        is ImportResult.Queued -> queued++
                        is ImportResult.AlreadyImported -> notes += "${row.name} is already imported as ${r.name}"
                    }
                } catch (e: Exception) {
                    notes += "Couldn't import ${row.name}: ${e.message}"
                }
            }
            val summary = if (queued > 0) listOf("Importing $queued file${if (queued == 1) "" else "s"}") else emptyList()
            _message.value = (summary + notes).joinToString("\n")
        }
    }

    fun messageShown() {
        _message.value = null
    }

    private companion object {
        const val KEY_DRAFT = "importDraft"
    }
}
