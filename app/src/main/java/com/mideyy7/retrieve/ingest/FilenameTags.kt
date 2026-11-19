package com.mideyy7.retrieve.ingest

/** Module and week guessed from a filename, used to pre-fill the import form. Either may be null. */
data class FilenameTags(val module: String?, val week: Int?)

object FilenameTagParser {
    // Manchester-style unit codes: four letters + five digits, e.g. COMP26120.
    private val MODULE = Regex("(?i)(?<![a-z])([a-z]{4})[\\s_-]?(\\d{5})(?!\\d)")

    // "week 4", "Week_04", "wk6", "w-12". Requires a separator or digit right after the keyword,
    // and no letter right before it, so "Hawk7" or "new3" don't match.
    private val WEEK = Regex("(?i)(?<![a-z])(?:week|wk|w)[\\s_-]*(\\d{1,2})(?!\\d)")

    fun parse(filename: String): FilenameTags {
        val stem = filename.substringBeforeLast('.')
        val module = MODULE.find(stem)?.let { (it.groupValues[1] + it.groupValues[2]).uppercase() }
        val week = WEEK.find(stem)?.groupValues?.get(1)?.toInt()?.takeIf { it in 1..52 }
        return FilenameTags(module, week)
    }
}
