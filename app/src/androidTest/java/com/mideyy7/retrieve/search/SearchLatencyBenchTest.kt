package com.mideyy7.retrieve.search

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mideyy7.retrieve.retrieveApp
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 7 latency benchmark (spec §6): the warm index, 20 warm-up queries, then every query in the
 * pushed workload, each timed end to end on the device. Two paths are timed per query:
 *  - keyword: Kotlin -> JNI -> C++ parse/plan/BM25 top 20 -> Kotlin hits (the CV bullet's number);
 *  - app: SearchRepository.search, i.e. what the Search screen waits for (hybrid for plain
 *    questions: embedding the query, the semantic scan, fusion and the Room lookup).
 * Writes one JSON line per query to search_latency.jsonl; scripts/search_latency.sh computes P50/P99.
 * Skipped unless `-e queries <file>` names a pushed workload file.
 */
@RunWith(AndroidJUnit4::class)
class SearchLatencyBenchTest {
    @Test
    fun timeQueries() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.retrieveApp
        val base = requireNotNull(app.getExternalFilesDir(null))
        val input = InstrumentationRegistry.getArguments().getString("queries")?.let { File(base, it) }
        assumeTrue("pass -e queries <file> (see scripts/search_latency.sh)", input?.isFile == true)
        app.keywordIndex.openThenRebuild()
        app.semanticIndex.reload()
        assumeTrue("library not indexed and embedded yet", app.semanticIndex.size > 0)
        val repo = SearchRepository(app.database, app.keywordIndex, app.semanticIndex, { app.embedder.embed(it) })
        val queries = input!!.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        // Warm-up: JIT, page cache, the embedding interpreter.
        queries.take(WARMUP).forEach { q ->
            runCatching { app.keywordIndex.search(q, 20) }
            repo.search(q)
        }
        val out = File(base, "search_latency.jsonl")
        out.writeText("")
        val device = JSONObject().put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("soc", Build.SOC_MODEL)
            .put("android", Build.VERSION.RELEASE).put("chunks", app.semanticIndex.size)
        for (q in queries) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val kwHits = runCatching { app.keywordIndex.search(q, 20).size }.getOrDefault(-1)
            val t1 = SystemClock.elapsedRealtimeNanos()
            val outcome = repo.search(q)
            val t2 = SystemClock.elapsedRealtimeNanos()
            val hybrid = (outcome as? SearchOutcome.Results)?.hybrid == true
            out.appendText(
                JSONObject(device.toString()).put("query", q).put("keyword_ns", t1 - t0).put("keyword_hits", kwHits)
                    .put("app_ns", t2 - t1).put("app_hybrid", hybrid).toString() + "\n",
            )
        }
    }

    private companion object {
        const val WARMUP = 20
    }
}
