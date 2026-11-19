package com.mideyy7.retrieve.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchesTest {
    @Test
    fun newestFirstWithoutRepeats() {
        val r = pushRecent(listOf("raft", "unit propagation"), "Unit  propagation ")
        assertEquals(listOf("Unit propagation", "raft"), r)
    }

    @Test
    fun blankIsIgnoredAndListIsCapped() {
        assertEquals(listOf("a"), pushRecent(listOf("a"), "   "))
        val many = (1..10).fold(emptyList<String>()) { acc, i -> pushRecent(acc, "q$i") }
        assertEquals(RecentSearches.MAX, many.size)
        assertEquals("q10", many.first())
    }
}
