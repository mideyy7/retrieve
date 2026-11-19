package com.mideyy7.retrieve.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test

class QueryEditsTest {
    @Test
    fun appendsToTheQuery() {
        assertEquals("raft log module:COMP26120 ", withFilter("raft log", "module", "COMP26120"))
        assertEquals("module:COMP26120 ", withFilter("  ", "module", "COMP26120"))
    }

    @Test
    fun replacesAFilterWithTheSameKey() {
        assertEquals("module:COMP26120 week:6 raft ", withFilter("module:COMP26120 week:4..6 raft", "week", "6"))
        assertEquals("(module:COMP21111 OR x) ", withFilter("(Module:COMP26120 OR x)", "module", "COMP21111"))
    }

    @Test
    fun leavesFiltersInsideQuotesAlone() {
        assertEquals("\"week:4 notes\" week:6 ", withFilter("\"week:4 notes\"", "week", "6"))
    }

    @Test
    fun doesNotMatchKeysThatAreSuffixesOfOtherWords() {
        assertEquals("midweek:3 week:6 ", withFilter("midweek:3", "week", "6"))
    }
}
