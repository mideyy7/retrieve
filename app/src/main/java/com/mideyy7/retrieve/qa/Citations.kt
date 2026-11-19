package com.mideyy7.retrieve.qa

import com.mideyy7.retrieve.data.ChunkWithDoc
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.ui.components.timestamp

/** One passage given to the model, and the tag it must cite it by. */
data class Source(val tag: String, val hit: ChunkWithDoc)

/**
 * The citation tag for a passage, as the model is asked to write it:
 * `[COMP21111 wk4 slide 22]`, `[COMP22111 wk0 page 3]` or `[COMP23311 wk3 18:38]`.
 */
fun citationTag(module: String, week: Int, type: DocType, page: Int?, startMs: Long?): String {
    val where = when {
        startMs != null -> timestamp(startMs)
        type == DocType.SLIDES -> "slide $page"
        else -> "page $page"
    }
    return "[$module wk$week $where]"
}

fun citationTag(hit: ChunkWithDoc): String =
    citationTag(hit.chunk.module, hit.chunk.week, hit.docType, hit.chunk.page, hit.chunk.startMs)

/** How a citation in the answer relates to the passages the model was given. */
enum class CitationStatus {
    /** Exactly a given tag (after spacing/case/"week" vs "wk" differences). */
    VALID,

    /** Close to exactly one given tag (e.g. `COMP2111` for `COMP21111`), so it was corrected to it. */
    REPAIRED,

    /** Matches no given passage. Shown as unsupported; never linked. */
    UNSUPPORTED,
}

/** A citation found in the answer: where it is in the text, what was written, and what it points to. */
data class Citation(val range: IntRange, val written: String, val status: CitationStatus, val source: Source?)

data class CheckedAnswer(val text: String, val citations: List<Citation>) {
    val valid get() = citations.count { it.status == CitationStatus.VALID }
    val repaired get() = citations.count { it.status == CitationStatus.REPAIRED }
    val unsupported get() = citations.count { it.status == CitationStatus.UNSUPPORTED }

    /** The given passages the answer actually cites, in order of first citation. */
    val citedSources: List<Source> get() = citations.mapNotNull { it.source }.distinct()
}

/**
 * Checks every bracketed citation in a model answer against the passages it was given.
 *
 * Small on-device models copy tags imperfectly (the spike's Gemma 3 1B wrote `COMP2111` for
 * `COMP21111`), so nothing the model writes is linked unchecked: a tag must match a given passage
 * exactly, or be within a small edit distance of exactly one passage and clearly closer to it than
 * to any other, in which case it is repaired. Anything else is marked unsupported.
 */
object CitationChecker {
    /** Bracketed text that looks like a citation: has a digit and is short. Plain "[sic]" isn't one. */
    private val BRACKET = Regex("\\[([^\\[\\]\\n]{1,60})]")
    private const val MAX_EDITS = 3
    private val LETTER = Regex("(?:Excerpt\\s+)?([A-H])")
    private val LETTERS = Regex("\\s*[A-H](\\s*[,;]\\s*[A-H])*\\s*")
    private val NUMBER = Regex("(?i)(?:excerpt|source)?\\s*(\\d{1,2})")

    fun check(answer: String, sources: List<Source>): CheckedAnswer {
        val byNorm = sources.associateBy { normalise(it.tag) }
        val citations = BRACKET.findAll(answer).filter { m -> m.groupValues[1].any { it.isDigit() } || LETTERS.matches(m.groupValues[1]) }.flatMap { m ->
            // "[COMP21111 wk4 slide 22; COMP21111 wk4 slide 23]" or "[1, 2]" cite two passages in one bracket.
            val parts = m.groupValues[1].split(';')
            parts.flatMap { it.split(',').takeIf { p -> p.all { x -> NUMBER.matches(x.trim()) || LETTER.matches(x.trim()) } } ?: listOf(it) }
                .map { part -> resolve(m.range, part.trim(), byNorm, sources) }
        }.toList()
        return CheckedAnswer(answer, citations)
    }

    private fun resolve(range: IntRange, written: String, byNorm: Map<String, Source>, sources: List<Source>): Citation {
        // "[2]" (or "excerpt 2"): the numbered-prompt style refers to the second passage given.
        NUMBER.matchEntire(written.trim())?.let { m ->
            val i = m.groupValues[1].toInt() - 1
            return if (i in sources.indices) Citation(range, written, CitationStatus.VALID, sources[i])
            else Citation(range, written, CitationStatus.UNSUPPORTED, null)
        }
        // "[B]": the lettered-prompt style refers to the second passage given.
        LETTER.matchEntire(written.trim())?.let { m ->
            val i = m.groupValues[1][0] - 'A'
            return if (i in sources.indices) Citation(range, written, CitationStatus.VALID, sources[i])
            else Citation(range, written, CitationStatus.UNSUPPORTED, null)
        }
        val n = normalise("[$written]")
        byNorm[n]?.let { return Citation(range, written, CitationStatus.VALID, it) }
        // Only the place, e.g. "[slide 22]": fine if exactly one passage is at that place.
        byNorm.entries.filter { it.key.endsWith(" ${n.trim('[', ']')}]") }.singleOrNull()?.let {
            return Citation(range, written, CitationStatus.REPAIRED, it.value)
        }
        val scored = byNorm.entries.map { it to editDistance(n, it.key) }.sortedBy { it.second }
        val best = scored.firstOrNull()
        val runnerUp = scored.getOrNull(1)?.second ?: Int.MAX_VALUE
        if (best != null && best.second <= MAX_EDITS && best.second < runnerUp) {
            return Citation(range, written, CitationStatus.REPAIRED, best.first.value)
        }
        return Citation(range, written, CitationStatus.UNSUPPORTED, null)
    }

    /** Upper case, single spaces, no commas, and "week 4"/"wk 4"/"Wk.4" all as "WK4". */
    fun normalise(tag: String): String = tag.uppercase()
        .replace(',', ' ')
        .replace(Regex("\\bWEEK|\\bWK\\.?"), "WK ")
        .replace(Regex("WK\\s+(\\d)"), "WK$1")
        .replace(Regex("\\s+"), " ")
        .replace("[ ", "[").replace(" ]", "]")
        .trim()

    fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}

/**
 * Gemma 3 1B tends to put the letter before the sentence it supports ("[B] An AND gate's output is
 * true when…") even when told to put it after. Readers expect it at the end, so a citation that
 * opens a sentence is moved to just before that sentence's full stop. Only when a capitalised
 * sentence follows: in "[A] explains that…" the letter is the subject, and moving it would leave
 * "explains that… [A]". Citations already at the end
 * are left alone, and nothing is added or removed.
 */
fun moveLeadingCitations(answer: String): String =
    LEADING.replace(answer) { m ->
        val (lead, cite, sentence, stop) = m.destructured
        "$lead${sentence.trimEnd()} $cite$stop"
    }

/** A bracketed letter citation at the start of the text, a line or a sentence, then that sentence. */
private val LEADING = Regex("(^|\\n|(?<=[.!?])[ \\t]+)(\\[[A-H](?:\\s*[,;]\\s*[A-H])*])[ \\t]*([A-Z\"“][^\\[\\n.!?]*)([.!?])")
