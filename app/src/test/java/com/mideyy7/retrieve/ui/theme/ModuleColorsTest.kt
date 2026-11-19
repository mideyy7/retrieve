package com.mideyy7.retrieve.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleColorsTest {
    private val library = listOf("COMP12111", "COMP21111", "COMP22111", "COMP23111", "COMP23311")

    @Test
    fun hueDependsOnlyOnTheCode() {
        assertEquals(moduleHueIndex("COMP21111"), moduleHueIndex("comp21111"))
        assertEquals(assignModuleHues(library), assignModuleHues(library.reversed()))
    }

    @Test
    fun modulesGetDistinctHuesWhileThereAreEnough() {
        // On this library the plain hash puts two pairs on the same hue.
        assertEquals(library.size, assignModuleHues(library).values.toSet().size)
    }

    @Test
    fun moreModulesThanHuesStillGetAHueEach() {
        val many = (1..20).map { "COMP%05d".format(it) }
        val hues = assignModuleHues(many)
        assertEquals(20, hues.size)
        assertEquals(8, hues.values.toSet().size)
    }
}
