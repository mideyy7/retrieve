package com.mideyy7.retrieve.qa

import com.mideyy7.retrieve.search.SearchOutcome
import com.mideyy7.retrieve.search.SearchResult
import com.mideyy7.retrieve.search.Snippets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * How passages are labelled in the prompt and how the model is told to cite them.
 * [TAGS] asks it to copy `[COMP21111 wk4 slide 22]`; [NUMBERED] labels passages [1]..[n] and asks for
 * the number, which the app maps back to the passage (a small model copies "[2]" far more reliably
 * than a long tag). [LETTERS] does the same with [A]..[D], which the model can't mistake for
 * numbering its own sentences. Either way every citation goes through [CitationChecker].
 */
enum class PromptStyle { TAGS, NUMBERED, LETTERS }

/** What the model is told, once per conversation ([PromptStyle.TAGS]). */
const val SYSTEM_PROMPT = """You help a student revise from their own lecture materials.
Answer the question using only the excerpts below. Write two to four short sentences.
After each sentence, cite the excerpt it comes from by copying its tag exactly, for example [COMP21111 wk4 slide 22].
Never cite a tag that is not in the excerpts. If the excerpts do not answer the question, reply only: Not in your materials."""

/** What the model is told, once per conversation ([PromptStyle.NUMBERED]). */
const val SYSTEM_PROMPT_NUMBERED = """You help a student revise from their own lecture materials.
Answer the question using only the numbered excerpts. Write two to four short sentences.
End every sentence with the number of the excerpt it comes from in square brackets, like [1] or [2].
If the excerpts do not answer the question, reply only: Not in your materials."""

/** What the model is told, once per conversation ([PromptStyle.LETTERS]). */
const val SYSTEM_PROMPT_LETTERS = """You help a student revise from their own lecture materials.
Answer the question using only the excerpts, which are labelled with letters like [A] and [B]. Write two to four short sentences.
End every sentence with the letter of the excerpt that supports it, like [A] or [C].
If the excerpts do not answer the question, reply only: Not in your materials."""

/** The phrase the model is told to use when it can't answer from the excerpts. */
const val NOT_IN_MATERIALS = "Not in your materials"

object Refusal {
    /**
     * Below this best question-to-chunk cosine similarity the library almost certainly doesn't cover
     * the question, so the app says so instead of asking the model. Tuned on the dev split only by
     * ml/refusal.py (docs/results/refusal.csv): dev 100% answered / 100% refused, test 96% / 100%.
     */
    const val THRESHOLD = 0.4768f
}

/** Model output so far plus timing, streamed while the answer is written. */
data class Generation(val text: String, val done: Boolean, val stats: GenerationStats? = null)

/** Time to first token and decode speed for one answer, as reported by the runtime. */
data class GenerationStats(
    val ttftMs: Long,
    val decodeTokensPerSecond: Double,
    val prefillTokens: Int,
    val decodeTokens: Int,
    val loadMs: Long?,
)

/** An on-device model. Separate from the pipeline so the pipeline is tested without one. */
interface Generator {
    fun generate(system: String, user: String): Flow<Generation>
}

sealed interface AskEvent {
    /** The library doesn't cover the question; the model isn't asked. */
    data class Refused(val topSimilarity: Float?, val reason: String) : AskEvent

    /** The passages chosen, before the model starts. */
    data class Sources(val sources: List<Source>) : AskEvent

    /** [raw] is exactly what the model wrote; [checked] is what's shown, citations moved to sentence ends. */
    data class Answer(val checked: CheckedAnswer, val sources: List<Source>, val done: Boolean, val stats: GenerationStats?, val raw: String = checked.text) : AskEvent {
        /** The model itself said the excerpts don't answer it. */
        val modelDeclined: Boolean get() = checked.text.trim().startsWith(NOT_IN_MATERIALS, ignoreCase = true)
    }
}

/**
 * Question -> retrieval -> refusal check -> prompt -> model -> checked citations.
 * The same pipeline runs in the Ask screen and in the on-device evaluation (QaEvalTest).
 */
class AskPipeline(
    private val search: suspend (String) -> SearchOutcome,
    private val generator: Generator,
    private val maxSources: Int = MAX_SOURCES,
    private val style: PromptStyle = PromptStyle.LETTERS,
) {
    fun ask(question: String): Flow<AskEvent> = flow {
        val outcome = search(question)
        val results = (outcome as? SearchOutcome.Results)?.results.orEmpty()
        val top = (outcome as? SearchOutcome.Results)?.topSimilarity
        if (results.isEmpty()) {
            emit(AskEvent.Refused(top, "Nothing in your library matches this question."))
            return@flow
        }
        if (top != null && top < Refusal.THRESHOLD) {
            emit(AskEvent.Refused(top, "This doesn't seem to be covered in your materials."))
            return@flow
        }
        val sources = sourcesFor(results, maxSources)
        emit(AskEvent.Sources(sources))
        val (system, user) = when (style) {
            PromptStyle.TAGS -> SYSTEM_PROMPT to userPrompt(question, sources)
            PromptStyle.NUMBERED -> SYSTEM_PROMPT_NUMBERED to numberedPrompt(question, sources)
            PromptStyle.LETTERS -> SYSTEM_PROMPT_LETTERS to letteredPrompt(question, sources)
        }
        generator.generate(system, user).collect { g ->
            val text = if (style == PromptStyle.LETTERS) moveLeadingCitations(g.text) else g.text
            emit(AskEvent.Answer(CitationChecker.check(text, sources), sources, g.done, g.stats, raw = g.text))
        }
    }

    companion object {
        /** Four passages of ~600 characters keep the prompt near 900 tokens: prefill is the slow part on a phone CPU. */
        const val MAX_SOURCES = 4
        const val EXCERPT_CHARS = 600

        /** The top results, one passage per place (hybrid results are already one per slide/moment). */
        fun sourcesFor(results: List<SearchResult>, n: Int): List<Source> =
            results.distinctBy { citationTag(it.hit) }.take(n).map { Source(citationTag(it.hit), it.hit) }

        fun userPrompt(question: String, sources: List<Source>): String {
            val terms = Snippets.queryTerms(question)
            val excerpts = sources.joinToString("\n\n") { s ->
                val text = Snippets.make(s.hit.chunk.text, terms, maxChars = EXCERPT_CHARS).text
                "${s.tag}\n$text"
            }
            return "Excerpts:\n\n$excerpts\n\nQuestion: ${question.trim()}"
        }

        /** Passages labelled [1]..[n] with where they're from; the reminder at the end is what a small model follows. */
        fun numberedPrompt(question: String, sources: List<Source>): String {
            val terms = Snippets.queryTerms(question)
            val excerpts = sources.withIndex().joinToString("\n\n") { (i, s) ->
                val text = Snippets.make(s.hit.chunk.text, terms, maxChars = EXCERPT_CHARS).text
                "[${i + 1}] (${s.tag.trim('[', ']')})\n$text"
            }
            return "Excerpts:\n\n$excerpts\n\nQuestion: ${question.trim()}\n" +
                "Answer in two to four sentences, ending each sentence with its excerpt number like [1]."
        }

        fun letteredPrompt(question: String, sources: List<Source>): String {
            val terms = Snippets.queryTerms(question)
            val excerpts = sources.withIndex().joinToString("\n\n") { (i, s) ->
                val text = Snippets.make(s.hit.chunk.text, terms, maxChars = EXCERPT_CHARS).text
                "[${'A' + i}] (${s.tag.trim('[', ']')})\n$text"
            }
            return "Excerpts:\n\n$excerpts\n\nQuestion: ${question.trim()}\n" +
                "Answer in two to four sentences. End each sentence with the letter of the excerpt that supports it, like [B]."
        }
    }
}
