package com.mideyy7.retrieve.ui.search

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [query] moved to the front of [recent], without repeats (ignoring case and spacing), at most [max]. */
fun pushRecent(recent: List<String>, query: String, max: Int = RecentSearches.MAX): List<String> {
    val q = query.trim().replace(Regex("\\s+"), " ")
    if (q.isEmpty()) return recent
    return (listOf(q) + recent.filterNot { it.equals(q, ignoreCase = true) }).take(max)
}

/** The last few searches the user committed to (pressed search or opened a result). Stays on the device. */
class RecentSearches(context: Context) {
    private val prefs = context.getSharedPreferences("search", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<String>> = _items.asStateFlow()

    fun add(query: String) = save(pushRecent(_items.value, query))

    fun remove(query: String) = save(_items.value - query)

    fun clear() = save(emptyList())

    private fun load(): List<String> =
        prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private fun save(items: List<String>) {
        if (items == _items.value) return
        _items.value = items
        // Queries are single-line (the field is singleLine), so a newline separates them safely.
        prefs.edit().putString(KEY, items.joinToString("\n")).apply()
    }

    companion object {
        const val MAX = 8
        private const val KEY = "recent"
    }
}
