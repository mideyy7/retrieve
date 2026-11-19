package com.mideyy7.retrieve.data

/**
 * Module codes are typed by hand at import and when renaming. They must stay one query-language
 * WORD (no whitespace, brackets or quotes), or `module:` filters couldn't name them.
 */
object ModuleCodes {
    private val VALID = Regex("[A-Z0-9][A-Z0-9._-]{0,31}")

    /** Upper-cased, trimmed code, or null if it can't be used as a module code. */
    fun normalize(input: String): String? = input.trim().uppercase().takeIf { VALID.matches(it) }
}

object Weeks {
    /** Week 0 holds material for the whole module. `week:0` finds it; `week:1..12` leaves it out. */
    const val WHOLE_MODULE = 0
    const val MAX = 52

    fun isValid(week: Int) = week in WHOLE_MODULE..MAX

    fun label(week: Int) = if (week == WHOLE_MODULE) "Whole module" else "Week $week"
}
