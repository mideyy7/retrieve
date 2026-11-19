package com.mideyy7.retrieve.ingest

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfTextExtractorTest {
    private class FakeSource(private val pages: List<String>) : PageTextSource {
        override val pageCount get() = pages.size
        override fun text(pageIndex: Int) = pages[pageIndex]
    }

    private class FakeOcr(private val results: Map<Int, String>) : PageOcr {
        val calls = mutableListOf<Int>()
        override suspend fun recognise(pageIndex: Int): String {
            calls += pageIndex
            return results[pageIndex].orEmpty()
        }
    }

    private val longText = "Amortised analysis of dynamic arrays"

    @Test
    fun textLayerUsedWhenLongEnoughAndOcrNotCalled() = runTest {
        val ocr = FakeOcr(emptyMap())
        val pages = PdfTextExtractor().extract(FakeSource(listOf(longText)), ocr)
        assertEquals(listOf(ExtractedPage(1, longText, ocr = false)), pages)
        assertEquals(emptyList<Int>(), ocr.calls)
    }

    @Test
    fun imageOnlyPageFallsBackToOcr() = runTest {
        val ocr = FakeOcr(mapOf(1 to "Raft log matching property"))
        val pages = PdfTextExtractor().extract(FakeSource(listOf(longText, "  ")), ocr)
        assertEquals(ExtractedPage(2, "Raft log matching property", ocr = true), pages[1])
        assertEquals(listOf(1), ocr.calls)
    }

    @Test
    fun thresholdCountsNonWhitespaceCharacters() = runTest {
        val nineteen = "a b c d e f g h i j k l m n o p q r s" // 19 letters, lots of spaces
        val twenty = "$nineteen t"
        val ocr = FakeOcr(mapOf(0 to "x".repeat(50), 1 to "y".repeat(50)))
        val pages = PdfTextExtractor(minChars = 20).extract(FakeSource(listOf(nineteen, twenty)), ocr)
        assertEquals(listOf(true, false), pages.map { it.ocr })
    }

    @Test
    fun keepsTextLayerWhenOcrFindsLess() = runTest {
        val ocr = FakeOcr(mapOf(0 to "W4"))
        val pages = PdfTextExtractor().extract(FakeSource(listOf("Week 4 intro")), ocr)
        assertEquals(ExtractedPage(1, "Week 4 intro", ocr = false), pages.single())
    }

    @Test
    fun reportsProgressPerPage() = runTest {
        val progress = mutableListOf<Pair<Int, Int>>()
        PdfTextExtractor().extract(FakeSource(listOf(longText, longText, longText)), FakeOcr(emptyMap())) { d, t ->
            progress += d to t
        }
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
    }
}
