package com.mideyy7.retrieve.search

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** assets/embedding/model.json, written by ml/tools/install_app_model.py. */
@Serializable
data class EmbeddingModelInfo(
    val name: String,
    val sha256: String,
    val dim: Int,
    val max_len: Int,
    val vocab_size: Int,
    val size_bytes: Long,
)

/**
 * Text -> 384-d L2-normalised embedding with the bundled LiteRT model (the distilled int8 student).
 * Same tokenizer, padding and model file as ml/eval.py measured, so on-device vectors match the
 * evaluated ones. Not thread-safe internally (one set of tensor buffers), hence the lock.
 */
class TextEmbedder(context: Context, val accelerator: Accelerator = Accelerator.CPU) : Closeable {
    val info: EmbeddingModelInfo = Json.decodeFromString(
        EmbeddingModelInfo.serializer(),
        context.assets.open("$DIR/model.json").bufferedReader().readText(),
    )
    private val tokenizer = WordPieceTokenizer(
        context.assets.open("$DIR/vocab.txt").bufferedReader().readLines(),
        maxLen = info.max_len,
    )
    private val model = CompiledModel.create(context.assets, "$DIR/model.tflite", CompiledModel.Options(accelerator))
    private val inputs: List<TensorBuffer> = model.createInputBuffers()
    private val outputs: List<TensorBuffer> = model.createOutputBuffers()
    private val lock = Any()

    fun embed(text: String): FloatArray = synchronized(lock) {
        val (ids, mask) = tokenizer.encodePadded(text)
        // Signature order matches the export: args_0 = input ids, args_1 = attention mask (int32).
        inputs[0].writeInt(ids)
        inputs[1].writeInt(mask)
        model.run(inputs, outputs)
        outputs[0].readFloat()
    }

    /** Model inference only, for latency benchmarks that separate tokenization from the model. */
    fun runPrepared(ids: IntArray, mask: IntArray): FloatArray = synchronized(lock) {
        inputs[0].writeInt(ids)
        inputs[1].writeInt(mask)
        model.run(inputs, outputs)
        outputs[0].readFloat()
    }

    fun tokenize(text: String) = tokenizer.encodePadded(text)

    override fun close() = synchronized(lock) {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
    }

    companion object {
        const val DIR = "embedding"

        fun toBytes(v: FloatArray): ByteArray =
            ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(v) }.array()

        fun fromBytes(b: ByteArray): FloatArray =
            FloatArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }
}
