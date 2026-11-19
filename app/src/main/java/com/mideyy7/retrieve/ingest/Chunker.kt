package com.mideyy7.retrieve.ingest

import kotlin.math.ceil

/** One chunk of a page. [page] is 1-based; [index] is the chunk's position within that page. */
data class PageChunk(val page: Int, val index: Int, val text: String)

/**
 * Splits page text into search chunks (spec §5.1): one chunk per page, and pages longer than
 * [maxTokens] are split into overlapping windows.
 *
 * "Token" here means a whitespace-separated word. This approximates the embedding model's
 * WordPiece tokens (which are usually a bit more numerous), and keeps the chunker independent of
 * any model. The embedding side truncates to its own limit.
 *
 * Long pages are split into k windows of equal size (not k-1 full windows plus a short leftover),
 * where k is the smallest count that keeps every window <= [maxTokens]. Neighbouring windows
 * share exactly [overlap] words so a sentence cut at a boundary still appears whole in one chunk.
 */
class Chunker(
    private val maxTokens: Int = 200,
    private val overlap: Int = 40,
    private val minLetters: Int = 3,
) {
    init {
        require(maxTokens > 0) { "maxTokens must be positive" }
        require(overlap in 0 until maxTokens) { "overlap must be in [0, maxTokens)" }
    }

    fun chunkPages(pages: List<String>): List<PageChunk> =
        pages.flatMapIndexed { i, text ->
            chunkText(text).mapIndexed { index, chunk -> PageChunk(page = i + 1, index = index, text = chunk) }
        }

    /**
     * Pages with fewer than [minLetters] letters (blank pages, pages that are only a page number
     * or a divider line) produce no chunks: they can't answer a question and would only add noise
     * terms like "9" to the index.
     */
    fun chunkText(text: String): List<String> {
        if (text.count { it.isLetter() } < minLetters) return emptyList()
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        val n = words.size
        if (n <= maxTokens) return listOf(words.joinToString(" "))

        val stride = maxTokens - overlap
        val k = ceil((n - overlap).toDouble() / stride).toInt()
        val size = ceil((n + (k - 1) * overlap).toDouble() / k).toInt()

        val chunks = mutableListOf<String>()
        var start = 0
        while (true) {
            val end = minOf(start + size, n)
            chunks += words.subList(start, end).joinToString(" ")
            if (end == n) break
            start = end - overlap
        }
        return chunks
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
