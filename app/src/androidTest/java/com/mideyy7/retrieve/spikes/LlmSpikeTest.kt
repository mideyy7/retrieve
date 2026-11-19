package com.mideyy7.retrieve.spikes

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Spike 1 (docs/IMPLEMENTATION_PLAN.md, Phase 0): can the test tablet run an on-device LLM at all,
 * and how fast? Not a pass/fail test: it is skipped unless a model was pushed and named via
 * instrumentation arguments, and it writes its measurements to llm_spike.jsonl for the spike script.
 *
 *   spikes/spike1_llm_device.sh gemma3-1b-it-int4.litertlm cpu
 */
@RunWith(AndroidJUnit4::class)
class LlmSpikeTest {

    @Test
    fun loadAndAnswerOneQuestion() {
        val args = InstrumentationRegistry.getArguments()
        val modelName = args.getString("model")
        assumeTrue("pass -e model <file> (see spikes/spike1_llm_device.sh)", modelName != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(requireNotNull(context.getExternalFilesDir(null)), "llm")
        val model = File(dir, modelName!!)
        assumeTrue("model not found at $model", model.exists())
        val backendName = args.getString("backend", "cpu")

        val result = JSONObject()
            .put("model", modelName)
            .put("model_mb", model.length() / 1_000_000)
            .put("backend", backendName)
            .put("avail_mem_mb_before", availMemMb(context))

        val prompt = """
            Answer using only the lecture excerpt, in one sentence, and cite it as [COMP21111 wk4 slide 22].
            Excerpt [COMP21111 wk4 slide 22]: Unit propagation. If S contains a unit clause, i.e. a clause
            consisting of one literal L, then remove from S every clause containing L, and remove the
            complement of L from every other clause. Unit propagation is satisfiability preserving.
            Question: what does DPLL do when a clause has only one literal left?
        """.trimIndent()

        val loadStart = SystemClock.elapsedRealtime()
        val config = EngineConfig(
            modelPath = model.absolutePath,
            backend = if (backendName == "gpu") Backend.GPU() else Backend.CPU(),
            cacheDir = context.cacheDir.path,
        )
        Engine(config).use { engine ->
            engine.initialize()
            result.put("load_ms", SystemClock.elapsedRealtime() - loadStart)
            result.put("avail_mem_mb_after_load", availMemMb(context))

            engine.createConversation().use { conversation ->
                val start = SystemClock.elapsedRealtime()
                var firstAt = -1L
                var pieces = 0
                val text = StringBuilder()
                runBlocking {
                    conversation.sendMessageAsync(prompt).collect { piece ->
                        if (firstAt < 0) firstAt = SystemClock.elapsedRealtime()
                        pieces++
                        text.append(piece.toString())
                    }
                }
                val end = SystemClock.elapsedRealtime()
                val decodeS = (end - firstAt).coerceAtLeast(1) / 1000.0
                result.put("ttft_ms", firstAt - start)
                    .put("streamed_pieces", pieces)
                    // Pieces approximate tokens (one streamed piece per decode step in LiteRT-LM).
                    .put("decode_pieces_per_s", if (pieces > 1) (pieces - 1) / decodeS else 0.0)
                    .put("total_ms", end - start)
                    .put("answer", text.toString().trim())
            }
        }
        result.put("avail_mem_mb_end", availMemMb(context))
        File(context.getExternalFilesDir(null), "llm_spike.jsonl").appendText(result.toString() + "\n")
    }

    private fun availMemMb(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.availMem / 1_000_000
    }
}
