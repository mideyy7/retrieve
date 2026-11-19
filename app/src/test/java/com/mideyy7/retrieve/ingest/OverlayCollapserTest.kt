package com.mideyy7.retrieve.ingest

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayCollapserTest {
    private fun pages(vararg texts: String) = texts.mapIndexed { i, t -> ExtractedPage(i + 1, t, ocr = false) }
    private fun kept(result: List<ExtractedPage>) = result.filter { it.text.isNotEmpty() }.map { it.page }

    private val title = "Unit propagation Let S be a set of clauses"
    private val step2 = "$title Repeatedly perform the following transformation"
    private val step3 = "$step2 Lemma unit propagation is satisfiability preserving"

    @Test
    fun buildUpSequenceKeepsOnlyTheCompleteSlide() {
        assertEquals(listOf(3), kept(OverlayCollapser().collapse(pages(title, step2, step3))))
    }

    @Test
    fun identicalFramesWithOnlyHighlightingChangesCollapseToTheLast() {
        val frame = "Unit Propagation Example p q r propagating one unit removes all occurrences"
        assertEquals(listOf(4), kept(OverlayCollapser().collapse(pages(frame, frame, frame, frame))))
    }

    @Test
    fun distinctSlidesAreAllKept() {
        val result = OverlayCollapser().collapse(
            pages("Raft leader election terms votes", "Log replication append entries", "Safety log matching property"),
        )
        assertEquals(listOf(1, 2, 3), kept(result))
    }

    @Test
    fun shorterNextPageIsNotTreatedAsBuildUp() {
        // The next slide repeats a few words but is shorter, so it is a different slide.
        val result = OverlayCollapser().collapse(pages("Transactions ACID atomicity consistency isolation durability", "Transactions ACID"))
        assertEquals(listOf(1, 2), kept(result))
    }

    @Test
    fun pageNumbersAndOcrFlagsArePreserved() {
        val input = listOf(ExtractedPage(7, title, ocr = true), ExtractedPage(8, step2, ocr = true))
        val result = OverlayCollapser().collapse(input)
        assertEquals(listOf(7, 8), result.map { it.page })
        assertEquals(listOf(true, true), result.map { it.ocr })
        assertEquals("", result[0].text)
    }

    @Test
    fun quizSlidesDifferingInOneTokenAreBothKept() {
        // Real false positives from the COMP12111 handout under the earlier 90% rule.
        val q1 = "Quiz-time How many control lines will a 4:1 mux require? A. 1 B. 2 C. 3 D. 4 COMP12111 Overview"
        val q2 = "Quiz-time How many control lines will a 16:1 mux require? A. 1 B. 2 C. 3 D. 4 COMP12111 Overview"
        assertEquals(listOf(1, 2), kept(OverlayCollapser().collapse(pages(q1, q2))))
    }

    @Test
    fun repeatedWordsMustAllReappear() {
        // "z a b" twice on page 1 but only once on page 2: not contained, so not a build step.
        assertEquals(listOf(1, 2), kept(OverlayCollapser().collapse(pages("z a b z a b", "z a b and more words"))))
        assertEquals(listOf(2), kept(OverlayCollapser().collapse(pages("z a b", "z a b z a b"))))
    }

    @Test
    fun replacedTextIsNotCollapsed() {
        // A Beamer \only swap that removes words keeps both pages, so no content is lost.
        assertEquals(listOf(1, 2), kept(OverlayCollapser().collapse(pages("Converting a formula to clausal form", "Clausal form example p or q"))))
    }
}
