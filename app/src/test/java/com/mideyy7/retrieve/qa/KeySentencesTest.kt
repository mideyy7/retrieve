package com.mideyy7.retrieve.qa

import com.mideyy7.retrieve.data.Chunk
import com.mideyy7.retrieve.data.ChunkWithDoc
import com.mideyy7.retrieve.data.DocType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeySentencesTest {
    private fun source(page: Int, text: String): Source {
        val hit = ChunkWithDoc(
            Chunk(stableKey = "k$page", docId = 1, module = "COMP12111", week = 1, page = page, startMs = null, text = text),
            docName = "notes.pdf", docType = DocType.PDF, docSha = "s",
        )
        return Source(citationTag(hit), hit)
    }

    /** A stand-in embedder: a bag of the words below, so "closest in meaning" is just word overlap. */
    private val vocab = listOf("carry", "bit", "wait", "slow", "half", "adder", "delay", "truth", "table", "rows")
    private val embed = { s: String -> FloatArray(vocab.size) { i -> if (vocab[i] in s.lowercase()) 1f else 0f } }

    @Test
    fun splitsOnSentencesAndBulletsAndDropsFragments() {
        val parts = KeySentences.split("The parallel adder. Each bit must wait for the carry from the previous bit. • Sums need all carries generated first 105")
        assertEquals(listOf("Each bit must wait for the carry from the previous bit.", "Sums need all carries generated first 105"), parts)
    }

    @Test
    fun longUnpunctuatedCaptionsAreCutIntoWindows() {
        val caption = (1..70).joinToString(" ") { "word$it" }
        assertTrue(KeySentences.split(caption).size >= 2)
    }

    @Test
    fun picksTheBestSentencePerSourceFirst() {
        val a = source(105, "The parallel adder shown operates in parallel. Each bit must wait for the carry from the previous bit, so the adder is slow.")
        val b = source(101, "The half adder takes two input bits. It ignores a carry in from the previous bit.")
        val key = KeySentences.pick("why is a ripple carry adder slow", listOf(a, b), embed, n = 2)
        assertEquals("Each bit must wait for the carry from the previous bit, so the adder is slow.", key[0].text)
        assertEquals(a, key[0].source)
        assertEquals(b, key[1].source)
    }

    @Test
    fun excerptIsCutAroundTheKeySentence() {
        val text = "filler ".repeat(200) + "Each bit must wait for the carry. " + "tail ".repeat(200)
        val ex = KeySentences.excerptAround(text, "Each bit must wait for the carry.", 120)
        assertTrue("Each bit must wait for the carry." in ex)
        assertTrue(ex.startsWith("…") && ex.endsWith("…"))
    }
}
