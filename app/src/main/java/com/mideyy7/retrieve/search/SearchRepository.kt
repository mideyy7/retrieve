package com.mideyy7.retrieve.search

import com.mideyy7.retrieve.data.ChunkWithDoc
import com.mideyy7.retrieve.data.RetrieveDatabase
import com.mideyy7.retrieve.nativebridge.QuerySyntaxException

data class SearchResult(val hit: ChunkWithDoc, val score: Float)

sealed interface SearchOutcome {
    data class Results(val results: List<SearchResult>) : SearchOutcome
    data class SyntaxError(val message: String) : SearchOutcome
}

/** Query -> native keyword search -> chunks from Room, in rank order. Semantic hits join in Phase 5. */
class SearchRepository(private val db: RetrieveDatabase, private val index: KeywordIndexStore) {

    suspend fun search(query: String, k: Int = 20): SearchOutcome {
        val hits = try {
            index.search(query, k)
        } catch (e: QuerySyntaxException) {
            return SearchOutcome.SyntaxError(e.message ?: "invalid query")
        }
        if (hits.isEmpty()) return SearchOutcome.Results(emptyList())
        val byKey = db.chunks().withDocByStableKeys(hits.map { it.stableKey }).associateBy { it.chunk.stableKey }
        // A hit can be missing from Room if a document was deleted after the index was built; skip it.
        return SearchOutcome.Results(hits.mapNotNull { h -> byKey[h.stableKey]?.let { SearchResult(it, h.score) } })
    }
}
