package com.mideyy7.retrieve.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mideyy7.retrieve.data.ChunkExporter
import com.mideyy7.retrieve.retrieveApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Screen state lives here rather than in the composables: a ViewModel survives configuration
 * changes (rotation, dark-mode switch) that destroy and recreate the Activity and its UI.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val db = app.retrieveApp.database
    private val repo = app.retrieveApp.imports

    /** Null until the first database read, so the screen doesn't flash its empty state. */
    val modules: StateFlow<List<ModuleCard>?> =
        combine(db.documents().observeModules(), db.documents().observeWeekStatus(), ::modulesWithSegments)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Re-runs indexing for every document, e.g. after a change to extraction or chunking. */
    fun reindexAll() {
        viewModelScope.launch {
            val ids = db.documents().allIds()
            ids.forEach(repo::enqueueIndexing)
            _message.value = "Re-indexing ${ids.size} documents"
        }
    }

    /** Debug hand-off to ml/: writes chunks.json to the app's external files dir for `adb pull`. */
    fun exportChunks() {
        viewModelScope.launch {
            _message.value = try {
                val app = getApplication<Application>()
                val (file, count) = withContext(Dispatchers.IO) {
                    val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
                    val chunks = db.chunks().allReady()
                    val json = ChunkExporter.toJson(version, db.documents().allReady(), chunks)
                    val out = File(requireNotNull(app.getExternalFilesDir(null)), "chunks.json")
                    out.writeText(json)
                    out to chunks.size
                }
                "Exported $count chunks to ${file.absolutePath}"
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
        }
    }

    fun messageShown() {
        _message.value = null
    }
}
