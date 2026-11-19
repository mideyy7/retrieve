package com.mideyy7.retrieve.qa

import com.mideyy7.retrieve.data.Chunk
import com.mideyy7.retrieve.data.ChunkWithDoc
import com.mideyy7.retrieve.data.DocType
import org.junit.Assert.assertEquals
import org.junit.Test

class RenderedAnswerTest {
    private fun source(page: Int): Source {
        val hit = ChunkWithDoc(
            Chunk(stableKey = "k$page", docId = 1, module = "COMP21111", week = 4, page = page, startMs = null, text = "t"),
            docName = "d", docType = DocType.SLIDES, docSha = "s",
        )
        return Source(citationTag(hit), hit)
    }

    private val s22 = source(22)
    private val s4 = source(4)
    private val s9 = source(9)
    private val given = listOf(s4, s22, s9)

    @Test
    fun tagsBecomeNumbersInOrderOfFirstCitation() {
        val a = CitationChecker.check(
            "It removes clauses[COMP21111 wk4 slide 22]. DPLL uses it [COMP21111 wk4 slide 4; COMP21111 wk4 slide 22].",
            given,
        )
        val r = render(a, given)
        assertEquals("It removes clauses [1]. DPLL uses it [2, 1].", r.text)
        assertEquals(listOf(s22, s4, s9), r.numbered)
        assertEquals(listOf("[1]", "[2, 1]"), r.markers.map { r.text.substring(it.range) })
        assertEquals(listOf(s4, s22), r.markers[1].sources)
    }

    @Test
    fun unmatchedCitationShowsAQuestionMark() {
        val r = render(CitationChecker.check("Made up [COMP26120 wk6 slide 14].", given), given)
        assertEquals("Made up [?].", r.text)
        assertEquals(true, r.markers.single().unsupported)
    }

    @Test
    fun answerWithoutCitationsIsUnchanged() {
        val r = render(CitationChecker.check("Not in your materials.", given), given)
        assertEquals("Not in your materials.", r.text)
        assertEquals(emptyList<Marker>(), r.markers)
    }
}
