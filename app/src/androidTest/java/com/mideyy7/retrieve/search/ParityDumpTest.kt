package com.mideyy7.retrieve.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a pass/fail test on its own: runs bench/parity_queries.txt against the app's real index on
 * the device and writes the results to the app's external files dir. scripts/parity_check.py
 * pulls that file and compares it with retrieve_cli on the same corpus.
 */
@RunWith(AndroidJUnit4::class)
class ParityDumpTest {
    @Test
    fun dumpSearchResultsForParityCheck() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val indexFile = File(app.filesDir, "index/keyword.idx")
        assumeTrue("no index on device yet; open the app once first", indexFile.exists())

        val queries = instrumentation.context.assets.open("parity_queries.txt").bufferedReader().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        val out = File(requireNotNull(app.getExternalFilesDir(null)), "parity_results.jsonl")
        KeywordIndex.open(indexFile).use { idx ->
            out.bufferedWriter().use { w ->
                for (q in queries) {
                    val hits = JSONArray()
                    idx.search(q, 10).forEach { hits.put(JSONObject().put("key", it.stableKey).put("score", it.score.toDouble())) }
                    w.write(JSONObject().put("query", q).put("hits", hits).toString())
                    w.newLine()
                }
            }
        }
    }
}
