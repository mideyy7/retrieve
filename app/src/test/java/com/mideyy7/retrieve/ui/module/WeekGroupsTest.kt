package com.mideyy7.retrieve.ui.module

import com.mideyy7.retrieve.data.DocStatus
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.data.DocumentSummary
import com.mideyy7.retrieve.ui.library.DocumentRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekGroupsTest {
    private var nextId = 1L

    private fun doc(name: String, week: Int, chunks: Int = 10) = DocumentRow(
        DocumentSummary(nextId++, name, "COMP26120", week, DocType.SLIDES, DocStatus.READY, null, chunks),
        progress = null,
    )

    @Test
    fun groupsByWeekWithWholeModuleLastAndNamesSorted() {
        val groups = groupByWeek(listOf(doc("b.pdf", 3), doc("Textbook.pdf", 0), doc("A.pdf", 3), doc("c.pdf", 1)))
        assertEquals(listOf(1, 3, 0), groups.map { it.week })
        assertEquals(listOf("A.pdf", "b.pdf"), groups[1].rows.map { it.summary.name })
    }

    @Test
    fun filterKeepsMatchingFilesAndDropsEmptyWeeks() {
        val groups = groupByWeek(listOf(doc("Raft.pdf", 6), doc("Paxos.pdf", 6), doc("Intro.pdf", 1)), filter = " raft ")
        assertEquals(listOf(6), groups.map { it.week })
        assertEquals(listOf("Raft.pdf"), groups.single().rows.map { it.summary.name })
    }

    @Test
    fun shortModulesStartOpenLongOnesFolded() {
        assertTrue(isExpanded(3, totalDocs = FOLD_THRESHOLD, filter = "", toggled = emptyMap()))
        assertFalse(isExpanded(3, totalDocs = FOLD_THRESHOLD + 1, filter = "", toggled = emptyMap()))
    }

    @Test
    fun userChoiceWinsAndSearchOpensEverything() {
        assertTrue(isExpanded(3, totalDocs = 40, filter = "", toggled = mapOf(3 to true)))
        assertFalse(isExpanded(3, totalDocs = 2, filter = "", toggled = mapOf(3 to false)))
        assertTrue(isExpanded(3, totalDocs = 2, filter = "raft", toggled = mapOf(3 to false)))
    }

    @Test
    fun headlineCountsDocsWeeksAndChunks() {
        assertEquals("3 docs · weeks 1–7 · 30 chunks", moduleHeadline(listOf(doc("a", 1), doc("b", 7), doc("c", 0))))
        assertEquals("1 doc · week 4 · 1 chunk", moduleHeadline(listOf(doc("a", 4, chunks = 1))))
        assertEquals("1 doc · 5 chunks", moduleHeadline(listOf(doc("a", 0, chunks = 5))))
    }

    @Test
    fun scopeQueriesUseTheQueryLanguage() {
        assertEquals("module:COMP26120 ", scopeQuery("COMP26120"))
        assertEquals("module:COMP26120 week:0 ", scopeQuery("COMP26120", 0))
    }
}
