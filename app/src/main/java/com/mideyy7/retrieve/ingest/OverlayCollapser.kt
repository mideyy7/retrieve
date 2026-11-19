package com.mideyy7.retrieve.ingest

/**
 * Removes intermediate "build-up" pages produced by slide overlays (LaTeX Beamer \pause/\only,
 * PowerPoint exports with animations as separate pages).
 *
 * Page i is a build step of page i+1 when at least [containment] of page i's words also appear on
 * page i+1 and page i+1 is at least as long. Such pages are superseded: the next page shows the
 * same content plus more (or the same text with different highlighting). Only the last page of a
 * build sequence is kept, so a search hit cites the complete slide.
 *
 * Without this, COMP21111's Beamer decks (76% build steps) would fill a top-5 result list with
 * copies of one slide. Superseded pages are blanked rather than removed, so every page keeps its
 * real page number.
 */
class OverlayCollapser(private val containment: Double = 0.9) {

    fun collapse(pages: List<ExtractedPage>): List<ExtractedPage> {
        val words = pages.map { WORD.findAll(it.text.lowercase()).map { m -> m.value }.toList() }
        return pages.mapIndexed { i, page ->
            if (i + 1 < pages.size && isBuildStep(words[i], words[i + 1])) page.copy(text = "") else page
        }
    }

    private fun isBuildStep(current: List<String>, next: List<String>): Boolean {
        if (current.isEmpty() || next.size < current.size) return false
        val nextSet = next.toHashSet()
        val shared = current.count { it in nextSet }
        return shared >= containment * current.size
    }

    private companion object {
        val WORD = Regex("\\w+")
    }
}
