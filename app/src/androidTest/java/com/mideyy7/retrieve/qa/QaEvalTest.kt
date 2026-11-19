package com.mideyy7.retrieve.qa

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mideyy7.retrieve.retrieveApp
import com.mideyy7.retrieve.search.SearchRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the app's real Q&A pipeline (hybrid retrieval, refusal check, Gemma 3 1B, citation checks) on
 * a fixed list of questions and writes one JSON line per question. Not a pass/fail test: it is
 * skipped unless given a question file, and the output is judged by hand (scripts/qa_eval.sh).
 *
 *   -e questions qa/<file>.json   pushed to the app's external files dir: [{"id", "question"}, ...]
 *   -e config v1|v2               pipeline version (AskConfig, default v2)
 *   -e style LETTERS|NUMBERED|TAGS prompt style (default LETTERS)
 *   -e out qa/<file>.jsonl        output, appended one line per question
 */
@RunWith(AndroidJUnit4::class)
class QaEvalTest {
    @Test
    fun answerQuestions() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.retrieveApp
        val base = requireNotNull(app.getExternalFilesDir(null))
        val input = args.getString("questions")?.let { File(base, it) }
        assumeTrue("pass -e questions <file> (see scripts/qa_eval.sh)", input?.isFile == true)
        assumeTrue("the answering model isn't installed", LlmModel.isInstalled(app))
        val style = PromptStyle.valueOf(args.getString("style", "LETTERS"))
        val out = File(base, args.getString("out", "qa/output.jsonl")).also { it.parentFile?.mkdirs() }

        app.keywordIndex.openThenRebuild()
        app.semanticIndex.reload()
        assumeTrue("library not embedded yet", app.semanticIndex.size > 0)

        val questions = JSONArray(input!!.readText())
        LiteRtGenerator(app).use { generator ->
            val search = SearchRepository(app.database, app.keywordIndex, app.semanticIndex, { app.embedder.embed(it) })
            val config = AskConfig.named(args.getString("config", "v2")).copy(style = style)
            val pipeline = AskPipeline(search::search, generator, { app.embedder.embed(it) }, config)
            for (i in 0 until questions.length()) {
                val q = questions.getJSONObject(i)
                val events = pipeline.ask(q.getString("question")).toList()
                out.appendText(record(app, q, style, events).put("config", config.name).toString() + "\n")
            }
        }
    }

    private fun record(context: Context, q: JSONObject, style: PromptStyle, events: List<AskEvent>): JSONObject {
        val o = JSONObject().put("id", q.getString("id")).put("question", q.getString("question")).put("style", style.name)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE).put("soc", Build.SOC_MODEL)
            .put("ram_mb", totalRamMb(context)).put("model", LlmModel.FILE_NAME)
        val refused = events.filterIsInstance<AskEvent.Refused>().firstOrNull()
        if (refused != null) {
            return o.put("outcome", "refused_retrieval").put("top_similarity", refused.topSimilarity?.toDouble())
        }
        val chosen = events.filterIsInstance<AskEvent.Sources>().first()
        val sources = chosen.sources
        o.put("key_sentences", JSONArray(chosen.keySentences.map { JSONObject().put("text", it.text).put("source", it.source.tag).put("score", it.score.toDouble()) }))
        val answer = events.filterIsInstance<AskEvent.Answer>().last()
        val rendered = render(answer.checked, sources)
        o.put("outcome", if (answer.modelDeclined) "declined_by_model" else "answered")
            .put("sources", JSONArray(sources.map { it.tag }))
            .put("raw_answer", answer.raw)
            .put("answer", rendered.text)
            .put("cited", JSONArray(answer.checked.citedSources.map { it.tag }))
            .put("citations_valid", answer.checked.valid)
            .put("citations_repaired", answer.checked.repaired)
            .put("citations_unsupported", answer.checked.unsupported)
        answer.stats?.let { s ->
            o.put("ttft_ms", s.ttftMs).put("decode_tok_s", s.decodeTokensPerSecond)
                .put("prefill_tokens", s.prefillTokens).put("decode_tokens", s.decodeTokens).put("load_ms", s.loadMs)
        }
        return o
    }

    private fun totalRamMb(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.totalMem / 1_000_000
    }
}
