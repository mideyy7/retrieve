package com.mideyy7.retrieve.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mideyy7.retrieve.retrieveApp
import com.mideyy7.retrieve.search.TextEmbedder
import kotlin.coroutines.cancellation.CancellationException

/**
 * Embeds every chunk of a READY document that has no embedding yet, in batches, then reloads the
 * semantic index. Idempotent: it only ever looks at chunks still missing a vector, so a killed or
 * repeated run just continues. Re-indexing a document deletes and re-inserts its chunks, which
 * clears their embeddings, so changed text is always re-embedded.
 */
class EmbedWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext.retrieveApp
        val dao = app.database.chunks()
        return try {
            val total = dao.countMissingEmbeddings()
            var done = 0
            while (true) {
                val batch = dao.missingEmbeddings(BATCH)
                if (batch.isEmpty()) break
                for (c in batch) {
                    dao.setEmbedding(c.id, TextEmbedder.toBytes(app.embedder.embed(c.text)))
                    done++
                }
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
            }
            app.semanticIndex.reload()
            Result.success(workDataOf(KEY_DONE to done))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure(workDataOf("error" to e.message))
        }
    }

    companion object {
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        private const val BATCH = 32
        private const val UNIQUE = "embed-chunks"

        /** APPEND_OR_REPLACE: a request made while one runs is queued after it, so no chunk is missed. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<EmbedWorker>().addTag("embedding").build(),
            )
        }
    }
}
