package com.mideyy7.retrieve.qa

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Gemma 3 1B (int4) through LiteRT-LM on the CPU (the GPU path ran out of memory on the dev tablet,
 * docs/NOTES.md Spike 1). The engine is loaded on first use, which takes tens of seconds and ~600 MB,
 * and must be [close]d when the Ask screen goes away. Each question gets a fresh conversation, so
 * answers don't leak into each other.
 */
// enableBenchmark and getBenchmarkInfo are experimental in LiteRT-LM 0.19; used only for the timing line.
@OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
class LiteRtGenerator(private val context: Context) : Generator, AutoCloseable {
    private val lock = Mutex()
    private var engine: Engine? = null

    /** How long the last engine load took, for the stats line. Null once reported. */
    private var pendingLoadMs: Long? = null

    val isLoaded: Boolean get() = engine != null

    override fun generate(system: String, user: String, maxOutputTokens: Int): Flow<Generation> = flow {
        lock.withLock {
            val e = engine ?: load().also { engine = it }
            val config = ConversationConfig(
                systemInstruction = Contents.of(system),
                // Greedy decoding (top-k 1): the same question gives the same answer, which the
                // evaluation relies on, and small models drift less from the excerpts.
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 1234),
                maxOutputToken = maxOutputTokens,
            )
            e.createConversation(config).use { conversation ->
                val start = SystemClock.elapsedRealtime()
                var firstAt = -1L
                var pieces = 0
                val text = StringBuilder()
                conversation.sendMessageAsync(user).collect { piece ->
                    if (firstAt < 0) firstAt = SystemClock.elapsedRealtime()
                    pieces++
                    text.append(piece.toString())
                    emit(Generation(text.toString(), done = false))
                }
                val end = SystemClock.elapsedRealtime()
                emit(Generation(text.toString(), done = true, stats = stats(conversation, start, firstAt, end, pieces)))
            }
        }
    }.flowOn(Dispatchers.Default)

    private fun load(): Engine {
        val model = checkNotNull(LlmModel.verifiedFile(context)) { "The answering model isn't installed." }
        ExperimentalFlags.enableBenchmark = true
        val t0 = SystemClock.elapsedRealtime()
        val e = Engine(EngineConfig(modelPath = model.absolutePath, backend = Backend.CPU(), cacheDir = context.cacheDir.path))
        e.initialize()
        pendingLoadMs = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "loaded ${model.name} in ${pendingLoadMs} ms")
        return e
    }

    /** The runtime's own measurements when available, otherwise wall-clock timing of the stream. */
    private fun stats(c: com.google.ai.edge.litertlm.Conversation, start: Long, firstAt: Long, end: Long, pieces: Int): GenerationStats {
        val loadMs = pendingLoadMs.also { pendingLoadMs = null }
        val info = runCatching { c.getBenchmarkInfo() }.getOrNull()
        return if (info != null && info.lastDecodeTokenCount > 0) {
            GenerationStats(
                ttftMs = (info.timeToFirstTokenInSecond * 1000).toLong(),
                decodeTokensPerSecond = info.lastDecodeTokensPerSecond,
                prefillTokens = info.lastPrefillTokenCount,
                decodeTokens = info.lastDecodeTokenCount,
                loadMs = loadMs,
            )
        } else {
            val decodeS = (end - firstAt).coerceAtLeast(1) / 1000.0
            GenerationStats(
                ttftMs = if (firstAt >= 0) firstAt - start else end - start,
                decodeTokensPerSecond = if (pieces > 1) (pieces - 1) / decodeS else 0.0,
                prefillTokens = -1,
                decodeTokens = pieces,
                loadMs = loadMs,
            )
        }
    }

    /**
     * Frees the model's memory. Doesn't block: it waits in the background for a running answer to
     * stop (its collector is cancelled when the screen goes away), then closes the engine.
     */
    override fun close() {
        cleanup.launch {
            lock.withLock {
                engine?.close()
                engine = null
                Log.i(TAG, "released")
            }
        }
    }

    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private companion object {
        const val TAG = "LiteRtGenerator"
    }
}
