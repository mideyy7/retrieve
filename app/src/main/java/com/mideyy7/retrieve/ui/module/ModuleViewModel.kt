package com.mideyy7.retrieve.ui.module

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.work.WorkManager
import com.mideyy7.retrieve.data.Weeks
import com.mideyy7.retrieve.retrieveApp
import com.mideyy7.retrieve.ui.ModuleRoute
import com.mideyy7.retrieve.ui.library.DocumentRow
import com.mideyy7.retrieve.ui.library.withIndexingProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ModuleViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    val code: String = saved.toRoute<ModuleRoute>().code

    private val db = app.retrieveApp.database
    private val library = app.retrieveApp.library

    /** Null until the first read. */
    val rows: StateFlow<List<DocumentRow>?> =
        db.documents().observeModuleSummaries(code)
            .withIndexingProgress(WorkManager.getInstance(app))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val title: StateFlow<String?> =
        db.modules().observeTitle(code).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every module code, for the Move dialog's suggestions and to warn when a rename would merge. */
    val existingModules: StateFlow<List<String>> =
        db.documents().observeModules()
            .map { modules -> modules.map { it.module }.sorted() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Set once the module has been renamed, so the screen can reopen itself under the new code. */
    private val _renamedTo = MutableStateFlow<String?>(null)
    val renamedTo: StateFlow<String?> = _renamedTo.asStateFlow()

    /** Weeks the user opened or closed by hand; untouched weeks follow [isExpanded]'s default. */
    private val _toggled = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val toggled: StateFlow<Map<Int, Boolean>> = _toggled.asStateFlow()

    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun toggleWeek(week: Int, currentlyExpanded: Boolean) = _toggled.update { it + (week to !currentlyExpanded) }

    fun setFilter(text: String) {
        _filter.value = text
    }

    fun retry(docId: Long) = launchReporting { library.retry(docId) }

    fun delete(docId: Long, name: String) = launchReporting("Deleted $name") { library.delete(docId) }

    fun renameDocument(docId: Long, name: String) = launchReporting { library.renameDocument(docId, name) }

    fun moveDocument(docId: Long, name: String, module: String, week: Int) = launchReporting(
        if (module == code) "Moved $name to ${Weeks.label(week)}" else "Moved $name to $module › ${Weeks.label(week)}",
    ) { library.moveDocument(docId, module, week) }

    fun setTitle(title: String) = launchReporting { library.setModuleTitle(code, title) }

    fun renameModule(to: String) = launchReporting {
        library.renameModule(code, to)
        _renamedTo.value = to
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
