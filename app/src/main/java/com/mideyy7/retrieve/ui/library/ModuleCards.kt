package com.mideyy7.retrieve.ui.library

import com.mideyy7.retrieve.data.DocStatus
import com.mideyy7.retrieve.data.ModuleSummary
import com.mideyy7.retrieve.data.WeekStatusCount
import com.mideyy7.retrieve.data.Weeks

/** A week's state on a module card's bar: the worst of its documents' states. */
enum class SegmentState { READY, BUSY, FAILED }

data class WeekSegment(val week: Int, val state: SegmentState)

data class ModuleCard(val summary: ModuleSummary, val segments: List<WeekSegment>) {
    /**
     * Text shown on the card only when there is something to act on or wait for; otherwise the bar
     * says enough. Mirrors Blitz, which prints a module's status only when it needs the learner.
     */
    val status: String?
        get() = when {
            summary.failedCount > 0 -> "${summary.failedCount} failed"
            summary.busyCount > 0 -> "Indexing ${summary.docCount - summary.busyCount} of ${summary.docCount}"
            else -> null
        }

    /** Read out by screen readers in place of the bar. */
    val description: String
        get() = buildString {
            append(summary.module)
            summary.title?.let { append(", $it") }
            append(", ${summary.docCount} document${if (summary.docCount == 1) "" else "s"}")
            append(", ${segments.count { it.week != Weeks.WHOLE_MODULE }} weeks")
            status?.let { append(", $it") }
        }
}

/** One segment per week present in each module, in week order with the whole-module material last. */
fun weekSegments(rows: List<WeekStatusCount>): Map<String, List<WeekSegment>> =
    rows.groupBy { it.module }.mapValues { (_, moduleRows) ->
        moduleRows.groupBy { it.week }
            .map { (week, weekRows) ->
                val statuses = weekRows.filter { it.count > 0 }.map { it.status }.toSet()
                val state = when {
                    DocStatus.FAILED in statuses -> SegmentState.FAILED
                    DocStatus.PENDING in statuses || DocStatus.INDEXING in statuses -> SegmentState.BUSY
                    else -> SegmentState.READY
                }
                WeekSegment(week, state)
            }
            .sortedWith(weekOrder { it.week })
    }

/** Weeks ascending, with week 0 (whole module) after the numbered weeks. */
fun <T> weekOrder(week: (T) -> Int): Comparator<T> =
    compareBy<T> { week(it) == Weeks.WHOLE_MODULE }.thenBy { week(it) }

fun modulesWithSegments(modules: List<ModuleSummary>, rows: List<WeekStatusCount>): List<ModuleCard> {
    val segments = weekSegments(rows)
    return modules.map { ModuleCard(it, segments[it.module].orEmpty()) }
}
