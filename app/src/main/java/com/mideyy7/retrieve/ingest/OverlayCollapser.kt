package com.mideyy7.retrieve.ingest

/**
 * Removes intermediate "build-up" pages produced by slide overlays (LaTeX Beamer \pause/\only,
 * PowerPoint exports with animations as separate pages).
 *
 * Page i is a build step of page i+1 when every word on page i, counting repeats, also appears on
 * page i+1. The next page then shows the same content plus more (or the same text with different
 * highlighting), so only the last page of a build sequence is kept and a search hit cites the
 * complete slide.
 *
 * Containment must be total. A 90% threshold was tried first and wrongly merged distinct quiz
 * slides that differ in a single token ("4:1 mux" vs "16:1 mux", "Z=A.B" vs "Z=A+B"), losing
 * questions. Real overlays in the corpus are 100% contained.
 *
 * Without this, COMP21111's Beamer decks (~73% build steps) would fill a top-5 result list with
 * copies of one slide. Superseded pages are blanked rather than removed, so every page keeps its
 * real page number.
 */
class OverlayCollapser {

    fun collapse(pages: List<ExtractedPage>): List<ExtractedPage> {
        val counts = pages.map { wordCounts(it.text) }
        return pages.mapIndexed { i, page ->
            if (i + 1 < pages.size && isBuildStep(counts[i], counts[i + 1])) page.copy(text = "") else page
        }
    }

    private fun isBuildStep(current: Map<String, Int>, next: Map<String, Int>): Boolean =
        current.isNotEmpty() && current.all { (word, n) -> (next[word] ?: 0) >= n }

    private fun wordCounts(text: String): Map<String, Int> =
        WORD.findAll(text.lowercase()).groupingBy { it.value }.eachCount()

    private companion object {
        val WORD = Regex("\\w+")
    }
}
