package com.mideyy7.retrieve.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mideyy7.retrieve.data.ChunkExporter
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.data.DocumentSummary
import com.mideyy7.retrieve.data.RetrieveDatabase
import com.mideyy7.retrieve.ingest.FilenameTagParser
import com.mideyy7.retrieve.ingest.FilenameTags
import com.mideyy7.retrieve.ingest.ImportRepository
import com.mideyy7.retrieve.ingest.ImportResult
import com.mideyy7.retrieve.work.IndexDocumentWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Progress(val done: Int, val total: Int)

data class DocumentRow(val summary: DocumentSummary, val progress: Progress?)

/** A picked file waiting for the user to confirm its module/week/type. */
data class PendingImport(val uri: Uri, val name: String, val guess: FilenameTags)

/**
 * Screen state lives here rather than in the composables: a ViewModel survives configuration
 * changes (rotation, dark-mode switch) that destroy and recreate the Activity and its UI.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val db = RetrieveDatabase.get(app)
    private val workManager = WorkManager.getInstance(app)
    private val repo = ImportRepository(app, db, workManager)

    val documents: StateFlow<List<DocumentRow>> =
        combine(
            db.documents().observeSummaries(),
            workManager.getWorkInfosByTagFlow(ImportRepository.TAG_INDEXING),
        ) { docs, infos ->
            val progress = infos
                .filter { it.state == WorkInfo.State.RUNNING }
                .mapNotNull { info ->
                    val id = ImportRepository.docIdFromTags(info.tags) ?: return@mapNotNull null
                    val total = info.progress.getInt(IndexDocumentWorker.KEY_TOTAL, 0)
                    id to Progress(info.progress.getInt(IndexDocumentWorker.KEY_DONE, 0), total)
                }
                .toMap()
            docs.map { DocumentRow(it, progress[it.id]) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _pending = MutableStateFlow<List<PendingImport>>(emptyList())
    val pending: StateFlow<List<PendingImport>> = _pending.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun onFilesPicked(uris: List<Uri>) {
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) {
                uris.map { uri -> repo.displayName(uri).let { PendingImport(uri, it, FilenameTagParser.parse(it)) } }
            }
            _pending.update { it + picked }
        }
    }

    fun confirmImport(item: PendingImport, module: String, week: Int, type: DocType) {
        _pending.update { it - item }
        viewModelScope.launch {
            _message.value = try {
                when (val r = repo.import(item.uri, item.name, module, week, type)) {
                    is ImportResult.Queued -> "Importing ${item.name}"
                    is ImportResult.AlreadyImported -> "Already imported as ${r.name}"
                }
            } catch (e: Exception) {
                "Couldn't import ${item.name}: ${e.message}"
            }
        }
    }

    fun skipImport(item: PendingImport) = _pending.update { it - item }

    fun delete(docId: Long) {
        viewModelScope.launch {
            workManager.cancelUniqueWork(IndexDocumentWorker.uniqueName(docId))
            db.documents().delete(docId)
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
