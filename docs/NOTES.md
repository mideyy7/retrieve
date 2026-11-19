# Retrieve dev log

One entry per day worked. Record decisions, measurements (with the CSV they live in), what broke, and what's next.

---

## 2026-11-19 (Thu): Phase 0 start

### Decisions
- **Repo initialised** on `main`.
- **Model files stay out of git.** Use `scripts/fetch_models.sh` to download them by pinned URL and check each SHA-256. Why not Git LFS: GitHub's free LFS quota is small and these models would use it up quickly, while a fetch script with checksums is just as reproducible.
- **Lecture PDFs and recordings stay out of git** (university copyright). Keep them locally in `corpus/`. `ml/data/chunks.json` (the extracted text) is also a question to check: confirm whether committing lecture text is OK before pushing it to a public repo.

### Dev machine
- MacBook M1, 8 GB RAM, ~44 GB free disk.
- Note: 8 GB is tight for Android Studio + Gradle + PyTorch running together. Close Studio while training (Phase 4).

### Research findings (checked 2026-11-19; confirm on official pages before relying on them)
- **Spike 1, LLM runtime:** MediaPipe LLM Inference is now maintenance-only. Google points to **LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android`, models in `.litertlm` format). Gemma 4 E2B is listed at ~2.6 GB, so it would need Play AI packs and a lot of phone RAM. Candidates to try: Gemma 3 1B (smaller) vs Gemma 4 E2B. Reported pitfall: requesting the GPU backend can silently fall back to CPU, so log which backend actually runs.
  - https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/
  - https://developers.google.com/edge/litert
- **Spike 3, converter:** `ai-edge-torch` was renamed **`litert-torch`** (`pip install litert-torch`, `litert_torch.convert(model, sample_inputs)`). The model must be exportable with `torch.export`. Quantisation is a separate step (`ai-edge-quantizer`).
  - https://github.com/google-ai-edge/litert-torch
- **R0, Play testing rule:** personal accounts created after 13 Nov 2023 need a closed test with **≥ 12 testers opted in continuously for ≥ 14 days** before applying for production. Organisation accounts are exempt. **To do: check when this account was created.**
  - https://support.google.com/googleplay/android-developer/answer/14151465

### Spike 3, laptop half: PASSED (`spikes/spike3_export_minilm.py`)
- Environment: `.venv` with Python 3.11.8, torch 2.14.1 (pinned by litert-torch), transformers 5.23.0, sentence-transformers 6.3.0, litert-torch 0.10.0.
- all-MiniLM-L6-v2 (BERT + mean pooling + L2 norm) converts with `litert_torch.convert` at seq len 128. fp32 .tflite = 90.1 MB.
- Parity on 4 sentences: max abs diff 2.05e-07, cosine 1.0. (Spike sanity check only; the real parity test is Phase 5, 50 inputs.)
- **Gotcha:** the converter downcasts int64 inputs to **int32**. The Kotlin tokeniser must output int32 token ids and attention masks.
- **Parameter breakdown (teacher, 22,713,216 total):** word embeddings 11.72M (**51.6%**), each of the 6 layers 1.77M (7.8%), position embeddings 0.2M, pooler 0.15M.
  - Implication for the student: removing 3 layers alone saves only ~23%. A big reduction needs a smaller hidden size and/or a vocabulary pruned to tokens that appear in the corpus.
  - The pooler isn't used by mean pooling and can be dropped from both models.
- Still to do: run `models/spike/minilm_fp32.tflite` on the test phone (CPU and GPU delegate).

### Toolchain check
- Desktop C++: CMake 4.3.0 + Apple clang 17 + GoogleTest v1.17.0 via FetchContent: builds, and ctest passes.
- Java: Temurin 17 is installed, but it's the **x86_64** build, which runs under Rosetta on this M1. Use Android Studio's bundled JDK, or install an arm64 JDK 17/21.
- Android SDK at `~/Library/Android/sdk`: has platform-tools and android-34 only. **Missing:** Android Studio, NDK, SDK CMake, a newer platform (API 35/36 for Play targetSdk). `adb` isn't on PATH.

### Phase 0 status
- [x] Toolchain installed (SDK, NDK, CMake, JDK 21; command-line builds, no Android Studio needed)
- [x] Test device chosen: Galaxy Tab A7 SM-T505N
- [~] Spike 1: LLM runtime + model (prepared: LiteRT-LM + 3 candidate models; needs the tablet)
- [~] Spike 2: whisper.cpp on phone (prepared: arm64 build + models; needs the tablet)
- [ ] Spike 3: PyTorch → LiteRT export of MiniLM, run on phone
- [x] Spike 4: PDF text extraction library (PdfBox + ML Kit, 19 real PDFs in Phase 1)
- [ ] Corpus collected; 30-min benchmark recording SHA-256 recorded
- [x] ≥ 120 question texts (120 drafted in ml/data/question_drafts.json; Ayo to review)
- [~] R0: Play account checked and testers recruited (Ayo, 2026-11-19); still to do: targetSdk requirement, size limits, model delivery method

---

## 2026-11-19 (Thu, continued): Phase 1, app skeleton, ingestion and storage

### Toolchain installed
- arm64 JDK 21 (`brew install openjdk@21`). Build with `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`.
- SDK: platform android-36 and android-37.2, build-tools 36.1.0, NDK 30.0.16248370, CMake 4.1.2.
- Gradle 9.9.1 wrapper, AGP 9.5.1 (built-in Kotlin), Kotlin 2.4.21, KSP 2.3.14.

### Decisions
- **minSdk 29, targetSdk 36, compileSdk 37.2.** compileSdk 37 is required by current AndroidX (core 1.19, Compose BOM 2026.09). targetSdk 36 is what Play currently needs: confirm in R0.
- **arm64-v8a only** for native code (test phone + nearly all Play devices + the Apple-silicon emulator).
- **PDF text: PdfBox-Android 2.0.27.0.** OCR: ML Kit text-recognition 16.0.1 (bundled Latin model, no Play services needed). This settles Spike 4's library choice, but it still has to be checked on 3 real lecture PDFs.
- **DAO tests run with Robolectric on the JVM** instead of on the phone (`connectedAndroidTest`), so they run on every build without a device.
- "Token" in the chunker = whitespace-separated word: max 200 per chunk, overlap 40, windows balanced in size.
- Documents are unique by SHA-256 of the file. Chunks are committed with READY in one transaction, so a crash can't leave a half-imported document.

### Verified on laptop
- `./gradlew testDebugUnitTest`: 33 tests pass (DAO 7, chunker 8, PDF extraction 6, indexer 6, filename parser 3, export 3).
- `ctest --test-dir native/build`: passes.
- `./gradlew assembleRelease bundleRelease`: R8 build succeeds and `NativeBridge` keeps its name (mapping.txt).

### Phase 1 exit gate
- [x] Unit tests pass (Robolectric replaces connected DAO tests)
- [ ] **Phone:** import a real module's PDFs, including an image-only deck; spot-check 5 OCR'd pages by eye
- [ ] **Phone:** kill the app mid-import (`adb shell am kill com.mideyy7.retrieve` while indexing, then force stop), reopen it, check there are no half-imported documents
- [ ] **Phone:** Export chunks twice → `adb pull /sdcard/Android/data/com.mideyy7.retrieve/files/chunks.json ml/data/chunks.json`, and confirm the two exports are identical (`cmp`)
- [ ] **R1:** create the upload key and keystore.properties, back up and restore-test the key; release build installed via bundletool imports and searches on the phone

### Test device (read with adb getprop, 2026-11-19)
- **Samsung Galaxy Tab A7 10.4 (2020), SM-T505N**, Android 12 (API 31)
- Qualcomm Snapdragon 662 (SM6115, "bengal"), Adreno 610 GPU (Vulkan driver present), arm64-v8a
- RAM: 2,770,252 kB visible (~3 GB). Free storage at selection: 3.9 GB of 22 GB
- Choice: use it for every on-device number. **Risk:** Phase 9 LLM on 3 GB RAM. Spike 1 must test a ~1B model (Gemma 3 1B / Qwen3 0.6B) on it before relying on it. Gemma 4 E2B won't fit.
- To do: free ~5 GB of storage before Phase 5.
- First install: debug build launches (cold start reported by `am start -W`: 2363 ms) and shows "Native engine 0.1.0", so JNI works on the device.

### Phase 1 device checks on the tablet (2026-11-19)
Corpus so far: `corpus/COMP12111/COMP12111 Part1 Notes_C_Moutafis_2025.pdf` (141 pages, a handout with slide images plus a "Notes:" lines area). Only real lecture PDF on the device so far; more modules still needed.

- **First import:** 195 chunks. Found that blank pages made junk chunks of just the page number ("9"), so pages with fewer than 3 letters are now skipped (commit 5fab64e).
- **Schema v1 → v2 migration ran on the device** with existing data: user_version 2, all 195 chunks kept.
- **Crash test PASSED.** Re-import (doc 2, week 1). At page ~12 of 141 the process was killed with `adb shell am crash com.mideyy7.retrieve` (`run-as kill` isn't permitted on this Samsung build). WorkManager restarted the process by itself after ~20 s, with the app not reopened. The import finished READY: 181 chunks, 181 distinct stableKeys, no orphan chunks from the deleted first import.
- **Dropped pages verified blank:** 2, 9, 23, 35, 41, 55, 65, 71, 87, 127, 131, 138, 139, 140 have 0.01–0.02% ink (page number only).
- **OCR:** 85 of 127 content pages are image-only slides and went through ML Kit. Spot-check of pages 14, 38, 64, 88, 116 against renders: prose and quiz options exact. Expected losses: superscripts flattened (10³ → 10), diagrams become fragments ("X y-"), one O→0 in a footer.
- **Known issue, to measure in Phase 5:** boilerplate on every page (garbled sideways logo "Thsniversity MANCHESTER", footer "COMP12111:Overview & Introduction to Logic", "Notes:"). Near-zero IDF, so BM25 should be unaffected, but it may pull embeddings together. If recall suffers, strip lines that repeat on most pages of a document.
- **Export determinism PASSED.** Two exports from the current build are byte-identical (sha256 a0ac2685…). An export from the previous build (before the format_version fix) has line-for-line identical chunk content. Saved as `ml/data/chunks.json` (181 chunks, 85 OCR), which is gitignored because it holds lecture text.
- Bug found and fixed while checking: `format_version` was missing from the export (kotlinx.serialization omits default values), commit 6af4b2e.

### Phase 1 exit gate: status
- [x] Unit tests (35) and native tests pass
- [x] Import a real module PDF with image-only slides on the device; OCR spot-checked on 5 pages
- [x] Process killed mid-import → WorkManager resumed it on its own → no partial or duplicate chunks
- [x] chunks.json exported, pulled, and deterministic
- [x] Corpus: 4 modules, 19 PDFs (see "Phase 1 closed")
- [x] R1: upload key created, backed up and restore-tested; release build matches debug on the tablet

### R1: upload key and release build (2026-11-19)
- Upload key: `~/keys/retrieve-upload.jks` (PKCS12, RSA 4096, alias `upload`, valid until 2054), CN "Retrieve Upload Key" (no personal name in a certificate anyone can read). Random password, stored only in the gitignored `keystore.properties`.
- Cert SHA-256: `02:68:1C:D6:D5:69:4A:9D:BA:0D:6E:38:EF:22:AC:58:1A:FB:CD:4B:8C:A4:50:8D:1F:A9:5E:0C:EC:A5:2E:DE`
- **Backup:** iCloud Drive → `Retrieve-upload-key-backup/` (jks + keystore.properties + README with restore steps). **Restore-tested:** the backup copy opens with its own password and the fingerprint matches.
- `bundleRelease` AAB is signed with the upload key (jarsigner verified).
- **R8 caught a real bug:** ML Kit's component registrars lost their no-arg constructors in release, so OCR would have failed only in the Play build. Fixed with a keep rule (commit 6a85951).
- Debug builds are now `com.mideyy7.retrieve.debug` ("Retrieve (debug)") so both builds install side by side. Use debug for development: `run-as` DB access only works on debuggable builds.

### Corpus imported to tablet (2026-11-19)
- `~/Downloads/Revision_Slides.zip` → `corpus/revision_zip/`: 18 PDFs, 1,072 pages. COMP21111 Logic and Modelling (weeks 1–5, 7), COMP22111 Processor Microarchitecture (weeks 1–5), COMP23111 Database Systems (weeks 4–5). Plus COMP12111 notes.
- `Week07_21111_slides_week6_bdds.pdf` is filed as **week 7** (confirmed by Ayo; the deck's own filename says week6).
- **COMP21111 Beamer overlays:** 657 of 865 pages are build-up steps. Collapsed so only the final, complete slide is indexed (commit d041cb4).
- Gotcha: files pushed with `adb push` don't show in the Downloads picker until they're media-scanned: `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://<path>`.

### Phase 1 closed (2026-11-19)
- **Overlay rule tightened:** the 90% word-overlap rule merged distinct COMP12111 quiz slides that differ in one token (4:1 vs 16:1 mux) and ~20 pages in COMP22111/23111. Now every word (with repeats) must reappear on the next page (commit 3168a78). Re-indexed with the new "Re-index all" action: COMP12111 is back to 127 pages / 181 chunks / 85 OCR.
- **Final library on the tablet:** 19 documents, all READY, 673 chunks (unique stableKeys), 87 OCR chunks. COMP12111 181, COMP21111 274, COMP22111 107, COMP23111 111. Export saved to `ml/data/chunks.json` (gitignored).
- Triggers.pdf was missing after the batch import because its dialog was dismissed (a tap outside the dialog = Skip). Re-imported fine.
- **R1 release check PASSED:** Transactions-01 imported in the upload-key-signed, R8-shrunk release build gives exactly the same chunks (22, 2 OCR, same text) as the debug build. "Search works in release" moves to Phase 2, when search exists.

**Phase 1 exit gate: all items done.**

---

## 2026-11-19 (Thu, continued): Phase 2, C++ keyword engine, JNI and search screen

### What was built
- `native/index/`: tokenizer → positional inverted index (one binary file, `mmap`ed, validated on open, atomic replace) → BM25 (k1 1.2, b 0.75, non-negative Lucene idf) with quoted phrases.
- `native/tools/retrieve_cli`: build/search/stats on the desktop from `chunks.json`, using the same sources as the app.
- JNI bridge + `KeywordIndex` (read-write lock around the native handle) + `KeywordIndexStore` (rebuild after index/delete and at launch) + Search tab.

### Tokenizer decisions (all in `native/index/tokenizer.h`)
- Lowercase ASCII; split on ASCII and Unicode punctuation/symbols (curly quotes, dashes, bullets, arrows, logic operators).
- **No stemming, no stopwords.** Cost seen: the phrase `"binary decision diagram"` finds nothing because the slides say "Diagrams" (the unquoted query works). Phase 4 recall will show whether a light plural rule is worth adding.
- Possessive `'s`/`’s` dropped: "Ohm’s Law" ranked #2 for "Moore's law" before this.
- Math alphanumeric symbols folded to ASCII: COMP23111 Transactions spells "𝑫𝑩𝑴𝑺 𝑩𝒖𝒇𝒇𝒆𝒓" in U+1D400 code points.

### Bugs caught by tests
- `cond ? lo : -1` with `lo` uint32_t → "not found" became 4294967295 (phrase tests + ASan). No compiler warning catches this, even `-Weverything`.
- JNI `GetStringUTFChars` gives modified UTF-8, so supplementary characters would differ from the CLI. JNI now converts UTF-16 → UTF-8 itself; a device test checks it, and real slides contain such characters.

### Numbers (desktop numbers on the M1; device numbers on the Tab A7). Informational, not benchmarks
- Corpus: 673 chunks, 3,778 terms, 889 KB index, avg 93 tokens/chunk.
- Build: CLI ~28–44 ms; tablet 494–496 ms (including the Room read).
- In-app query time shown in the UI: 9–19 ms for typical queries. **The CV P99 number comes from the Phase 7 benchmark, not this.**

### Incident: library wiped by connectedAndroidTest
- `./gradlew connectedDebugAndroidTest` uninstalls the app after running, which deleted the 19-document library. Restored from a database snapshot pulled earlier (integrity_check ok, 19 docs, 673 chunks).
- Side effect: the persisted SAF read grants were lost with the uninstall. **Re-index all would mark these documents FAILED** until each file is re-imported (re-importing re-takes the grant). Search and export are unaffected.
- Fix: `scripts/device-tests.sh` installs both APKs and runs `am instrument` directly, so nothing is uninstalled. Never use connectedAndroidTest on the test device.

### Phase 2 exit gate
- [x] `ctest`: 40 native tests pass, also under AddressSanitizer + UBSan (`native/build-asan`)
- [x] retrieve_cli gives sensible top-5 for known-answer queries (unit propagation, stored procedure parameters, trigger, ACID isolation, Moore's law, binary decision diagram)
- [x] Tablet vs CLI: index file **byte-identical**; `scripts/parity_check.py` **30/30 queries identical** (keys, order, scores)
- [x] No JNI leaks: 1,000 searches with native heap growth under 256 KB; 3,000-chunk build doesn't exhaust local refs (device tests)
- [x] R1 leftover: search works in the R8 release build (scores match the CLI)

---

## 2026-11-19 (Thu, continued): Phase 3, query language

### Design decision: what juxtaposed words mean
The spec grammar has implicit AND. Strict AND would make natural questions ("where did they explain unit propagation") match almost nothing, which would wreck the BM25 recall baseline in Phase 4. Adopted Lucene-style roles instead (full rules in `docs/query-language.md`):
- **required**: filters, phrases, multi-term words (RISC-V, 16:1), groups, anything next to an explicit `AND`
- **should**: plain single words. At least one must match, unless a required operand already brings words (a phrase or an AND-joined word).
- **must-not**: `NOT x`
So `module:COMP21111 unit propagation` = in that module **and** containing unit or propagation; `"stored procedure" parameters` = must have the phrase, and "parameters" only boosts the ranking.

### Built
- `docs/query-language.md` (written first): grammar, roles, ranking, every error message.
- `native/query/`: lexer (code-point columns, curly quotes), recursive-descent parser → AST, planned + naive executors, seeded random-query generator.
- Ties broken by module → week → page/start time → id, so filter-only and NOT-only queries list chunks in course order.
- `retrieve_cli parse | equiv | bench`; `scripts/make_bench_queries.py` → `bench/queries.txt` (210 distinct: 110 plain, 50 filter, 25 phrase, 25 boolean, generated from the corpus with a fixed seed); `scripts/planner_bench.py` → `docs/results/planner.csv`.
- App: syntax errors are shown under the search box with their column (QuerySyntaxException from JNI; works in the R8 release build).

### Verification
- 90 native tests (35 parser: valid trees + exact messages/columns; 14 semantics; equivalence; plus earlier suites), also under ASan + UBSan.
- **Equivalence:** planned == naive **bit for bit** on 500+ random queries over a synthetic corpus (unit test) and **1,000 random queries over the real corpus vocabulary** (`retrieve_cli equiv`, seed 7: 654 non-empty, 0 mismatches). Both executors add BM25 terms in the same order, which is what makes exact score equality possible.
- Device: 9 instrumented tests pass; device-vs-CLI parity **40/40** (now including filters, NOT, AND, OR, groups).
- Hand check on the tablet: `module:COMP21111 week:4..5 "unit propagation" NOT example` → wk4 slides 22, 4, 34 (12 ms). Without `NOT example`: +33, 75, 49, exactly the three containing "example".

### Benchmark: `docs/results/planner.csv` (commit 14f5df3, MacBook Air M1 8 GB, Release -O3, median of 101 alternating runs, top-10)
| kind | n | planned med | naive med | speedup med | speedup min |
|---|---|---|---|---|---|
| plain | 110 | 7.4 µs | 225.6 µs | 31.6× | 11.1× |
| phrase | 12 | 3.0 µs | 90.4 µs | 29.7× | 4.1× |
| filter | 66 | 13.6 µs | 92.5 µs | 7.3× | 2.5× |
| boolean | 22 | 4.2 µs | 162.2 µs | 35.5× | 11.7× |
| **all** | **210** | **8.9 µs** | **171.1 µs** | **21.4×** | **2.5×** |

**Caveat to keep when quoting this:** the naive baseline (as the spec defines it) tests every one of the 673 chunks one at a time. The speedup therefore measures set-at-a-time evaluation **plus** filters-first, not rarest-first ordering on its own. Laptop numbers, not on-device. The planner was faster on all 210 queries.

### Phase 3 exit gate
- [x] All C++ tests pass, including ≥ 500-query equivalence (synthetic) and 1,000 on the real corpus vocabulary
- [x] `planner.csv` committed with commit hash and machine
- [x] On the tablet, a real module + week range + phrase + NOT query returns the correct pages (hand-checked)

---

## 2026-11-19 (Thu): Scope change, Vulkan profiling removed
- **Dropped entirely** at Ayo's request: the ExecuTorch Vulkan export, running under `VK_LAYER_stage_profiler`, `docs/vulkan-inference.md`, and the link to the GPU profiler project. No code had been written for it.
- Spec: removed the CV supporting fact, its evidence row, §5.8 (old §5.9 Battery is now §5.8), its build-order day and its acceptance item (§9 now has 7 items).
- Plan: Phase 10 removed, so old Phase 11 (final measurements and release) is now **Phase 10**. Sun 6 Dec becomes a buffer day, most likely needed for the Phase 9 LLM fallback on the 3 GB tablet. The test device no longer needs Vulkan.
- Earlier entries that mention Vulkan are left as they were written (this is a log).

### R0 update (2026-11-19)
- Ayo confirmed the Play account check is done and testers are recruited. **Still to record here:** whether the 12-tester / 14-day closed-test rule applies to the account (created before or after 13 Nov 2023), and how many testers have agreed (target ≥ 15 for a buffer above 12).
- R0 left for me: current Play targetSdk requirement, download size limits, model delivery method (Play AI packs vs first-launch download), each with a link.

---

## 2026-11-19 (Thu, continued): Phase 4, test set, teacher, distillation, int8

### Test set: `ml/data/questions.json` (drafted by Claude at Ayo's request; Ayo to review)
- 120 questions (**101 test, 19 dev**), stratified across COMP12111/21111/22111/23111, from 134 slides picked by `ml/tools/sample_for_questions.py` (seed 4). Paraphrased as a revising student would ask. `author: claude-draft` on every question.
- Labels are locations (doc SHA-256 + page). Near-copy pages (Jaccard ≥ 0.95) also count; 0.8 was too loose (Stump Fetch vs Execute signal charts 0.81–0.89, Lost vs Temporary Update 0.88).
- **Frozen test split sha256 `57d17ab16cb84a36c4e2172d87bf4a90c4daf0a909972a614d52866699227b20`.** If Ayo edits questions, rebuild and record the new hash; don't tune on test.
- **Known bias:** questions written while reading the slides share their wording, which favours BM25. No recording questions yet (no recordings).

### Models (all numbers from `docs/results/*.csv`)
| | params | fp32 | int8 | laptop CPU latency (4 threads, batch 1, 128 tok) |
|---|---|---|---|---|
| teacher all-MiniLM-L6-v2 (pooler dropped) | 22,565,376 | 90.1 MB | – | 10.99 ms median |
| student-l3 | 10,429,056 (46.2%) | 42.0 MB | **11.2 MB** (3.74×) | 5.76 ms median |
- Student: teacher layers 0, 3, 5 (chosen by held-out fidelity before training: 0.558 vs 0.509 for 0,2,5), vocab 30,522 → 13,164 (all lecture pieces + WikiText-2 pieces with count ≥ 20, + single chars), positions 512 → 128.
- Distillation: 4 epochs, 19,793 texts (lecture windows + WikiText-2), cosine loss, 29 min on M1 CPU; held-out fidelity 0.558 → 0.915. Loss curve: `docs/results/distill_student-l3.csv`.
- Bug caught: `BertTokenizerFast(vocab_file=...)` silently ignored the file (everything became [UNK], fidelity 0.01). The tokenizer is now built from the teacher's tokenizer.json with the vocabulary swapped.
- LiteRT export parity on 50 inputs: student max abs 1.7e-7, teacher 2.9e-7. int8 (ai-edge-quantizer dynamic_wi8_afp32) vs fp32: cosine mean 0.9993, min 0.9990.
- MAX_LEN 128: on dev, identical R@1/5/10 to 256 (MRR 0.582 vs 0.607). 52% of chunks are truncated. Finding: COMP21111 wk3 p77/p79 share their first 128 tokens, so they embed identically.

### Recall: `docs/results/recall.csv`, CIs in `recall_ci.csv` (test, n = 101)
| method | R@1 | R@5 [95% CI] | R@10 | MRR@10 |
|---|---|---|---|---|
| BM25 (retrieve_cli) | 0.594 | 0.812 [0.733, 0.881] | 0.851 | 0.677 |
| teacher fp32 | 0.535 | 0.842 [0.762, 0.911] | 0.911 | 0.668 |
| student-l3 fp32 | 0.515 | 0.802 [0.723, 0.871] | 0.851 | 0.630 |
| **student-l3 int8 (LiteRT file)** | 0.515 | **0.802** [0.723, 0.871] | 0.851 | 0.632 |
| **hybrid BM25 + student int8 (RRF 60)** | 0.515 | **0.851** [0.782, 0.911] | 0.911 | 0.663 |
- Paired differences: hybrid − BM25 +0.040 [−0.010, +0.099]; student − teacher −0.040 [−0.099, +0.020]; int8 − fp32 0. **None significant at n = 101**, so CV wording reports the numbers without claiming a significant gain.
- `ml/tests` (pytest, 7 tests): metric arithmetic, RRF, split integrity, run-to-run reproducibility, self-retrieval sanity.

### Phase 4 exit gate
- [x] questions.json committed, test split hash recorded, every answer resolves to a chunk (builder + test)
- [x] recall.csv has BM25, teacher, student, student-int8 (+ hybrid) on test, all from eval.py
- [x] Parameter counts printed by code (baseline.py / distill.py)
- [x] Laptop latency recorded (`embed_latency_laptop.csv`)
- [ ] Ayo reviews the drafted questions (edits → rebuild → new hash)

---

## 2026-11-19 (Thu, continued): Phase 0 remainder, R0 research and spike prep

### R0 research (checked 2026-11-19)
- **targetSdk:** new apps and updates must target **API 36** since 31 Aug 2026; we target 36. https://support.google.com/googleplay/android-developer/answer/11926878
- **Size:** base module ≤ 200 MB compressed; asset packs don't count toward it. Our release AAB is 17 MB. https://support.google.com/googleplay/android-developer/answer/9859372
- **Model delivery decision: Play for On-device AI (beta) AI packs**, ≤ 1.5 GB per pack, ≤ 4 GB total, fast-follow/on-demand via the Play AI Delivery Library. Embedding model (11 MB) and Whisper tiny ship in the base module; Whisper base and the LLM go in on-demand AI packs. **Gemma 4 E2B (~2.6 GB) can't ship as one pack.** https://developer.android.com/google/play/on-device-ai
- Still from Ayo: does the 12-tester/14-day rule apply to the account, and how many testers?

### Spike 2 prep: whisper.cpp
- v1.9.5 (`d1be6fd`), built for macOS and cross-compiled with NDK 30 into a static arm64 `whisper-cli` (24.6 MB). Models (SHA-256 prefix): tiny.en 77.7 MB `921e4cf8`, tiny.en-q5_1 32.2 MB `c77c5766`, base.en 148.0 MB `a03779c8`, base.en-q5_1 59.7 MB `4baf70dd`.
- Laptop: both q5_1 models transcribe the JFK sample correctly (~2.2 s total for 11 s of audio, Metal build; not a device number).
- On the tablet: `spikes/spike2_whisper_device.sh tiny.en-q5_1` (prints the real-time factor).

### Spike 1 prep: on-device LLM
- MediaPipe LLM Inference is maintenance-only, so we use **LiteRT-LM**: official `litertlm-android` 0.19.1 from Google Maven, **debug builds only** for now (adds 22.7 MB of native libs to the debug APK).
- Running the downloaded `litert_lm_main` binary was blocked (unsigned binary, Gatekeeper bypass), and the library route is the Phase 9 path anyway.
- Candidates: Qwen3-0.6B int4 343 MB `bf1aba9c` (open). **Correction:** the Gemma 3 1B and 270M downloads were 137/140-byte "Access … is restricted" error pages (Gemma is gated on Hugging Face despite the listing), so the hashes first recorded here were of error pages. Gemma needs Ayo to accept the licence and provide a token.
- On the tablet: `spikes/spike1_llm_device.sh Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm cpu`, then the Gemma 1B. It pushes one model at a time (the tablet has ~3.9 GB free).

---

## 2026-11-19 (Thu, continued): Phase 5 built without the tablet; measurements pending

### Built and tested on the laptop
- **Kotlin WordPiece tokenizer**: identical ids to HuggingFace on all **454** fixture cases (questions, WikiText-2, edge cases: accents, İ, CJK, emoji, zero-width/control chars, math letters, over-long words, truncation). Java vs Rust differences handled: Unicode White_Space, per-code-point lowercasing, dropping format characters.
- **On-device embeddings**: LiteRT 2.3.2 `CompiledModel` (CPU/GPU) running the bundled student-l3 int8 (11.2 MB, stored uncompressed in the APK). `EmbedWorker` fills `chunks.embedding`; `SemanticIndex` does brute-force dot product in stableKey order (same tie-break as eval.py). `assets/embedding/model.json` holds the model SHA-256; a changed model clears and re-embeds everything. Install with `python ml/tools/install_app_model.py` (the .tflite is gitignored).
- **Hybrid search** for plain questions: keyword top 10 + semantic top 10, RRF (k 60) over locations, exactly as eval.py. `ml/data/rrf_fixture.json` is checked by both pytest and Kotlin `RrfTest`. Query-language queries stay keyword-only (semantic can't honour NOT, phrases or filters yet).
- 53 unit tests pass; the R8 release build (44 MB) includes LiteRT.

### Known issues / to do
- **eval.py audio locations:** `(doc_sha256, page)` with page = None would merge every chunk of a recording into one location. This must become (doc, start/end time) before the Phase 6 audio questions exist.
- LiteRT-LM (debug) and LiteRT are both in the debug APK (114 MB). Check for native-lib conflicts when the LLM spike runs.

### Phase 5 exit gate
- [x] Token-id parity test passes (454/454)
- [x] `recall.csv` has the hybrid row from eval.py (0.851 test)
- [ ] **Tablet:** `python ml/tools/make_embed_fixture.py` (done) → `scripts/device_embed_bench.sh`. Parity (device vs laptop LiteRT, 50 inputs) and `embed_latency_device.csv` (teacher fp32 vs student int8, CPU vs GPU, median/P95/P99, device fields)
- [ ] **Tablet:** semantic top-5 for 5 dev questions matches eval.py (open the app, let EmbedWorker finish, search)

---

## 2026-11-19 (Thu, continued): Tablet results, Phase 5 gate and Spike 1

### Phase 5 on the Galaxy Tab A7 (SM-T505N, Android 12, Snapdragon 662), `docs/results/embed_latency_device.csv`
| model | accelerator | median | P95 | P99 | parity vs laptop LiteRT (min cos, 50 inputs) |
|---|---|---|---|---|---|
| teacher fp32 (90 MB) | CPU | 269.9 ms | 271.0 ms | 271.4 ms | 1.00000 |
| **student-l3 int8 (11.2 MB)** | CPU | **88.6 ms** | 88.9 ms | **89.0 ms** | 0.99994 |
| both | GPU | init failed | | | |
- **3.0× faster on device** (inference only, 100 fixed questions after 20 warm-ups; tokenization timed separately).
- **GPU failure, root cause from logcat:** the GPU delegate doesn't support `GATHER_ND` (the word-embedding lookup), then an OpenCL kernel failed ("Unable to parse bc coord for BATCH axis"), so the model doesn't compile. Possible later fix: run the embedding lookup on CPU and the encoder on GPU. Not needed at 89 ms.
- EmbedWorker embedded all 673 chunks in ~65 s on device.
- **Semantic parity (gate):** device top-5 (device-embedded library and questions) vs laptop eval for 100 questions: **96/100 identical top-5, 100/100 identical top-1**, max score diff 3.2e-3. The 4 differences are near-tie swaps (e.g. GSAT p25/p28 have identical text).
- [x] Phase 5 exit gate complete (token parity, hybrid row, device semantic match, `embed_latency_device.csv` with device fields).

### Spike 1, on-device LLM: `docs/results/llm_spike.csv`
- **Qwen3-0.6B int4, CPU:** loads in 17.2 s (free RAM 1,146 → 432 MB), **TTFT 19.5 s, 1.27 tokens/s**, 5.5 min total (392 tokens, mostly Qwen3's hidden thinking). Answer roughly right and cited, but expands DPLL wrongly.
- **Same model, GPU:** killed by lmkd (signal 9) during OpenCL init: out of memory.
- **Gemma 3 (1B, 270M):** gated on Hugging Face, so the downloads were error pages. Needs Ayo's licence acceptance + token to test.
- **Conclusion:** on this 3 GB / Snapdragon 662 tablet, on-device QA works but is far too slow to use (~1 min even for a short answer without thinking). Phase 9 needs a decision from Ayo.

### Decision (2026-11-19): development device vs measurement device
- The Q&A spike showed the 3 GB tablet can't run an LLM at a usable speed. Ayo will get a newer phone for measurements.
- **Tablet = development device** (build everything, check correctness). **Phone = measurement device:** all on-device CV/README numbers are re-measured on it, not just Q&A, so the one-device rule (spec §6) still holds.
- Phone wanted: **≥ 6 GB RAM (8 GB better)**, Android 12+, a recent chip. Ideally one with a LiteRT-LM NPU build (Snapdragon 8 Gen 2/3/Elite = sm8550/8650/8750, or Tensor G5).
- Phase 9 keeps the generative LLM. On the tablet it's built and checked for correctness with Qwen3-0.6B (slow but works). Gemma needs Ayo's Hugging Face licence acceptance + token.
- Re-measure on the phone: `scripts/device_embed_bench.sh`, `spikes/spike1_llm_device.sh`, `spikes/spike2_whisper_device.sh`, and the Phase 7/8 benchmarks once written.

### Spike 1 continued: Gemma (licence accepted, logged in via `hf auth login`, token file deleted)
| model (CPU, tablet) | load | TTFT | decode | total | answer |
|---|---|---|---|---|---|
| Qwen3-0.6B int4 (343 MB) | 17.2 s | 19.5 s | 1.27 tok/s | 5.5 min | partly right, hidden thinking tokens |
| **Gemma 3 1B int4 (584 MB, sha256 `1325ae36`)** | 33.0 s | **9.4 s** | **5.08 tok/s** | **15.9 s** | partly right; **citation corrupted to `COMP2111`** |
| Gemma 3 270M int8 (304 MB, sha256 `757e9119`) | 6.5 s | 3.3 s | 7.87 tok/s | 5.1 s | wrong, no citation |
- **Phase 9 model: Gemma 3 1B int4.** Fits on the tablet (978 MB free after load), and is usable even here; the phone should be much faster.
- **Design consequence:** small models mangle citation tags, so Phase 9 must check every cited tag against the context chunks and repair it (nearest valid tag) or flag it. Never show citations unchecked.

### Spike 2: whisper.cpp on the tablet (dev device; JFK sample, 11.0 s, 4 threads)
| model | wall (incl. load) | real-time factor | transcript |
|---|---|---|---|
| tiny.en-q5_1 (32 MB) | 4.6 s | **0.42** | exact |
| base.en-q5_1 (60 MB) | 8.7 s | **0.79** | exact, better punctuation |
- Both faster than real time even on the Snapdragon 662, so Phase 6 is viable. The real RTF comes from the fixed 30-min recording on the measurement phone (load cost amortised).
- **Phase 0 spikes: all four done** (1 LLM → Gemma 3 1B; 2 Whisper → viable; 3 LiteRT export → done in Phase 4/5; 4 PDF → done in Phase 1).

---

## 2026-11-19 (Thu, continued): Housekeeping
- **Question set reviewed by Ayo:** all 120 drafted questions accepted as they are. Provenance for interviews: drafted with an assistant from Ayo's slides, reviewed and accepted by Ayo. questions.json is unchanged, so the frozen test-split hash `57d17ab1…` still holds.
- UI worktree merged (`9a84580`), `main` fast-forwarded, `ui-modules`/`ui-design` branches and the `retrieve-ui` worktree removed, `docs/ui.md` committed as the design record.
- Lecture captions found: COMP23311 W3 L1 "Unit Testing" (48:54, 591 cues, 7,102 words) and W3 L2 "From Tests To Test Suites" (44:42, 510 cues, 5,964 words), as `.vtt` + `.txt` automated transcripts. No audio yet.
- **File permissions restored (item 5).** First attempt failed: re-picking granted access to *new* URIs, but documents kept their old `sourceUri`, so "Re-index all" marked 18/19 FAILED ("permission revoked"); the one that worked had a matching URI. Fix: re-importing an existing file now repoints the document to the newly granted URI (commit after 3eddb88). After re-importing all 19 (driven through the picker with adb + uiautomator), every document re-indexed: **READY 19, 673 chunks, 673 embeddings**, keyword index rebuilt.

---

## 2026-11-19 (Thu, continued): Phase 6 part 1, lecture captions

### Built
- `WebVtt` parser + `TimedChunker` (≥ 30 s chunks, short tail folded in). Real captions: L1 591 cues → 86 chunks, L2 510 → 77, median ~80 words (fits 128 tokens; slides are 52% truncated).
- Import accepts `.vtt` as a **recording** (DocType.AUDIO), with module/week from the name ("COMP23311 … W3 …" → COMP23311 · Week 3). The indexer stores chunks keyed `sha:t<ms>`, page null. Search shows `COMP23311 › Week 3 › 43:53`.
- Gotcha: Android's media index types `.vtt` as `application/octet-stream`, so the picker also accepts that type (the sheet decides by extension).
- `eval.py`: chunk locations are `(doc, page)` or `(doc, "t", start_ms)`; recording answers are `{doc, start_ms, end_ms}`, hit = overlap with a chunk's span. Slide-only numbers verified unchanged before adding recordings.

### Question set v2: **test split sha256 `4bf9b74362433fb0eb415627122a42a817aec634ac177aa08d96957d767d6c56`**
- +24 recording-only questions (COMP23311 W3, drafted by Claude from the captions, time-range labels). 144 total = 23 dev + 121 test (101 slide + 20 recording). The 120 earlier questions are unchanged (same ids, splits, labels).

### Recall (laptop, corpus 836 chunks), `recall.csv` / `recall_ci.csv`
| method | test R@5 [95% CI] (n=121) | slides only (n=101) | recordings only (n=20) |
|---|---|---|---|
| BM25 | 0.835 [0.769, 0.901] | 0.812 | 0.95 |
| teacher fp32 | 0.868 [0.810, 0.926] | 0.842 | 1.00 |
| student int8 | 0.826 [0.760, 0.893] | 0.802 | 0.95 |
| **hybrid** | **0.868** [0.810, 0.926] | **0.861** | 0.90 (R@1 0.70, best) |
- No pairwise difference is significant (hybrid − BM25 +0.033 [−0.017, +0.083]).
- Recording questions are easy: COMP23311 is the only unit-testing material in the corpus, and the questions were written from the transcripts. Treat 0.90–1.00 as "works", not as a benchmark.

### Phase 6: what's left (needs the lecture **audio/video**)
- whisper.cpp in the app (JNI) + audio decoding (MediaCodec → 16 kHz mono), transcribe → same TimedChunker path.
- Media3 playback from `startMs` on tap.
- Real-time-factor benchmark on a fixed 30-min recording, tiny vs base, **on the measurement phone**.
- Bonus once audio exists: word error rate of Whisper vs the portal captions.
