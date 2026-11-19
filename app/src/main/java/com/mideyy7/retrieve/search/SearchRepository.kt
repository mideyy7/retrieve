package com.mideyy7.retrieve.search

import com.mideyy7.retrieve.data.ChunkWithDoc
import com.mideyy7.retrieve.data.RetrieveDatabase

data class SearchResult(val hit: ChunkWithDoc, val score: Float)

/** Query -> native keyword search -> chunks from Room, in rank order. Semantic hits join in Phase 5. */
class SearchRepository(private val db: RetrieveDatabase, private val index: KeywordIndexStore) {

    suspend fun search(query: String, k: Int = 20): List<SearchResult> {
        val hits = index.search(query, k)
        if (hits.isEmpty()) return emptyList()
        val byKey = db.chunks().withDocByStableKeys(hits.map { it.stableKey }).associateBy { it.chunk.stableKey }
        // A hit can be missing from Room if a document was deleted after the index was built; skip it.
        return hits.mapNotNull { h -> byKey[h.stableKey]?.let { SearchResult(it, h.score) } }
    }
}
