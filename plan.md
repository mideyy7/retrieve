# Project Spec: Retrieve, On-Device Lecture Search for Android

Package name: `com.mideyy7.retrieve` (fixed: it can never change after the first Play upload).

## 0. How to use this document (for the LLM)

You are helping Ayo, a second-year CS student at Manchester, build this app for his CV before Google's London intern team matching. He knows C++ and Python well; Kotlin, Jetpack Compose and Android are newer to him.

- Build in small, tested steps. Explain Android concepts (lifecycle, Room, WorkManager, JNI) the first time they appear, and walk through code line by line when asked.
- Every number on the CV must come from a measurement this project actually produces. Never estimate, round up or invent results.
- Each feature exists to make a CV bullet true or to support the interview story. If a feature doesn't, keep it minimal (section 10).
- Commit small and often with descriptive messages; Ayo keeps a daily NOTES.md dev log.

## 1. One-line summary

An Android app that makes a student's lecture slides, PDFs and recordings searchable entirely on the phone: a C++ search engine with a custom query language, a PyTorch-distilled embedding model for semantic search, on-device transcription so results jump to the exact moment in a recording, and a small on-device language model that answers questions with citations to slide numbers and timestamps.

## 2. Why it exists (the use case)

Lecture recordings are hours of unsearchable audio and many slide decks are images. General phone search can't look inside either. A student revising asks "where did they explain Raft log matching?" and Retrieve answers "COMP26120 week 6, slide 14, and 32:10 in the recording", with a short cited answer. Everything stays on the device.

## 3. Target CV bullets (reverse-engineer everything from these)

> **Retrieve, On-Device Lecture Search for Android** | GitHub | Kotlin, Jetpack Compose, C++ (NDK/JNI), PyTorch, LiteRT
> - Built an Android app that indexes lecture slides and PDFs on the device and answers queries in **[X] ms at P99**, with a C++ inverted index and BM25 ranking called from Kotlin over JNI
> - Designed a query language (`module:COMP26120 week:4..6 "amortised" NOT tutorial`) with a parser and planner that orders terms by selectivity and applies filters before scoring
> - Distilled a sentence-embedding model in PyTorch from **[X]M to [Y]M parameters** and quantised it to int8, keeping recall@5 at **[Z]%** on a **[N]-question** labelled test set while cutting on-device latency from **[A] ms to [B] ms**

Strong supporting facts that may replace or extend a bullet if they measure well (all in scope):
- Hybrid keyword + semantic ranking improves recall@5 from **[a]%** (BM25 only) to **[b]%**
- On-device Whisper transcription at **[r]×** real time, with results linking to exact timestamps
- On-device question answering with citations, **[f] of 30** answers judged faithful
- Model profiled on the phone GPU with Ayo's own Vulkan layer (links to the GPU profiler project)

### Evidence each claim needs

| Claim | Evidence the repo must contain |
| --- | --- |
| Indexes slides and PDFs on device | Import flow, text extraction, OCR fallback, chunks stored with module/week/page |
| [X] ms at P99 | On-device benchmark: ≥ 200 queries from the test set, warm index, P50 and P99, phone model named |
| C++ inverted index + BM25 over JNI | `native/` C++ library with unit tests; JNI bridge; Kotlin calls it |
| Query language with parser and planner | Grammar doc, tokeniser, recursive-descent parser to AST, planner, tests, planned-vs-naive benchmark |
| Distilled [X]M → [Y]M, int8 | `ml/` training code, parameter counts printed, quantisation script, model files |
| recall@5 [Z]% on [N] questions | Labelled JSON test set in repo; one evaluation script used for every method |
| Latency [A] → [B] ms | On-device timing of baseline vs distilled+quantised model, same input set, CPU and GPU delegate |
| Hybrid improves recall | Same eval script run for BM25, semantic, hybrid |
| Transcription [r]× real time | Benchmark on a fixed 30-minute recording, tiny vs base model |
| QA faithfulness | 30 hand-judged answers in a CSV with categories |
| Vulkan link | ExecuTorch Vulkan export, run under `VK_LAYER_stage_profiler`, `docs/vulkan-inference.md` |

## 4. Architecture

```
app/ (Kotlin)
  ui/            Jetpack Compose screens: Library, Import, Search, Result detail, Ask, Settings
  data/          Room DB: Document, Chunk(id, docId, module, week, page|null, startMs|null, text, embedding blob)
  ingest/        PDF text extraction, ML Kit OCR fallback, chunker, audio import
  work/          WorkManager jobs: IndexDocumentWorker, TranscribeWorker, EmbedWorker
  ml/            LiteRT embedding runner, LLM runner (QA)
  search/        SearchRepository: query -> native plan -> keyword hits; embedding hits; RRF fusion
  nativebridge/  JNI declarations
native/ (C++17, CMake, built by the NDK and also on desktop for tests)
  index/         tokenizer, inverted index, postings, BM25
  query/         lexer, parser, AST, planner, executor
  whisper/       whisper.cpp integration
  jni/           JNI glue
  tests/         GoogleTest, runs on the laptop
ml/ (Python, PyTorch)
  data/          chunk export from the app, questions.json
  baseline.py    teacher model embeddings + recall@5
  distill.py     student model + distillation training
  quantize.py    int8 post-training quantisation
  export_litert.py, export_executorch_vulkan.py
  eval.py        one harness for BM25, semantic, hybrid
docs/            architecture diagram, query-language.md, results.md, vulkan-inference.md
NOTES.md
```

### Query flow

```
user query -> Kotlin -> JNI -> lexer -> parser -> AST -> planner -> executor (postings, BM25) -> top-k keyword hits
user query -> embedding model (LiteRT) -> cosine top-k over chunk embeddings -> semantic hits
keyword hits + semantic hits -> reciprocal rank fusion -> results (slide page or audio timestamp)
"Ask" -> top hybrid chunks as context -> on-device LLM -> answer with [slide/timestamp] citations
```

## 5. Component specs

### 5.1 Ingestion
- Import PDFs (and PDF exports of slide decks) via the Storage Access Framework. User tags module and week at import (pre-filled from filename where possible).
- Extract text per page. If a page has under ~20 characters of text, render it and run ML Kit text recognition.
- Chunk: one chunk per slide/page; split long pages into ~200-token chunks with overlap.
- Audio: import an audio file, transcribe with whisper.cpp into segments; merge segments into ~30-second chunks with `startMs`.
- All heavy work runs in WorkManager workers with progress and survives the app being closed.

### 5.2 Keyword engine (C++)
- Tokeniser: lowercase, split on non-alphanumerics, simple stemming optional (document the choice).
- Inverted index: term -> postings (chunkId, term frequency), document lengths, average length.
- BM25 with k1 = 1.2, b = 0.75 (configurable). Phrase queries via positional postings.
- Persist to a file in app storage and memory-map it on start.
- GoogleTest unit tests on desktop with a hand-checked tiny corpus.

### 5.3 Query language
- Grammar (document in `docs/query-language.md`):
  ```
  query   := orExpr
  orExpr  := andExpr ("OR" andExpr)*
  andExpr := unary (("AND")? unary)*
  unary   := "NOT" unary | primary
  primary := TERM | PHRASE | FILTER | "(" orExpr ")"
  FILTER  := ("module" | "week" | "type") ":" value     ; week supports ranges a..b
  ```
- Recursive-descent parser producing an AST; clear error messages with position for bad input.
- Planner:
  1. Push filters down: resolve module/week/type to a candidate chunk set first.
  2. Order AND terms by ascending document frequency (most selective first) and intersect.
  3. `NOT` as set difference applied after positive terms.
  4. Score only surviving candidates with BM25.
- Benchmark: same queries through planner vs naive (evaluate left to right, filter after scoring). Report time per query.

### 5.4 Embedding model (the PyTorch part, Ayo's main learning goal)
- **Test set first:** ~120 questions written by Ayo about his own modules, each labelled with the chunk ids that answer it; 20 of them answerable only from a recording. Stored as `ml/data/questions.json`. Never tune on the test questions; keep a separate small dev split.
- **Teacher:** a small open sentence-embedding model (e.g. a MiniLM-class model). Record parameter count, embedding dimension, recall@5.
- **Student:** fewer layers and/or smaller hidden size. Train by distillation on lecture-text chunks (not the test questions): minimise MSE or cosine loss between student and teacher embeddings. Log loss curves.
- **Quantisation:** int8 post-training quantisation; record size on disk.
- **Results table (required):** model, params, size MB, laptop latency, on-device CPU latency, on-device GPU latency, recall@5. Rows: BM25, teacher, student, student int8, hybrid.
- **Export:** LiteRT for the app (with a numerical parity check vs PyTorch on 50 inputs); ExecuTorch Vulkan backend as the second export for the profiler link.

### 5.5 Hybrid ranking
- Reciprocal rank fusion: score = Σ 1/(60 + rank) across keyword and semantic lists. Report recall@5 for keyword, semantic, hybrid using the same eval harness.

### 5.6 Transcription
- whisper.cpp compiled with the NDK, called over JNI; tiny and base models compared.
- Measure real-time factor on a fixed 30-minute lecture on the test phone; choose a default and justify it in docs.
- Search results from audio show the module, week and timestamp; tapping plays from `startMs`.

### 5.7 Question answering
- Use the currently recommended on-device LLM runtime for Android (check before starting; e.g. Gemma via Google's on-device LLM inference).
- Prompt includes the top hybrid chunks with ids; the model must cite `[module wk N slide P]` or `[module wk N mm:ss]`; if retrieval scores are below a threshold, reply that it isn't in the user's materials.
- Evaluate 30 questions by hand: correct / partly correct / wrong / unsupported claim. Record time to first token and tokens per second.

### 5.8 Profiling link to the GPU project
- Run the ExecuTorch Vulkan export on the phone with `VK_LAYER_stage_profiler` enabled.
- Compare where inference time goes vs the LiteRT path. Write `docs/vulkan-inference.md` and link it from both repos.

### 5.9 Battery and resources
- Measure battery per indexing run (100 slides) and per hour of transcription using `dumpsys batterystats` on the test phone. Record index size vs raw text size and peak memory.

## 6. Measurement methodology

- One named test phone for all on-device numbers (model, Android version, chipset in every results table).
- Fresh install, airplane mode, screen on, charger unplugged for battery runs.
- Latency: warm-up 20 queries, then ≥ 200 timed; report P50 and P99.
- Every number in the CV and README traceable to a CSV in `docs/results/` and a command or script that produced it.

## 7. Build order (maps to plan.md weeks 2 and 3)

| Day | Deliverable |
| --- | --- |
| Thu 26 Nov | Project + native module, import, text extraction, OCR, Room storage |
| Fri 27 Nov | C++ index + BM25 with tests, JNI, search screen |
| Sat 28 Nov | Query language: lexer, parser, planner, tests, planner benchmark |
| Sun 29 Nov | Test set, teacher baseline, distillation, int8 |
| Mon 30 Nov | LiteRT export + parity, on-device embeddings, hybrid RRF, latency CPU vs GPU |
| Tue 1 Dec | whisper.cpp via NDK, transcripts, timestamp playback, transcription benchmarks |
| Wed 2 Dec | Full measurement pass, README, demo video, CV numbers |
| Thu 3 Dec | WorkManager background indexing, battery |
| Fri 4 to Sat 5 Dec | Question answering with citations + evaluation |
| Sun 6 Dec | ExecuTorch Vulkan export under the profiling layer |
| Mon 7 to Tue 8 Dec | Final measurements, README, new demo |
| Stretch | Play Store closed test with 12+ classmates, then production |

## 8. Tests that must exist

- C++: tokenizer, index build, BM25 against hand-computed scores, phrase queries, lexer/parser (valid and invalid), planner equivalence (planned and naive return identical result sets), filter ranges.
- Kotlin: Room DAO tests, chunker tests, RRF tests.
- ML: parity test PyTorch vs LiteRT (max abs diff threshold), eval harness reproducibility (same seed, same numbers).

## 9. Acceptance checklist

- [ ] Searching a real module's slides on the phone returns correct pages, with the query language working
- [ ] Results table complete for BM25, teacher, student, student int8, hybrid
- [ ] Transcribed recording searchable, tap plays at timestamp
- [ ] QA answers cite sources; faithfulness CSV committed
- [ ] Vulkan inference profiled and documented
- [ ] README: problem, architecture diagram, results with phone model, how to build, demo video
- [ ] Every CV bracket filled from `docs/results/`
- [ ] Commit history spread over the days worked; NOTES.md daily

## 10. Keep minimal (needed, but not worth polish)

These exist only to make the pipeline usable; build the simplest version that works:
- UI styling and theming (default Material 3 is fine)
- Settings screen (model choice, thresholds)
- Library management (rename, delete documents)
- Account, sync, cloud backup: **none**; everything is on-device by design
- Supporting formats beyond PDF and common audio files
- Multiple languages, tablets, landscape layouts