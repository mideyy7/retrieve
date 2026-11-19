package com.mideyy7.retrieve.work

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mideyy7.retrieve.data.DocStatus
import com.mideyy7.retrieve.data.Document
import com.mideyy7.retrieve.data.RetrieveDatabase
import com.mideyy7.retrieve.ingest.DocumentIndexer
import com.mideyy7.retrieve.ingest.IndexOutcome
import com.mideyy7.retrieve.ingest.MlKitPageOcr
import com.mideyy7.retrieve.ingest.OpenedPdf
import com.mideyy7.retrieve.ingest.PdfBoxTextSource
import com.mideyy7.retrieve.ingest.PermanentImportError
import com.mideyy7.retrieve.retrieveApp
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.FileNotFoundException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Background job that indexes one document. WorkManager persists the request, so it survives the
 * app being closed or the phone rebooting, and re-runs it if the process is killed mid-way.
 * Runs in the foreground with a progress notification (see [IndexingNotifications]); when the
 * app is in the background and Android refuses that, it carries on as an ordinary worker.
 * Indexing a document is idempotent (chunks have unique stable keys and are committed together
 * with READY), so a run killed mid-way simply starts the document again with no duplicates.
 */
class IndexDocumentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val docId = inputData.getLong(KEY_DOC_ID, -1)
        if (docId < 0) return Result.failure()

        val db = RetrieveDatabase.get(applicationContext)
        val name = db.documents().byId(docId)?.name ?: "file"
        goForeground(name, 0, 0)
        var lastShown = 0L
        val indexer = DocumentIndexer(
            db,
            openPdf = { doc -> openPdf(applicationContext, doc) },
            readCaptions = { doc -> readText(applicationContext, doc) },
        )

        return try {
            val outcome = indexer.index(docId) { done, total ->
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                // Update the notification at most twice a second; Android rate-limits it anyway.
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastShown > 500 || done == total) {
                    lastShown = now
                    goForeground(name, done, total)
                }
            }
            when (outcome) {
                is IndexOutcome.Indexed -> {
                    // Chunks are committed; make them searchable. A failed rebuild doesn't fail the
                    // document (its chunks are safe in Room) and is repaired at the next app start.
                    applicationContext.retrieveApp.keywordIndex.rebuild()
                    EmbedWorker.enqueue(applicationContext) // new chunks have no embeddings yet
                    Result.success(
                        workDataOf(KEY_PAGES to outcome.pages, KEY_OCR_PAGES to outcome.ocrPages, KEY_CHUNKS to outcome.chunks),
                    )
                }
                IndexOutcome.DocumentGone -> Result.success()
                is IndexOutcome.Failed -> Result.failure(workDataOf(KEY_ERROR to outcome.reason))
            }
        } catch (e: CancellationException) {
            throw e // WorkManager stopped us; it will reschedule, and indexing is idempotent.
        } catch (e: Exception) {
            if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                db.documents().setStatus(docId, DocStatus.FAILED, e.message ?: e.javaClass.simpleName)
                Result.failure(workDataOf(KEY_ERROR to e.message))
            } else {
                Result.retry()
            }
        }
    }

    private suspend fun goForeground(name: String, done: Int, total: Int) {
        try {
            setForeground(IndexingNotifications.foregroundInfo(applicationContext, name, done, total))
        } catch (e: IllegalStateException) {
            // Started while the app was in the background (Android 12+ forbids foreground services
            // then). The work still runs, within the ordinary worker time limit.
        } catch (e: SecurityException) {
            // Notification permission/foreground type not granted: same fallback.
        }
    }

    companion object {
        const val KEY_DOC_ID = "docId"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_PAGES = "pages"
        const val KEY_OCR_PAGES = "ocrPages"
        const val KEY_CHUNKS = "chunks"
        const val KEY_ERROR = "error"
        private const val MAX_ATTEMPTS = 3

        fun uniqueName(docId: Long) = "index-doc-$docId"

        /** Opens the text layer and the OCR renderer, mapping unrecoverable errors to [PermanentImportError]. */
        /** Reads a caption file as UTF-8, mapping unrecoverable errors to [PermanentImportError]. */
        fun readText(context: Context, doc: Document): String = try {
            context.contentResolver.openInputStream(Uri.parse(doc.sourceUri))?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw PermanentImportError("Cannot open ${doc.name}")
        } catch (e: FileNotFoundException) {
            throw PermanentImportError("${doc.name} is no longer available", e)
        } catch (e: SecurityException) {
            throw PermanentImportError("Permission to read ${doc.name} was revoked", e)
        }

        fun openPdf(context: Context, doc: Document): OpenedPdf {
            val uri = Uri.parse(doc.sourceUri)
            val text = try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw PermanentImportError("Cannot open ${doc.name}")
                PdfBoxTextSource(context, input)
            } catch (e: FileNotFoundException) {
                throw PermanentImportError("${doc.name} is no longer available", e)
            } catch (e: SecurityException) {
                throw PermanentImportError("Permission to read ${doc.name} was revoked", e)
            } catch (e: InvalidPasswordException) {
                throw PermanentImportError("${doc.name} is password protected", e)
            }
            val ocr = try {
                MlKitPageOcr(context, uri)
            } catch (e: Exception) {
                text.close()
                throw PermanentImportError("Cannot render ${doc.name}: ${e.message}", e)
            }
            return OpenedPdf(text, ocr) {
                ocr.close()
                text.close()
            }
        }
    }
}
