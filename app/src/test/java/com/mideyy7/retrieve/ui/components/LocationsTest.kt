package com.mideyy7.retrieve.ui.components

import com.mideyy7.retrieve.data.DocType
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationsTest {
    @Test
    fun timestampsUnderAndOverAnHour() {
        assertEquals("0:00", timestamp(0))
        assertEquals("43:53", timestamp(2_633_400))
        assertEquals("1:02:07", timestamp(3_727_000))
    }

    @Test
    fun locationBySourceType() {
        assertEquals("slide 14", locationLabel(DocType.SLIDES, 14, null))
        assertEquals("page 3", locationLabel(DocType.PDF, 3, null))
        assertEquals("5:00", locationLabel(DocType.AUDIO, null, 300_000))
    }

    @Test
    fun citationsNameModuleWeekFileAndPlace() {
        assertEquals(
            "COMP21111 · Week 4 · Lecture 4 SAT · slide 22",
            citation("COMP21111", 4, "Lecture 4 SAT.pdf", DocType.SLIDES, 22, null),
        )
        assertEquals(
            "COMP23311 · Week 3 · W3 L1 - Unit Testing · recording at 43:53",
            citation("COMP23311", 3, "W3 L1 - Unit Testing.vtt", DocType.AUDIO, null, 2_633_000),
        )
        assertEquals(
            "COMP22111 · Whole module · notes · page 2",
            citation("COMP22111", 0, "notes", DocType.PDF, 2, null),
        )
    }
}
