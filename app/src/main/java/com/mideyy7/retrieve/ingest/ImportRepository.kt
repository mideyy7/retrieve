package com.mideyy7.retrieve.ingest

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mideyy7.retrieve.data.DocStatus
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.data.Document
import com.mideyy7.retrieve.data.RetrieveDatabase
import com.mideyy7.retrieve.work.IndexDocumentWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface ImportResult {
    data class Queued(val docId: Long) : ImportResult
    data class AlreadyImported(val docId: Long, val name: String) : ImportResult
}

class ImportRepository(
    private val context: Context,
    private val db: RetrieveDatabase,
    private val workManager: WorkManager,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "document.pdf"

    /**
     * Records the document and queues indexing. The file is identified by its SHA-256, so the same
     * file picked twice (even from a different folder) is detected. A previously FAILED import of
     * the same file is retried.
     */
    suspend fun import(uri: Uri, name: String, module: String, week: Int, type: DocType): ImportResult =
        withContext(Dispatchers.IO) {
            // SAF grants are temporary by default. Persisting the grant lets the background worker
            // (and any later re-index) read the file after this screen, or the whole app, has gone.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val sha = context.contentResolver.openInputStream(uri)?.use(::sha256Hex)
                ?: error("Cannot open $name")

            val newId = db.documents().insert(
                Document(
                    sha256 = sha, name = name, module = module.trim().uppercase(), week = week, type = type,
                    sourceUri = uri.toString(), importedAt = clock(),
                ),
            )
            if (newId != -1L) {
                enqueueIndexing(newId)
                return@withContext ImportResult.Queued(newId)
            }

            val existing = requireNotNull(db.documents().bySha(sha))
            if (existing.status == DocStatus.FAILED) {
                enqueueIndexing(existing.id)
                ImportResult.Queued(existing.id)
            } else {
                ImportResult.AlreadyImported(existing.id, existing.name)
            }
        }

    fun enqueueIndexing(docId: Long) {
        val request = OneTimeWorkRequestBuilder<IndexDocumentWorker>()
            .setInputData(workDataOf(IndexDocumentWorker.KEY_DOC_ID to docId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG_INDEXING)
            .addTag(docTag(docId)) // WorkInfo doesn't expose input data, so the UI finds the doc by tag
            .build()
        // KEEP: if this document is already queued or running, don't start a second copy.
        workManager.enqueueUniqueWork(IndexDocumentWorker.uniqueName(docId), ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val TAG_INDEXING = "indexing"
        private const val DOC_TAG_PREFIX = "doc-"

        fun docTag(docId: Long) = "$DOC_TAG_PREFIX$docId"

        fun docIdFromTags(tags: Set<String>): Long? =
            tags.firstOrNull { it.startsWith(DOC_TAG_PREFIX) }?.removePrefix(DOC_TAG_PREFIX)?.toLongOrNull()
    }
}

fun sha256Hex(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
