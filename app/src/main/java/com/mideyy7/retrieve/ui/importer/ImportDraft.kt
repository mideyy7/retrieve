package com.mideyy7.retrieve.ui.importer

import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.data.ModuleCodes
import com.mideyy7.retrieve.data.Weeks
import com.mideyy7.retrieve.ingest.FilenameTags
import kotlinx.serialization.Serializable

/**
 * One picked file in the import sheet. Module and week come from, in order: what the user typed
 * on this row, what the file name says, and the sheet's "Add to" defaults. So "Add to" fills
 * every row the file name says nothing about, and a row edited by hand keeps its own values.
 */
@Serializable
data class DraftRow(
    val uri: String,
    val name: String,
    val guessModule: String? = null,
    val guessWeek: Int? = null,
    val module: String? = null,
    val week: String? = null,
    val type: DocType = DocType.SLIDES,
) {
    constructor(uri: String, name: String, guess: FilenameTags) : this(uri, name, guess.module, guess.week)
}

@Serializable
data class ImportDraft(
    val rows: List<DraftRow> = emptyList(),
    val defaultModule: String = "",
    val defaultWeek: String = "",
)

/** What a row resolves to: a ready-to-import module and week, or the reason it can't be imported yet. */
data class RowCheck(val moduleText: String, val weekText: String, val module: String?, val week: Int?) {
    val ready get() = module != null && week != null

    val problem: String?
        get() = when {
            moduleText.isBlank() -> "Needs a module"
            module == null -> "Module code: letters, digits, - . _ and no spaces"
            weekText.isBlank() -> "Needs a week"
            week == null -> "Week must be 0 to ${Weeks.MAX}"
            else -> null
        }
}

fun ImportDraft.check(row: DraftRow): RowCheck {
    val moduleText = row.module ?: row.guessModule ?: defaultModule
    val weekText = row.week ?: row.guessWeek?.toString() ?: defaultWeek
    return RowCheck(
        moduleText = moduleText,
        weekText = weekText,
        module = ModuleCodes.normalize(moduleText),
        week = weekText.trim().toIntOrNull()?.takeIf(Weeks::isValid),
    )
}

/** Week input: digits only, at most two. */
fun cleanWeek(text: String) = text.filter(Char::isDigit).take(2)
