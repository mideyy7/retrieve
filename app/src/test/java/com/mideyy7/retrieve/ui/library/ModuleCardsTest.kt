package com.mideyy7.retrieve.ui.library

import com.mideyy7.retrieve.data.DocStatus
import com.mideyy7.retrieve.data.ModuleSummary
import com.mideyy7.retrieve.data.WeekStatusCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModuleCardsTest {
    private fun row(module: String, week: Int, status: DocStatus, count: Int = 1) = WeekStatusCount(module, week, status, count)

    private fun summary(docs: Int = 3, failed: Int = 0, busy: Int = 0) =
        ModuleSummary("COMP26120", null, docs, 10, docs - failed - busy, failed, busy, 0)

    @Test
    fun aWeekTakesItsWorstState() {
        val segments = weekSegments(
            listOf(
                row("COMP26120", 1, DocStatus.READY, 2),
                row("COMP26120", 2, DocStatus.READY),
                row("COMP26120", 2, DocStatus.INDEXING),
                row("COMP26120", 3, DocStatus.PENDING),
                row("COMP26120", 3, DocStatus.FAILED),
            ),
        ).getValue("COMP26120")
        assertEquals(
            listOf(WeekSegment(1, SegmentState.READY), WeekSegment(2, SegmentState.BUSY), WeekSegment(3, SegmentState.FAILED)),
            segments,
        )
    }

    @Test
    fun wholeModuleMaterialComesAfterNumberedWeeks() {
        val segments = weekSegments(
            listOf(row("M", 0, DocStatus.READY), row("M", 7, DocStatus.READY), row("M", 2, DocStatus.READY)),
        ).getValue("M")
        assertEquals(listOf(2, 7, 0), segments.map { it.week })
    }

    @Test
    fun statusOnlyWhenThereIsSomethingToActOn() {
        assertNull(ModuleCard(summary(), emptyList()).status)
        assertEquals("Indexing 2 of 3", ModuleCard(summary(busy = 1), emptyList()).status)
        assertEquals("1 failed", ModuleCard(summary(failed = 1, busy = 1), emptyList()).status)
    }
}
