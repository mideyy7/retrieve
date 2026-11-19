package com.mideyy7.retrieve.ui.components

import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.data.Weeks

/** 43:53, or 1:02:07 past the hour. */
fun timestamp(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Where a passage is inside its file: "slide 14", "page 3" or "43:53". */
fun locationLabel(type: DocType, page: Int?, startMs: Long?): String = when {
    startMs != null -> timestamp(startMs)
    type == DocType.SLIDES -> "slide $page"
    else -> "page $page"
}

/** File name without its extension, for display: "W3 L1 - Unit Testing.vtt" → "W3 L1 - Unit Testing". */
fun displayName(fileName: String): String =
    fileName.substringBeforeLast('.', fileName).ifBlank { fileName }

/**
 * A reference a student can paste into notes and find the place again:
 * `COMP21111 · Week 4 · Lecture 4 SAT · slide 22`, or `… · W3 L1 - Unit Testing · recording at 43:53`.
 */
fun citation(module: String, week: Int, fileName: String, type: DocType, page: Int?, startMs: Long?): String {
    val where = if (startMs != null) "recording at ${timestamp(startMs)}" else locationLabel(type, page, null)
    return listOf(module, Weeks.label(week), displayName(fileName), where).joinToString(" · ")
}
