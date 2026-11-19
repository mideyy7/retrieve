package com.mideyy7.retrieve.ui.library

import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.data.DocumentSummary
import com.mideyy7.retrieve.ingest.ImportRepository
import com.mideyy7.retrieve.work.IndexDocumentWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class Progress(val done: Int, val total: Int)

data class DocumentRow(val summary: DocumentSummary, val progress: Progress?)

/** Joins document rows with the page progress of their running indexing workers. */
fun Flow<List<DocumentSummary>>.withIndexingProgress(workManager: WorkManager): Flow<List<DocumentRow>> =
    combine(workManager.getWorkInfosByTagFlow(ImportRepository.TAG_INDEXING)) { docs, infos ->
        val progress = infos
            .filter { it.state == WorkInfo.State.RUNNING }
            .mapNotNull { info ->
                val id = ImportRepository.docIdFromTags(info.tags) ?: return@mapNotNull null
                val total = info.progress.getInt(IndexDocumentWorker.KEY_TOTAL, 0)
                id to Progress(info.progress.getInt(IndexDocumentWorker.KEY_DONE, 0), total)
            }
            .toMap()
        docs.map { DocumentRow(it, progress[it.id]) }
    }

fun DocType.label() = when (this) {
    DocType.SLIDES -> "Slides"
    DocType.PDF -> "PDF"
    DocType.AUDIO -> "Recording"
}
