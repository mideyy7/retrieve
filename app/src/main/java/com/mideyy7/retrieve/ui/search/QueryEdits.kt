package com.mideyy7.retrieve.ui.search

/**
 * Adds a `key:value` filter to a query typed in the query language. A filter with the same key
 * that stands as its own word is replaced (tapping "Week 6" on a result narrows `week:4..6` to
 * `week:6`); otherwise the filter is appended. Filters inside quotes are left alone. The result
 * ends with a space so the user can keep typing.
 */
fun withFilter(query: String, key: String, value: String): String {
    val filter = "$key:$value"
    val existing = Regex("(?i)(?<=^|[\\s(])$key:[^\\s()\"“”]+")
    val match = existing.findAll(query).firstOrNull { !insideQuotes(query, it.range.first) }
    val edited = if (match != null) {
        query.replaceRange(match.range, filter)
    } else {
        val q = query.trimEnd()
        if (q.isEmpty()) filter else "$q $filter"
    }
    return edited.trimEnd() + " "
}

private fun insideQuotes(text: String, index: Int): Boolean =
    text.substring(0, index).count { it == '"' || it == '“' || it == '”' } % 2 == 1
