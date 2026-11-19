package com.mideyy7.retrieve.search

/** A window of chunk text around the first query-term match, with the ranges of matched words. */
data class Snippet(val text: String, val highlights: List<IntRange>)

object Snippets {
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    private val FILTER = Regex("(?<![\\p{L}\\p{N}])\\p{L}+:[^\\s()]+")
    private val NEGATED = Regex("\\bNOT\\s+(\"[^\"]*\"|[^\\s()]+)")
    private val OPERATOR = Regex("\\b(AND|OR|NOT)\\b")

    /**
     * Question and filler words. Marking every "is" and "what" in a passage hides the words that
     * matter, so they aren't highlighted (they still count for ranking, which is native).
     */
    private val STOPWORDS = setOf(
        "an", "the", "is", "are", "was", "were", "be", "been", "am", "do", "does", "did", "what", "which", "who",
        "whom", "how", "why", "when", "where", "of", "in", "on", "to", "for", "and", "or", "not", "it", "its",
        "this", "that", "these", "those", "with", "by", "from", "as", "at", "can", "there", "about", "into",
        "my", "me", "we", "you", "your", "if", "so", "than", "then",
    )

    /**
     * Lowercased query words, ignoring quotes and punctuation (display only; ranking is native).
     * Filters (`module:COMP21111`, `week:4..6`), the operators AND/OR/NOT and words the query
     * excludes (`NOT example`) aren't words to look for in the text, so they're left out, as are
     * [STOPWORDS].
     */
    fun queryTerms(query: String): Set<String> =
        WORD.findAll(query.replace(FILTER, " ").replace(NEGATED, " ").replace(OPERATOR, " ").lowercase())
            .map { it.value }.filter { (it.length > 1 || it.first().isDigit()) && it !in STOPWORDS }.toSet()

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
