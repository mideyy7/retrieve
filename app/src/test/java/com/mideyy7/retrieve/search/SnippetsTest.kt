package com.mideyy7.retrieve.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetsTest {
    @Test
    fun queryTermsIgnoreQuotesPunctuationAndSingleLetters() {
        assertEquals(setOf("unit", "propagation", "4"), Snippets.queryTerms("\"Unit propagation\" a 4!"))
    }

    @Test
    fun queryTermsLeaveOutFiltersOperatorsAndExcludedWords() {
        assertEquals(
            setOf("unit", "propagation", "clause"),
            Snippets.queryTerms("module:COMP21111 week:4..6 \"unit propagation\" NOT example OR clause NOT \"pure literal\""),
        )
    }

    @Test
    fun questionWordsAreNotHighlighted() {
        assertEquals(setOf("unit", "propagation"), Snippets.queryTerms("what is unit propagation"))
    }

    @Test
    fun highlightsMatchedWordsCaseInsensitively() {
        val s = Snippets.make("Unit propagation removes the unit.", setOf("unit"))
        assertEquals("Unit propagation removes the unit.", s.text)
        assertEquals(listOf(0..3, 29..32), s.highlights)
        assertEquals(listOf("Unit", "unit"), s.highlights.map { s.text.substring(it) })
    }

    @Test
    fun windowMovesToTheFirstMatchInLongText() {
        val text = "filler ".repeat(100) + "the Raft log matching property " + "tail ".repeat(100)
        val s = Snippets.make(text, setOf("raft"), maxChars = 120)
        assertTrue(s.text.startsWith("…"))
        assertTrue(s.text.endsWith("…"))
        assertEquals(listOf("Raft"), s.highlights.map { s.text.substring(it) })
    }

    @Test
    fun noMatchShowsTheStart() {
        val s = Snippets.make("Stored procedures in MySQL", setOf("raft"))
        assertEquals("Stored procedures in MySQL", s.text)
        assertTrue(s.highlights.isEmpty())
    }
}
