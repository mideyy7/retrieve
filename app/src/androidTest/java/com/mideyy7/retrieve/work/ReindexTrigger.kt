package com.mideyy7.retrieve.work

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mideyy7.retrieve.retrieveApp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a test: a hook for scripts/resume_check.sh and scripts/battery_run.sh. Queues re-indexing for
 * the documents named by `-e docs` (comma-separated ids, or "largest" for the biggest PDF, or "all")
 * and writes the chosen ids to resume_check.txt, then returns while the work runs in the app.
 * `-e embed all` instead clears every embedding and queues the whole library to be embedded again.
 */
@RunWith(AndroidJUnit4::class)
class ReindexTrigger {
    @Test
    fun reembed() = runBlocking {
        assumeTrue("pass -e embed all", InstrumentationRegistry.getArguments().getString("embed") == "all")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.retrieveApp
        app.database.chunks().clearEmbeddings()
        EmbedWorker.enqueue(app)
    }

    @Test
    fun queue() = runBlocking {
        val which = InstrumentationRegistry.getArguments().getString("docs")
        assumeTrue("pass -e docs <ids|largest|all>", which != null)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.retrieveApp
        val docs = app.database.documents().allReady()
        val ids = when (which) {
            "all" -> docs.map { it.id }
            "largest" -> listOfNotNull(docs.maxByOrNull { app.database.chunks().countForDoc(it.id) }?.id)
            else -> which!!.split(',').map { it.trim().toLong() }
        }
        ids.forEach(app.imports::enqueueIndexing)
        File(requireNotNull(app.getExternalFilesDir(null)), "resume_check.txt").writeText(ids.joinToString(","))
    }
}
