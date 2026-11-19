package com.mideyy7.retrieve.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which queries get hybrid (keyword + semantic) ranking: only ordinary questions. */
class IsPlainQueryTest {
    @Test
    fun ordinaryQuestionsArePlain() {
        assertTrue(SearchRepository.isPlain("what does DPLL do when a clause has one literal left"))
        assertTrue(SearchRepository.isPlain("Moore's law and 16:1 mux"))
        assertTrue(SearchRepository.isPlain("COMP12111:Overview notes"))  // digits before the colon: not a filter
        assertTrue(SearchRepository.isPlain("and or not in lower case"))
    }

    @Test
    fun queryLanguageIsNotPlain() {
        assertFalse(SearchRepository.isPlain("module:COMP21111 unit propagation"))
        assertFalse(SearchRepository.isPlain("unit (propagation)"))
        assertFalse(SearchRepository.isPlain("\"unit propagation\""))
        assertFalse(SearchRepository.isPlain("“stored procedure”"))
        assertFalse(SearchRepository.isPlain("trigger AND update"))
        assertFalse(SearchRepository.isPlain("NOT example"))
        assertFalse(SearchRepository.isPlain("dpll week:4"))
        assertFalse(SearchRepository.isPlain("   "))
    }
}
