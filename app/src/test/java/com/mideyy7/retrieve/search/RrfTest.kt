package com.mideyy7.retrieve.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The app's hybrid fusion must equal ml/eval.py's, which produced the hybrid recall numbers. */
class RrfTest {
    private val fixture = Json.parseToJsonElement(File("../ml/data/rrf_fixture.json").readText()).jsonObject

    @Test
    fun matchesPythonOnEveryFixtureCase() {
        val k = fixture["k"]!!.jsonPrimitive.int
        val limit = fixture["limit"]!!.jsonPrimitive.int
        assertEquals(Rrf.K, k)
        assertEquals(Rrf.LIMIT, limit)
        for (case in fixture["cases"]!!.jsonArray) {
            val c = case.jsonObject
            val lists = c["lists"]!!.jsonArray.map { l -> l.jsonArray.map { it.jsonPrimitive.content } }
            val expected = c["expected"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(c["name"]!!.jsonPrimitive.content, expected, Rrf.fuse(lists, k, limit).map { it.first })
        }
    }

    @Test
    fun locationKeysSortLikeShaPageTuples() {
        val keys = listOf(Rrf.locationKey("abc", 10, null), Rrf.locationKey("abc", 9, null), Rrf.locationKey("abb", 99, null))
        assertEquals(listOf("abb:p000099", "abc:p000009", "abc:p000010"), keys.sorted())
        assertTrue(Rrf.locationKey("abc", null, 30_000).startsWith("abc:t"))
    }
}
