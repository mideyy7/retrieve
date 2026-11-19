package com.mideyy7.retrieve.search

/**
 * Reciprocal rank fusion, exactly as ml/eval.py's rrf() that produced the hybrid recall numbers:
 * every occurrence of a location at 1-based rank r in any list adds 1/(k + r); locations are ordered
 * by fused score descending, then by key ascending; the top [limit] are kept. RrfTest checks this
 * against the fixture generated from the Python implementation.
 *
 * Fusion is over *locations* (document + page, or document + start time), not chunks, because that
 * is what the evaluation labels and what a student wants: "slide 22", not "chunk 2 of slide 22".
 * Keys must sort like the Python tuples (doc sha, page): use [locationKey].
 */
object Rrf {
    const val K = 60
    const val LIMIT = 10

    fun fuse(lists: List<List<String>>, k: Int = K, limit: Int = LIMIT): List<Pair<String, Double>> {
        val scores = HashMap<String, Double>()
        for (list in lists) {
            list.forEachIndexed { i, loc -> scores[loc] = (scores[loc] ?: 0.0) + 1.0 / (k + i + 1) }
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key to it.value }
    }

    /** Zero-padded so string order equals the evaluation's (sha, page) tuple order. */
    fun locationKey(docSha256: String, page: Int?, startMs: Long?): String =
        if (page != null) "%s:p%06d".format(docSha256, page) else "%s:t%012d".format(docSha256, startMs ?: 0L)
}
