package com.mideyy7.retrieve.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModulesTest {
    @Test
    fun codesAreTrimmedAndUpperCased() {
        assertEquals("COMP26120", ModuleCodes.normalize("  comp26120 "))
        assertEquals("MATH-101", ModuleCodes.normalize("math-101"))
    }

    @Test
    fun codesMustBeOneQueryWord() {
        assertNull(ModuleCodes.normalize(""))
        assertNull(ModuleCodes.normalize("COMP 26120")) // module:COMP would stop at the space
        assertNull(ModuleCodes.normalize("COMP(1)"))
        assertNull(ModuleCodes.normalize("\"COMP\""))
        assertNull(ModuleCodes.normalize("-COMP"))
        assertNull(ModuleCodes.normalize("A".repeat(33)))
    }

    @Test
    fun weekZeroIsTheWholeModule() {
        assertTrue(Weeks.isValid(0))
        assertTrue(Weeks.isValid(52))
        assertFalse(Weeks.isValid(53))
        assertFalse(Weeks.isValid(-1))
        assertEquals("Whole module", Weeks.label(0))
        assertEquals("Week 6", Weeks.label(6))
    }
}
