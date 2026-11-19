package com.mideyy7.retrieve.search

import com.mideyy7.retrieve.data.ChunkWithDoc
import com.mideyy7.retrieve.data.RetrieveDatabase
import com.mideyy7.retrieve.nativebridge.QuerySyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class MatchedBy { KEYWORD, SEMANTIC, BOTH }

/** [score] is BM25 for keyword-only queries and the RRF fusion score when [fused] (hybrid). */
data class SearchResult(
    val hit: ChunkWithDoc,
    val score: Float,
    val matchedBy: MatchedBy = MatchedBy.KEYWORD,
    val fused: Boolean = false,
)

sealed interface SearchOutcome {
    /**
     * [topSimilarity] is the best cosine similarity between the query and any chunk (hybrid only).
     * Q&A uses it to decide whether the library can answer at all (ml/refusal.py).
     */
    data class Results(val results: List<SearchResult>, val hybrid: Boolean = false, val topSimilarity: Float? = null) : SearchOutcome
    data class SyntaxError(val message: String) : SearchOutcome
}

/**
 * Query -> results. Plain questions use hybrid ranking: keyword top-10 (C++ BM25) and semantic
 * top-10 (on-device embeddings), fused by location with RRF, exactly as ml/eval.py measured
 * (docs/results/recall.csv). Query-language queries (quotes, brackets, filters, AND/OR/NOT) stay
 * keyword-only: the semantic side can't yet honour NOT, phrases or filters.
 */
class SearchRepository(
    private val db: RetrieveDatabase,
    private val keyword: KeywordIndexStore,
    private val semantic: SemanticIndex? = null,
    private val embed: ((String) -> FloatArray)? = null,
) {
    suspend fun search(query: String, k: Int = 20): SearchOutcome = withContext(Dispatchers.Default) {
        if (isPlain(query) && semantic != null && embed != null && semantic.size > 0) {
            return@withContext hybrid(query)
        }
        val hits = try {
            keyword.search(query, k)
        } catch (e: QuerySyntaxException) {
            return@withContext SearchOutcome.SyntaxError(e.message ?: "invalid query")
        }
        if (hits.isEmpty()) return@withContext SearchOutcome.Results(emptyList())
        val byKey = rows(hits.map { it.stableKey })
        // A hit can be missing from Room if a document was deleted after the index was built; skip it.
        SearchOutcome.Results(hits.mapNotNull { h -> byKey[h.stableKey]?.let { SearchResult(it, h.score) } })
    }

    private suspend fun hybrid(query: String): SearchOutcome {
        val kw = try {
            keyword.search(query, Rrf.LIMIT)
        } catch (e: QuerySyntaxException) {
            return SearchOutcome.SyntaxError(e.message ?: "invalid query")
        }
        val semAll = semantic!!.search(embed!!(query), Rrf.LIMIT)
        // Meaning-only matches below the threshold are noise for a question the library doesn't
        // cover ("raft" listed ten unrelated intro slides). Keyword matches are never dropped.
        // ml/weak_filter.py: dev recall@5 unchanged, test 0.868 -> 0.860, recall@1 0.545 -> 0.570.
        val sem = semAll.filter { it.score >= MIN_SIMILARITY }
        val byKey = rows(kw.map { it.stableKey } + sem.map { it.stableKey })
        fun loc(key: String) = byKey[key]?.let { Rrf.locationKey(it.docSha, it.chunk.page, it.chunk.startMs) }
        val kwLocs = kw.mapNotNull { loc(it.stableKey) }
        val semLocs = sem.mapNotNull { loc(it.stableKey) }
        // Each fused location is shown through its best-ranked chunk (earliest in either list).
        val representative = HashMap<String, Pair<Int, ChunkWithDoc>>()
        for (list in listOf(kw.map { it.stableKey }, sem.map { it.stableKey })) {
            list.forEachIndexed { rank, key ->
                val row = byKey[key] ?: return@forEachIndexed
                val l = loc(key)!!
                val current = representative[l]
                if (current == null || rank < current.first) representative[l] = rank to row
            }
        }
        val kwSet = kwLocs.toSet()
        val semSet = semLocs.toSet()
        val results = Rrf.fuse(listOf(kwLocs, semLocs)).mapNotNull { (l, score) ->
            val row = representative[l]?.second ?: return@mapNotNull null
            val by = when {
                l in kwSet && l in semSet -> MatchedBy.BOTH
                l in kwSet -> MatchedBy.KEYWORD
                else -> MatchedBy.SEMANTIC
            }
            SearchResult(row, score.toFloat(), by, fused = true)
        }
        return SearchOutcome.Results(results, hybrid = true, topSimilarity = semAll.firstOrNull()?.score)
    }

    private suspend fun rows(keys: List<String>) =
        db.chunks().withDocByStableKeys(keys.distinct()).associateBy { it.chunk.stableKey }

    companion object {
        /**
         * The best question-to-chunk cosine below which the library almost certainly doesn't cover a
         * question. Tuned on the dev split only by ml/refusal.py (docs/results/refusal.csv).
         */
        const val MIN_SIMILARITY = 0.4768f

        private val FILTER = Regex("(?<![^\\s(])[A-Za-z]+:\\S")
        private val OPERATOR = Regex("(^|\\s)(AND|OR|NOT)(\\s|$)")

        /** True for an ordinary question: no quotes, brackets, filters or upper-case operators. */
        fun isPlain(query: String): Boolean =
            query.isNotBlank() && query.none { it == '"' || it == '(' || it == ')' || it == '\u201C' || it == '\u201D' } &&
                !FILTER.containsMatchIn(query) && !OPERATOR.containsMatchIn(query)
    }
}
