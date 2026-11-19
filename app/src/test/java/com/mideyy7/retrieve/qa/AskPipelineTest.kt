package com.mideyy7.retrieve.qa

import com.mideyy7.retrieve.data.Chunk
import com.mideyy7.retrieve.data.ChunkWithDoc
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.search.SearchOutcome
import com.mideyy7.retrieve.search.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskPipelineTest {
    private fun result(page: Int, text: String) = SearchResult(
        ChunkWithDoc(
            Chunk(stableKey = "k$page", docId = 1, module = "COMP21111", week = 4, page = page, startMs = null, text = text),
            docName = "w4.pdf", docType = DocType.SLIDES, docSha = "s",
        ),
        score = 0.03f,
    )

    private val results = listOf(
        result(22, "Unit propagation. If S contains a unit clause, remove every clause containing L."),
        result(4, "DPLL ingredients: unit propagation, splitting."),
    )

    private class FakeGenerator(val answer: String) : Generator {
        var prompt: String? = null
        override fun generate(system: String, user: String): Flow<Generation> {
            prompt = user
            return flowOf(Generation(answer.take(10), done = false), Generation(answer, done = true))
        }
    }

    @Test
    fun belowTheThresholdTheModelIsNotAsked() = runTest {
        val gen = FakeGenerator("should not run")
        val events = AskPipeline({ SearchOutcome.Results(results, hybrid = true, topSimilarity = 0.30f) }, gen).ask("what is photosynthesis").toList()
        assertTrue(events.single() is AskEvent.Refused)
        assertEquals(null, gen.prompt)
    }

    @Test
    fun noResultsIsARefusal() = runTest {
        val events = AskPipeline({ SearchOutcome.Results(emptyList()) }, FakeGenerator("x")).ask("zzz").toList()
        assertTrue(events.single() is AskEvent.Refused)
    }

    @Test
    fun promptCarriesTaggedExcerptsAndAnswerIsChecked() = runTest {
        val gen = FakeGenerator("It removes satisfied clauses [COMP2111 wk4 slide 22].")
        val events = AskPipeline({ SearchOutcome.Results(results, hybrid = true, topSimilarity = 0.70f) }, gen, style = PromptStyle.TAGS)
            .ask("what is unit propagation").toList()

        val prompt = gen.prompt!!
        assertTrue(prompt.startsWith("Excerpts:\n\n[COMP21111 wk4 slide 22]\nUnit propagation."))
        assertTrue("[COMP21111 wk4 slide 4]" in prompt)
        assertTrue(prompt.endsWith("Question: what is unit propagation"))

        assertEquals(2, (events[0] as AskEvent.Sources).sources.size)
        val last = events.last() as AskEvent.Answer
        assertTrue(last.done)
        assertEquals(1, last.checked.repaired)
        assertEquals("[COMP21111 wk4 slide 22]", last.checked.citedSources.single().tag)
    }

    @Test
    fun modelSayingNotInMaterialsIsRecognised() = runTest {
        val events = AskPipeline({ SearchOutcome.Results(results, hybrid = true, topSimilarity = 0.6f) }, FakeGenerator("Not in your materials.")).ask("q").toList()
        assertTrue((events.last() as AskEvent.Answer).modelDeclined)
    }

    @Test
    fun numberedStyleLabelsPassagesAndMapsNumbersBack() = runTest {
        val gen = FakeGenerator("It removes satisfied clauses [1]. DPLL relies on it [2].")
        val events = AskPipeline({ SearchOutcome.Results(results, hybrid = true, topSimilarity = 0.70f) }, gen).ask("what is unit propagation").toList()
        val prompt = gen.prompt!!
        assertTrue(prompt.startsWith("Excerpts:\n\n[1] (COMP21111 wk4 slide 22)\nUnit propagation."))
        assertTrue("\n\n[2] (COMP21111 wk4 slide 4)\n" in prompt)
        val answer = (events.last() as AskEvent.Answer).checked
        assertEquals(listOf("[COMP21111 wk4 slide 22]", "[COMP21111 wk4 slide 4]"), answer.citedSources.map { it.tag })
        assertEquals(2, answer.valid)
    }
}
