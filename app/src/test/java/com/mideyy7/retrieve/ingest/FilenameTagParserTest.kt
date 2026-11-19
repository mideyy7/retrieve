package com.mideyy7.retrieve.ingest

import org.junit.Assert.assertEquals
import org.junit.Test

class FilenameTagParserTest {
    private fun p(name: String) = FilenameTagParser.parse(name)

    @Test
    fun commonPatterns() {
        assertEquals(FilenameTags("COMP26120", 4), p("COMP26120_Week4_Lecture.pdf"))
        assertEquals(FilenameTags("COMP26120", 6), p("comp26120-wk06.pdf"))
        assertEquals(FilenameTags("COMP25111", 12), p("COMP 25111 week 12 - Virtual memory.pdf"))
        assertEquals(FilenameTags(null, 3), p("W3 Raft consensus.pdf"))
        assertEquals(FilenameTags("MATH21120", null), p("MATH21120 notes.pdf"))
    }

    @Test
    fun noFalsePositives() {
        assertEquals(FilenameTags(null, null), p("Hawk7 slides.pdf"))
        assertEquals(FilenameTags(null, null), p("lecture.pdf"))
        assertEquals(FilenameTags(null, null), p("COMP261200.pdf")) // six digits is not a unit code
        assertEquals(FilenameTags(null, null), p("week 99.pdf")) // out of range
    }

    @Test
    fun extensionIgnored() {
        assertEquals(FilenameTags(null, null), p("notes.w5"))
    }
}
