package com.mideyy7.retrieve.search

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 5 on-device measurements (run via scripts/device_embed_bench.sh, which records the results
 * in docs/results/embed_latency_device.csv). For teacher fp32 (pushed to the device) and the bundled
 * student int8, on CPU and GPU:
 *  - parity: embeddings of 50 fixed inputs vs the laptop's LiteRT output for the same file;
 *  - latency: 20 warm-up runs, then the 100 fixed questions, model inference only (tokenization is
 *    timed separately); median, P95, P99.
 * Skipped unless run with -e embedBench true, so ordinary device-test runs stay quick.
 */
@RunWith(AndroidJUnit4::class)
class EmbedBenchTest {

    @Test
    fun benchmarkEmbeddingModels() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("run with -e embedBench true", args.getString("embedBench") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = JSONObject(instrumentation.context.assets.open("embed_fixture.json").bufferedReader().readText())
        val questions = fixture.getJSONArray("latency_inputs").strings()
        val parity = fixture.getJSONObject("parity")
        val parityInputs = parity.getJSONArray("inputs").strings()
        val teacherDir = File(requireNotNull(context.getExternalFilesDir(null)), "bench/teacher")
        val out = File(context.getExternalFilesDir(null), "embed_bench.jsonl").apply { delete() }

        val models = listOf(
            "student-l3-int8" to { acc: Accelerator -> TextEmbedder(context, acc) },
            "teacher-fp32" to { acc: Accelerator -> TextEmbedder.fromDirectory(teacherDir, acc) },
        )
        for ((name, factory) in models) {
            if (name == "teacher-fp32" && !File(teacherDir, "model.tflite").exists()) continue
            val expected = parity.getJSONArray(name)
            for (accelerator in listOf(Accelerator.CPU, Accelerator.GPU)) {
                val row = JSONObject()
                    .put("model", name).put("accelerator", accelerator.name)
                    .put("device", Build.MODEL).put("android", Build.VERSION.RELEASE).put("soc", Build.SOC_MODEL)
                val initStart = SystemClock.elapsedRealtime()
                val embedder = try {
                    factory(accelerator)
                } catch (e: Exception) {
                    out.appendText(row.put("error", "init failed: ${e.message}").toString() + "\n")
                    continue
                }
                embedder.use { m ->
                    row.put("init_ms", SystemClock.elapsedRealtime() - initStart).put("model_file", m.info.name)

                    // Parity with the laptop's LiteRT output for the same model file.
                    var minCos = 1.0
                    var maxAbs = 0.0
                    parityInputs.forEachIndexed { i, text ->
                        val got = m.embed(text)
                        val want = expected.getJSONArray(i)
                        var dot = 0.0
                        for (j in got.indices) {
                            dot += got[j] * want.getDouble(j)
                            maxAbs = maxOf(maxAbs, kotlin.math.abs(got[j] - want.getDouble(j)))
                        }
                        minCos = minOf(minCos, dot)
                    }
                    row.put("parity_inputs", parityInputs.size).put("parity_min_cos", minCos).put("parity_max_abs", maxAbs)

                    // Latency: tokenize everything first, then time inference only.
                    val tokStart = System.nanoTime()
                    val prepared = questions.map { m.tokenize(it) }
                    val tokenizeUsPerQuery = (System.nanoTime() - tokStart) / 1000.0 / questions.size
                    repeat(WARMUP) { m.runPrepared(prepared[it % prepared.size].first, prepared[it % prepared.size].second) }
                    val times = prepared.map { (ids, mask) ->
                        val t0 = System.nanoTime()
                        m.runPrepared(ids, mask)
                        (System.nanoTime() - t0) / 1e6
                    }.sorted()
                    row.put("runs", times.size).put("warmup", WARMUP)
                        .put("median_ms", times[times.size / 2]).put("p95_ms", pct(times, 0.95)).put("p99_ms", pct(times, 0.99))
                        .put("mean_ms", times.average()).put("tokenize_us_per_query", tokenizeUsPerQuery)
                }
                out.appendText(row.toString() + "\n")
            }
        }
    }

    private fun pct(sorted: List<Double>, p: Double) = sorted[minOf(sorted.size - 1, kotlin.math.ceil(p * sorted.size).toInt() - 1)]

    private fun JSONArray.strings() = List(length()) { getString(it) }

    private companion object {
        const val WARMUP = 20
    }
}
