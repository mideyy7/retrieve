package com.mideyy7.retrieve.ui.importer

import com.mideyy7.retrieve.ingest.FilenameTagParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportDraftTest {
    private fun row(name: String) = DraftRow("content://$name", name, FilenameTagParser.parse(name))

    @Test
    fun defaultsFillWhatTheFileNameDoesNotSay() {
        val draft = ImportDraft(listOf(row("L11 Raft.pdf"), row("wk7_lab.pdf"), row("COMP21111 week 2.pdf")), "comp26120", "6")
        val checks = draft.rows.map(draft::check)
        assertEquals(listOf("COMP26120", "COMP26120", "COMP21111"), checks.map { it.module })
        assertEquals(listOf(6, 7, 2), checks.map { it.week })
        assertTrue(checks.all { it.ready })
    }

    @Test
    fun handEditsWinOverFileNameAndDefaults() {
        val draft = ImportDraft(listOf(row("COMP21111 week 2.pdf").copy(module = "MATH10101", week = "0")), "COMP26120", "6")
        val check = draft.check(draft.rows.single())
        assertEquals("MATH10101", check.module)
        assertEquals(0, check.week)
    }

    @Test
    fun rowsSayWhyTheyCannotBeImported() {
        val draft = ImportDraft(listOf(row("notes.pdf")))
        assertEquals("Needs a module", draft.check(draft.rows.single()).problem)

        val badCode = ImportDraft(listOf(row("notes.pdf").copy(module = "COMP 26120", week = "3")))
        assertFalse(badCode.check(badCode.rows.single()).ready)

        val noWeek = ImportDraft(listOf(row("notes.pdf")), defaultModule = "COMP26120")
        assertEquals("Needs a week", noWeek.check(noWeek.rows.single()).problem)

        val badWeek = ImportDraft(listOf(row("notes.pdf")), "COMP26120", "53")
        assertEquals("Week must be 0 to 52", badWeek.check(badWeek.rows.single()).problem)

        val fine = ImportDraft(listOf(row("notes.pdf")), "COMP26120", "0")
        assertNull(fine.check(fine.rows.single()).problem)
    }

    @Test
    fun weekInputKeepsTwoDigits() {
        assertEquals("12", cleanWeek("1a2b3"))
    }

    @Test
    fun captionFilesAreRecordings() {
        assertEquals(com.mideyy7.retrieve.data.DocType.AUDIO, typeForFile("COMP23311 202627 - W3 L1 - Unit Testing.vtt"))
        assertEquals(com.mideyy7.retrieve.data.DocType.AUDIO, typeForFile("lecture.VTT"))
        assertEquals(com.mideyy7.retrieve.data.DocType.SLIDES, typeForFile("week4.pdf"))
    }
}
