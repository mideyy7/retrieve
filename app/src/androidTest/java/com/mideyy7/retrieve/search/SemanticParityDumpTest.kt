package com.mideyy7.retrieve.search

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
 * Dumps the device's semantic top-5 (embeddings computed on the device for both chunks and
 * questions) for the 100 fixed questions, for scripts/semantic_parity.py to compare with
 * ml/eval.py's ranking from the same .tflite on the laptop. Skipped until EmbedWorker has
 * embedded the library.
 */
@RunWith(AndroidJUnit4::class)
class SemanticParityDumpTest {
    @Test
    fun dumpSemanticTop5() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.retrieveApp
        app.semanticIndex.reload()
        assumeTrue("library not embedded yet: open the app and wait", app.semanticIndex.size > 0)
        val fixture = JSONObject(instrumentation.context.assets.open("embed_fixture.json").bufferedReader().readText())
        val questions = fixture.getJSONArray("latency_inputs")
        val out = File(requireNotNull(app.getExternalFilesDir(null)), "semantic_parity.jsonl")
        out.bufferedWriter().use { w ->
            for (i in 0 until questions.length()) {
                val q = questions.getString(i)
                val hits = app.semanticIndex.search(app.embedder.embed(q), 5)
                val arr = JSONArray()
                hits.forEach { arr.put(JSONObject().put("key", it.stableKey).put("score", it.score.toDouble())) }
                w.write(JSONObject().put("question", q).put("indexed_chunks", app.semanticIndex.size).put("hits", arr).toString())
                w.newLine()
            }
        }
    }
}
