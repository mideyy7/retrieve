package com.mideyy7.retrieve.ingest

import androidx.work.WorkManager
import com.mideyy7.retrieve.data.DocStatus
import com.mideyy7.retrieve.data.ModuleCodes
import com.mideyy7.retrieve.data.RetrieveDatabase
import com.mideyy7.retrieve.data.Weeks
import com.mideyy7.retrieve.search.KeywordIndexStore
import com.mideyy7.retrieve.work.IndexDocumentWorker

/**
 * Library management: delete, retry, rename and move. Anything that changes which chunks exist or
 * their module/week rebuilds the keyword index afterwards, because `module:` and `week:` filters
 * are resolved from the values the index was built with.
 */
class LibraryRepository(
    private val db: RetrieveDatabase,
    private val workManager: WorkManager,
    private val imports: ImportRepository,
    private val index: KeywordIndexStore,
) {
    suspend fun delete(docId: Long) {
        workManager.cancelUniqueWork(IndexDocumentWorker.uniqueName(docId))
        db.documents().delete(docId)
        index.rebuild()
    }

    /** Re-runs indexing for a FAILED document from the file it was imported from. */
    suspend fun retry(docId: Long) {
        db.documents().setStatus(docId, DocStatus.PENDING, null)
        imports.enqueueIndexing(docId)
    }

    suspend fun renameDocument(docId: Long, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Name can't be empty" }
        db.organize().renameDocument(docId, trimmed)
    }

    /** [module] must already be normalised with [ModuleCodes.normalize]. */
    suspend fun moveDocument(docId: Long, module: String, week: Int) {
        require(ModuleCodes.normalize(module) == module) { "Invalid module code" }
        require(Weeks.isValid(week)) { "Week must be 0 to ${Weeks.MAX}" }
        db.organize().moveDocument(docId, module, week)
        index.rebuild()
    }

    /** Whether renaming to [code] would merge into a module that already has documents. */
    suspend fun moduleExists(code: String) = db.organize().documentCount(code) > 0

    suspend fun renameModule(from: String, to: String) {
        require(ModuleCodes.normalize(to) == to) { "Invalid module code" }
        db.organize().renameModule(from, to)
        index.rebuild()
    }

    suspend fun setModuleTitle(code: String, title: String?) = db.modules().setTitle(code, title)
}
