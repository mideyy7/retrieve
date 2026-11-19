package com.mideyy7.retrieve.search

/** A window of chunk text around the first query-term match, with the ranges of matched words. */
data class Snippet(val text: String, val highlights: List<IntRange>)

object Snippets {
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** Lowercased query words, ignoring quotes and punctuation (display only; ranking is native). */
    fun queryTerms(query: String): Set<String> =
        WORD.findAll(query.lowercase()).map { it.value }.filter { it.length > 1 || it.first().isDigit() }.toSet()

    fun make(text: String, terms: Set<String>, maxChars: Int = 220): Snippet {
        val words = WORD.findAll(text).toList()
        val first = words.firstOrNull { it.value.lowercase() in terms }
        val start = if (first == null || first.range.first < maxChars / 3) {
            0
        } else {
            // Start a little before the match, at a word boundary.
            val target = first.range.first - maxChars / 3
            words.firstOrNull { it.range.first >= target }?.range?.first ?: 0
        }
        val end = minOf(text.length, start + maxChars)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        val body = text.substring(start, end)
        val highlights = words
            .filter { it.range.first >= start && it.range.last < end && it.value.lowercase() in terms }
            .map { (it.range.first - start + prefix.length)..(it.range.last - start + prefix.length) }
        return Snippet(prefix + body + suffix, highlights)
    }
}
