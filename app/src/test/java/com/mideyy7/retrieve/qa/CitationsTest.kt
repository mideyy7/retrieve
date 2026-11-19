package com.mideyy7.retrieve.qa

import com.mideyy7.retrieve.data.Chunk
import com.mideyy7.retrieve.data.ChunkWithDoc
import com.mideyy7.retrieve.data.DocType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CitationsTest {
    private fun source(module: String, week: Int, type: DocType, page: Int?, startMs: Long?): Source {
        val hit = ChunkWithDoc(
            Chunk(stableKey = "$module:$page:$startMs", docId = 1, module = module, week = week, page = page, startMs = startMs, text = "t"),
            docName = "d", docType = type, docSha = "s",
        )
        return Source(citationTag(hit), hit)
    }

    private val slide22 = source("COMP21111", 4, DocType.SLIDES, 22, null)
    private val slide4 = source("COMP21111", 4, DocType.SLIDES, 4, null)
    private val rec = source("COMP23311", 3, DocType.AUDIO, null, 1_118_000)
    private val notes = source("COMP12111", 0, DocType.PDF, 6, null)
    private val sources = listOf(slide22, slide4, rec, notes)

    @Test
    fun tagsNameModuleWeekAndPlace() {
        assertEquals(listOf("[COMP21111 wk4 slide 22]", "[COMP21111 wk4 slide 4]", "[COMP23311 wk3 18:38]", "[COMP12111 wk0 page 6]"), sources.map { it.tag })
    }

    @Test
    fun exactAndLooselyFormattedTagsAreValid() {
        val a = CitationChecker.check(
            "Unit propagation removes clauses [COMP21111 wk4 slide 22]. Tests call units [comp23311, Week 3, 18:38].",
            sources,
        )
        assertEquals(listOf(CitationStatus.VALID, CitationStatus.VALID), a.citations.map { it.status })
        assertEquals(listOf(slide22, rec), a.citedSources)
    }

    @Test
    fun theSpikesCorruptedModuleCodeIsRepaired() {
        val a = CitationChecker.check("It removes the literal [COMP2111 wk4 slide 22].", sources)
        assertEquals(CitationStatus.REPAIRED, a.citations.single().status)
        assertEquals(slide22, a.citations.single().source)
    }

    @Test
    fun aBarePlaceIsRepairedOnlyWhenItIsUnambiguous() {
        assertEquals(slide22, CitationChecker.check("x [slide 22]", sources).citations.single().source)
        val other = listOf(slide22, source("COMP22111", 1, DocType.SLIDES, 22, null))
        assertEquals(CitationStatus.UNSUPPORTED, CitationChecker.check("x [slide 22]", other).citations.single().status)
    }

    @Test
    fun aTagForAPassageThatWasNotGivenIsUnsupported() {
        val c = CitationChecker.check("Made up [COMP26120 wk6 slide 14].", sources).citations.single()
        assertEquals(CitationStatus.UNSUPPORTED, c.status)
        assertNull(c.source)
    }

    @Test
    fun closeToTwoPassagesEquallyIsNotGuessed() {
        // "slide 2" is one edit from both "slide 22" and "slide 4": no unique nearest, so no repair.
        val c = CitationChecker.check("[COMP21111 wk4 slide 2]", sources).citations.single()
        assertEquals(CitationStatus.UNSUPPORTED, c.status)
    }

    @Test
    fun severalTagsInOneBracketAndNonCitationsIgnored() {
        val a = CitationChecker.check("See [COMP21111 wk4 slide 22; COMP21111 wk4 slide 4] and [sic].", sources)
        assertEquals(listOf(slide22, slide4), a.citedSources)
        assertEquals(2, a.citations.size)
    }

    @Test
    fun numbersReferToPassagesInTheOrderGiven() {
        val a = CitationChecker.check("It removes clauses [1]. Units call units [3]. Both [1, 2]. Nope [7].", sources)
        assertEquals(listOf(slide22, rec, slide4), a.citedSources)
        assertEquals(listOf(CitationStatus.VALID, CitationStatus.VALID, CitationStatus.VALID, CitationStatus.VALID, CitationStatus.UNSUPPORTED), a.citations.map { it.status })
    }
}
