package com.mideyy7.retrieve.qa

/** A citation marker in the displayed answer: its place in [RenderedAnswer.text] and the sources it links to. */
data class Marker(val range: IntRange, val sources: List<Source>, val unsupported: Boolean)

/** The answer as shown: each bracketed tag replaced by numbered markers ("[1]", "[1, 2]") or "[?]". */
data class RenderedAnswer(val text: String, val markers: List<Marker>, val numbered: List<Source>)

/**
 * Swaps the model's long tags for short numbers that refer to the source list under the answer.
 * Sources are numbered in order of first citation. A bracket whose citations matched nothing becomes
 * "[?]" so the reader sees a claim without support rather than a broken or invented link. Passages
 * the model was given but didn't cite get numbers after the cited ones.
 */
fun render(answer: CheckedAnswer, given: List<Source>): RenderedAnswer {
    val numbered = (answer.citedSources + given).distinct()
    val number = numbered.withIndex().associate { (i, s) -> s to i + 1 }
    val out = StringBuilder()
    val markers = mutableListOf<Marker>()
    var at = 0
    for ((range, cs) in answer.citations.groupBy { it.range }.toSortedMap(compareBy { it.first })) {
        out.append(answer.text, at, range.first)
        val sources = cs.mapNotNull { it.source }.distinct()
        val label = if (sources.isEmpty()) "[?]" else sources.joinToString(", ", "[", "]") { number.getValue(it).toString() }
        // Keep the marker off the previous word: "clauses[1]" reads worse than "clauses [1]".
        if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
        val start = out.length
        out.append(label)
        markers += Marker(start until out.length, sources, unsupported = sources.isEmpty())
        at = range.last + 1
    }
    out.append(answer.text, at, answer.text.length)
    return RenderedAnswer(out.toString().trim(), markers, numbered)
}
