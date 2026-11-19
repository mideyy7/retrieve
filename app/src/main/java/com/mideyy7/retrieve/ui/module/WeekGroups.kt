package com.mideyy7.retrieve.ui.module

import com.mideyy7.retrieve.data.Weeks
import com.mideyy7.retrieve.ui.library.DocumentRow
import com.mideyy7.retrieve.ui.library.weekOrder

data class WeekGroup(val week: Int, val rows: List<DocumentRow>)

/** Above this many documents, weeks start folded so the page reads as an index of weeks (as in Blitz). */
const val FOLD_THRESHOLD = 12

/**
 * Documents grouped by week, numbered weeks first and whole-module material last. A non-blank
 * [filter] keeps only documents whose name contains it; weeks left empty are dropped.
 */
fun groupByWeek(rows: List<DocumentRow>, filter: String = ""): List<WeekGroup> {
    val q = filter.trim()
    val shown = if (q.isEmpty()) rows else rows.filter { it.summary.name.contains(q, ignoreCase = true) }
    return shown.groupBy { it.summary.week }
        .map { (week, docs) -> WeekGroup(week, docs.sortedBy { it.summary.name.lowercase() }) }
        .sortedWith(weekOrder { it.week })
}

/**
 * Whether a week's documents are shown. A search opens every matching week; otherwise the user's
 * own choice wins, and untouched weeks are open only in short modules.
 */
fun isExpanded(week: Int, totalDocs: Int, filter: String, toggled: Map<Int, Boolean>): Boolean =
    filter.isNotBlank() || (toggled[week] ?: (totalDocs <= FOLD_THRESHOLD))

/** "14 docs · weeks 1–7 · 412 chunks", from the same rows the page lists. */
fun moduleHeadline(rows: List<DocumentRow>): String {
    val docs = rows.size
    val chunks = rows.sumOf { it.summary.chunkCount }
    val weeks = rows.map { it.summary.week }.filter { it != Weeks.WHOLE_MODULE }.distinct().sorted()
    val parts = mutableListOf("$docs doc${if (docs == 1) "" else "s"}")
    when {
        weeks.size == 1 -> parts += "week ${weeks.single()}"
        weeks.size > 1 -> parts += "weeks ${weeks.first()}–${weeks.last()}"
    }
    parts += "$chunks chunk${if (chunks == 1) "" else "s"}"
    return parts.joinToString(" · ")
}

/** The query that opens this module (and optionally one week) in Search; trailing space so typing continues it. */
fun scopeQuery(module: String, week: Int? = null): String =
    if (week == null) "module:$module " else "module:$module week:$week "
