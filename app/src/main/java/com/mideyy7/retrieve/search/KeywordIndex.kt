package com.mideyy7.retrieve.search

import com.mideyy7.retrieve.data.Chunk
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.nativebridge.NativeBridge
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class KeywordHit(val stableKey: String, val score: Float)

/**
 * Safe wrapper around a native index handle. Searches take a read lock (many can run at once;
 * the native index is immutable), close() takes the write lock, so a handle is never freed while
 * a search is using it, and is never used after it has been freed.
 */
class KeywordIndex private constructor(private var handle: Long) : Closeable {
    private val lock = ReentrantReadWriteLock()

    val stats: IndexStats = NativeBridge.indexStats(handle).let { IndexStats(it[0].toInt(), it[1].toInt(), it[2]) }

    fun search(query: String, k: Int): List<KeywordHit> = lock.read {
        check(handle != 0L) { "index is closed" }
        val hits = NativeBridge.search(handle, query, k)
        hits.stableKeys.indices.map { KeywordHit(hits.stableKeys[it], hits.scores[it]) }
    }

    override fun close() = lock.write {
        if (handle != 0L) {
            NativeBridge.closeIndex(handle)
            handle = 0L
        }
    }

    data class IndexStats(val chunks: Int, val terms: Int, val fileBytes: Long)

    companion object {
        @Throws(IOException::class)
        fun open(file: File): KeywordIndex = KeywordIndex(NativeBridge.openIndex(file.absolutePath))

        /** Builds [file] from [chunks] (atomic replace). Order of [chunks] doesn't matter. */
        @Throws(IOException::class)
        fun build(file: File, chunks: List<Chunk>, typeOf: (Chunk) -> DocType) {
            file.parentFile?.mkdirs()
            val error = NativeBridge.buildIndex(
                path = file.absolutePath,
                stableKeys = Array(chunks.size) { chunks[it].stableKey },
                modules = Array(chunks.size) { chunks[it].module },
                weeks = IntArray(chunks.size) { chunks[it].week },
                types = IntArray(chunks.size) { typeOf(chunks[it]).ordinal },
                pages = IntArray(chunks.size) { chunks[it].page ?: -1 },
                startMs = LongArray(chunks.size) { chunks[it].startMs ?: -1L },
                texts = Array(chunks.size) { chunks[it].text },
            )
            if (error != null) throw IOException("index build failed: $error")
        }
    }
}
