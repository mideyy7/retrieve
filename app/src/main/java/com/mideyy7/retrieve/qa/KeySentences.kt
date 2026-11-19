package com.mideyy7.retrieve.qa

import kotlin.math.sqrt

/** A sentence copied from a passage, shown above the model's answer: it can't be made up. */
data class KeySentence(val text: String, val source: Source, val score: Float)

object KeySentences {
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+(?=[A-Z0-9“\"(])")
    private val BULLET = Regex("\\s*[•▪◦●]\\s*|\\s+-\\s+(?=[A-Z])")
    private const val MIN_CHARS = 25
    private const val MAX_CHARS = 240
    private const val WINDOW_WORDS = 30

    /**
     * A passage split into sentences. Slides are often bullet lists and captions often lack full
     * stops, so bullets also split, and anything still longer than [MAX_CHARS] is cut into ~30-word
     * pieces. Fragments shorter than [MIN_CHARS] (titles, page numbers) are dropped.
     */
    fun split(text: String): List<String> =
        text.split(SENTENCE_END).flatMap { it.split(BULLET) }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .flatMap { s ->
                if (s.length <= MAX_CHARS) listOf(s)
                else s.split(' ').chunked(WINDOW_WORDS).map { it.joinToString(" ") }
            }
            .filter { it.length >= MIN_CHARS && it.count(Char::isLetter) >= MIN_CHARS / 2 }

    /**
     * The [n] sentences across [sources] closest in meaning to [question] (cosine of the app's own
     * embeddings), best first, at most one per source unless a source is the only one.
     */
    fun pick(question: String, sources: List<Source>, embed: (String) -> FloatArray, n: Int = 2): List<KeySentence> {
        if (sources.isEmpty()) return emptyList()
        val q = embed(question)
        val scored = sources.flatMap { s -> split(s.hit.chunk.text).map { KeySentence(it, s, cosine(q, embed(it))) } }
            .sortedByDescending { it.score }
        val perSource = scored.distinctBy { it.source }
        return (perSource + scored).distinct().take(n)
    }

    /** About [chars] characters of [text] around [sentence], so the model sees the best part of a long passage. */
    fun excerptAround(text: String, sentence: String?, chars: Int): String {
        if (text.length <= chars) return text
        val at = sentence?.let { text.replace(Regex("\\s+"), " ").indexOf(it) } ?: -1
        val flat = text.replace(Regex("\\s+"), " ")
        if (at < 0) return flat.take(chars).trimEnd() + "…"
        val start = (at - chars / 3).coerceIn(0, (flat.length - chars).coerceAtLeast(0))
        val begin = if (start == 0) 0 else flat.indexOf(' ', start).let { if (it < 0) start else it + 1 }
        val end = (begin + chars).coerceAtMost(flat.length)
        return (if (begin > 0) "…" else "") + flat.substring(begin, end).trim() + (if (end < flat.length) "…" else "")
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na == 0f || nb == 0f) 0f else dot / sqrt(na * nb)
    }
}
