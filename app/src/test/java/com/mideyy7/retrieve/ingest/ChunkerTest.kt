package com.mideyy7.retrieve.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {
    private fun words(n: Int) = (1..n).joinToString(" ") { "w$it" }
    private fun String.wordList() = split(" ")

    @Test
    fun shortPageIsOneChunkWithNormalisedWhitespace() {
        assertEquals(listOf("Raft log matching"), Chunker().chunkText("  Raft\n\tlog   matching \n"))
    }

    @Test
    fun emptyOrBlankPageGivesNoChunks() {
        assertEquals(emptyList<String>(), Chunker().chunkText(""))
        assertEquals(emptyList<String>(), Chunker().chunkText(" \n\t "))
    }

    @Test
    fun pageOfExactlyMaxTokensIsNotSplit() {
        assertEquals(1, Chunker(maxTokens = 200).chunkText(words(200)).size)
    }

    @Test
    fun oneWordOverMaxSplitsIntoTwoBalancedChunks() {
        val chunks = Chunker(maxTokens = 200, overlap = 40).chunkText(words(201)).map { it.wordList() }
        assertEquals(2, chunks.size)
        assertEquals(listOf(121, 120), chunks.map { it.size }) // balanced, not 200 + 41
    }

    @Test
    fun longPageInvariantsHoldForManyLengths() {
        val chunker = Chunker(maxTokens = 50, overlap = 10)
        for (n in 51..400) {
            val all = words(n).wordList()
            val chunks = chunker.chunkText(words(n)).map { it.wordList() }

            assertTrue("n=$n: every chunk within max", chunks.all { it.size <= 50 })
            assertEquals("n=$n: starts at first word", all.first(), chunks.first().first())
            assertEquals("n=$n: ends at last word", all.last(), chunks.last().last())
            for ((a, b) in chunks.zipWithNext()) {
                assertEquals("n=$n: neighbours share exactly the overlap", a.takeLast(10), b.take(10))
            }
            // Removing each overlap reconstructs the page exactly: no words lost or duplicated.
            val rebuilt = chunks.first() + chunks.drop(1).flatMap { it.drop(10) }
            assertEquals("n=$n: lossless", all, rebuilt)
            // Balanced: sizes differ by at most a little, so no tiny trailing chunk.
            assertTrue("n=$n: balanced ${chunks.map { it.size }}", chunks.maxOf { it.size } - chunks.minOf { it.size } <= 10)
        }
    }

    @Test
    fun zeroOverlapPartitionsThePage() {
        val chunks = Chunker(maxTokens = 10, overlap = 0).chunkText(words(25)).map { it.wordList() }
        assertEquals(words(25).wordList(), chunks.flatten())
    }

    @Test
    fun chunkPagesNumbersPagesFromOneAndSkipsEmptyPages() {
        val result = Chunker(maxTokens = 3, overlap = 1).chunkPages(listOf("a b", "", "c d e f"))
        assertEquals(
            listOf(PageChunk(1, 0, "a b"), PageChunk(3, 0, "c d e"), PageChunk(3, 1, "e f")),
            result,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlapMustBeSmallerThanMax() {
        Chunker(maxTokens = 10, overlap = 10)
    }
}
