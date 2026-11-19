package com.mideyy7.retrieve.search

import com.mideyy7.retrieve.data.RetrieveDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SemanticHit(val stableKey: String, val score: Float)

/**
 * All chunk embeddings in one flat float array, searched by brute-force dot product (vectors are
 * L2-normalised, so dot = cosine). At this corpus size (hundreds to a few thousand chunks x 384)
 * that is well under a millisecond and needs no ANN index. Rows are ordered by stableKey, the same
 * order as chunks.json, so equal scores break ties exactly as ml/eval.py does.
 */
class SemanticIndex(private val db: RetrieveDatabase) {
    private class Snapshot(val keys: Array<String>, val vectors: FloatArray, val dim: Int)

    @Volatile private var snapshot: Snapshot? = null
    private val mutex = Mutex()

    val size: Int get() = snapshot?.keys?.size ?: 0

    suspend fun reload() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val rows = db.chunks().allEmbeddings()
            if (rows.isEmpty()) {
                snapshot = null
                return@withContext
            }
            val dim = rows[0].embedding.size / 4
            val vectors = FloatArray(rows.size * dim)
            rows.forEachIndexed { i, r -> TextEmbedder.fromBytes(r.embedding).copyInto(vectors, i * dim) }
            snapshot = Snapshot(Array(rows.size) { rows[it].stableKey }, vectors, dim)
        }
    }

    fun search(query: FloatArray, k: Int): List<SemanticHit> {
        val s = snapshot ?: return emptyList()
        require(query.size == s.dim) { "query has ${query.size} dims, index has ${s.dim}" }
        val scores = FloatArray(s.keys.size)
        for (row in s.keys.indices) {
            var dot = 0f
            val base = row * s.dim
            for (j in 0 until s.dim) dot += s.vectors[base + j] * query[j]
            scores[row] = dot
        }
        // Stable order: score descending, then row (stableKey) order.
        return s.keys.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
            .take(k)
            .map { SemanticHit(s.keys[it], scores[it]) }
    }
}
