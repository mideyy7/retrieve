# Retrieve: Phased Implementation Plan

Companion to [plan.md](plan.md) (the spec). The spec says *what* to build; this file says *in what order*, *how to know each phase is done*, and *what to record*. Dates follow spec section 7.

## How phases work

- Every phase ends in an **exit gate**: a short list of checks with the exact command or action that proves each one. **Do not start the next phase until every gate item passes.** If a gate fails and time runs out, cut from spec section 10 (the "keep minimal" list) or move a stretch item later. Don't skip the gate.
- Every phase ends with: green tests, a commit (or several small ones), a NOTES.md entry, and any numbers it produced saved as a CSV in `docs/results/`.
- **New concept** markers show where an Android/ML idea appears for the first time. Learn it properly there, because later phases assume it.
- No number goes on the CV or README unless it comes from a CSV in `docs/results/` that a script in the repo produced (spec §0, §6).

---

## Problems in the spec, and how this plan resolves them

These are gaps in the spec that would cause rework or invalid numbers if they weren't decided up front.

1. **Test set size vs benchmark size.** §6 wants ≥ 200 timed queries "from the test set", but the test set is ~120 questions. Timing the same query twice measures cache, not search. **Fix:** keep a separate latency workload, `bench/queries.txt`, with ≥ 200 *distinct* queries: the 120 test questions plus ≥ 80 query-language queries written in the style of real use. The test set is still only used for recall.
2. **Audio questions labelled before transcripts exist.** The test set is due Sun 29 Nov, but transcription arrives Tue 1 Dec, so the 20 recording-only questions can't point at chunk ids yet. **Fix:** label answers by **location, not chunk id**: `(docSha, page)` for slides and `(docSha, startMs, endMs)` for audio, the latter found by listening. A retrieved chunk counts as a hit if it is on that page or overlaps that time range. This also makes the test set survive any change to the chunker.
3. **WorkManager arrives on Thu 3 Dec, but §5.1 needs it from the start.** **Fix:** use a plain `CoroutineWorker` for import from Phase 1 (it's barely more code than a coroutine). Thu 3 Dec becomes *hardening*: foreground service, progress, resume after the process is killed, plus battery.
4. **Writing 120 labelled questions on one day, alongside distillation, isn't realistic.** **Fix:** write the question *text* during Phase 0 (this week). Label locations once the corpus has been imported (Phase 1 onward). Sun 29 Nov only finalises the file and the dev/test split.
5. **The BM25 row in the results table has to come from the real engine.** If `eval.py` used a Python BM25, the "BM25" and "hybrid" recall numbers would describe a different system from the one on the phone. **Fix:** build a desktop CLI, `retrieve_cli`, from the same `native/` library. `eval.py` calls it.
6. **The tokeniser on the phone must match the one used in training.** The LiteRT model takes token ids, so the app needs a WordPiece tokeniser that matches HuggingFace exactly. **Fix:** add a token-id parity test alongside the embedding parity test.
7. **Most of a MiniLM-class model's parameters are the token-embedding table** (vocabulary × hidden size). Dropping transformer layers shrinks the parameter count far less than you'd expect. Print the per-module parameter breakdown before choosing a student design (Phase 4).
8. **An int8 model on the GPU delegate** may fall back to CPU or be dequantised. The "GPU latency" for the int8 row might therefore not mean what it appears to. Log which delegate actually ran each op, and report what really happened.

---

## Conventions (set up once, used everywhere)

- **Stable chunk identity.** `Chunk.stableKey` = `"{docSha256[:12]}:p{page}:{i}"` for PDFs and `"{docSha256[:12]}:t{startMs}"` for audio. Room keeps an autoincrement `id`. The C++ index uses its own dense `uint32` ids plus a mapping table. Never put Room ids in files outside the app.
- **Results files.** `docs/results/<area>.csv`. Every row has `date, git_commit, device, android_version, chipset, script, ...metrics`. `docs/results/README.md` maps each CV bracket to its CSV row and the command that produced it.
- **Device info** is captured by code (`Build.MODEL`, `Build.VERSION.RELEASE`, `Build.SOC_MODEL`), never typed by hand.
- **One test phone** for every on-device number, chosen in Phase 0 and never changed.
- **Seeds** are fixed and logged in every training and eval script.

---

## Phase 0: Setup and de-risking (now → Wed 25 Nov)

**Goal:** by Thursday there should be no unknowns about toolchains or runtimes, and the test-set questions should be written.

Build / do:
- `git init`, `.gitignore` (Android + Python + CMake + model files), docs/NOTES.md, add docs/plan.md and this file. Decide whether to use Git LFS for model files, or to keep them out of git and download them with a script. Either works, but record which.
- Install Android Studio, the NDK, CMake, a Python 3.11 venv with PyTorch, and GoogleTest (via CMake `FetchContent`).
- **Choose the test phone.** It must be able to run a debuggable build and needs enough RAM for a ~1B LLM. Record its model, Android version and chipset in NOTES.md.
- **Spike 1 (runtime check, spec §5.7):** find out which on-device LLM runtime Google currently recommends (e.g. LiteRT-LM / MediaPipe LLM Inference) and which Gemma-class model fits the phone. Run its sample app on the phone.
- **Spike 2:** build whisper.cpp's Android example for the phone and transcribe 1 minute of audio.
- **Spike 3:** check that the current PyTorch → LiteRT converter (e.g. `ai-edge-torch`) can export a stock MiniLM and run it on the phone.
- **Spike 4:** pick the PDF text library. `PdfRenderer` only renders bitmaps on older APIs, so you probably need a text extractor such as PdfBox-Android. Check it on 3 real lecture PDFs.
- Collect the corpus: slides/PDFs for 2–3 modules and at least 2 lecture recordings (one is the fixed 30-minute benchmark recording).
- Write about 120 question *texts* in `ml/data/questions_draft.md`. Include about 20 that only a recording can answer.
- **Play release step R0** (see the Play release track below): check the account rules, recruit testers, choose how to deliver the models.

**Exit gate**
- [ ] Each of the 4 spikes ran on the test phone. The outcome and the chosen library/version are in NOTES.md.
- [ ] The corpus files are on the laptop, and the 30-minute benchmark recording is fixed (record its SHA-256).
- [ ] ≥ 120 question texts are written.
- [ ] Repo initialised and first commit made.

---

## Phase 1: App skeleton, ingestion, storage (Thu 26 Nov)

**New concepts:** activity lifecycle, Compose state, Room (entities, DAOs, migrations), Storage Access Framework, WorkManager basics.

Build:
- Android project `com.mideyy7.retrieve` (Compose, Material 3 defaults, single activity). Record the chosen minSdk/targetSdk.
- Empty `native/` CMake module wired into Gradle (`externalNativeBuild`) that exports one JNI function, `nativeVersion()`, which the UI displays. This proves the NDK path works on day 1.
- Room: `Document(id, sha256, name, module, week, type, importedAt)` and `Chunk(id, stableKey, docId, module, week, page?, startMs?, text, embedding?)`.
- Import screen: SAF picker → module/week form (prefilled by a filename regex) → enqueue `IndexDocumentWorker`.
- Worker: extract text per page; if a page has < 20 characters, render it and run ML Kit OCR; chunk the text; insert into Room. Re-importing the same SHA is a no-op.
- Chunker: one chunk per page; split pages over ~200 tokens with overlap.
- Library screen: documents with chunk counts. Debug action: **export chunks to JSON** (stableKey, module, week, page, startMs, text) for `ml/data/`.
- **Play release step R1:** release signing, R8 keep rules for JNI. Its gate is part of this phase's gate.

Tests:
- Room DAO tests (insert, query by module/week, duplicate SHA).
- Chunker tests: short page, long page, overlap correctness, empty page.

**Exit gate**
- [ ] `./gradlew testDebugUnitTest connectedDebugAndroidTest` passes.
- [ ] On the phone: import a real module's PDFs, including at least one image-only deck. Then spot-check 5 OCR'd pages against the originals by eye.
- [ ] Killing the app mid-import and reopening it doesn't leave half-imported documents (the worker retries, or the document is marked failed).
- [ ] The chunk export JSON is pulled to `ml/data/chunks.json`. Importing it twice gives identical stableKeys.

---

## Phase 2: C++ inverted index, BM25, JNI, search screen (Fri 27 Nov)

**New concepts:** JNI (native method declarations, `jstring`/`jlongArray` conversions, ownership across the boundary), NDK ABIs, memory-mapped files.

Build (`native/index/`):
- Tokeniser: lowercase, split on non-alphanumerics. Decide on stemming and document the decision. Whatever you choose, the query side must use the same tokeniser.
- Index: term → positional postings `(docId, tf, positions)`, per-chunk length, average length, and per-chunk metadata (module, week, type) stored in the index so that filters can run natively in Phase 3.
- BM25 (k1 = 1.2 and b = 0.75 by default, both configurable). Phrase matching via positions.
- Serialisation: write to a temp file, `fsync`, rename atomically, then `mmap` on load. Rebuild the whole index from Room after each import (the corpus is small; record how long a rebuild takes).
- `native/tools/retrieve_cli`: `build <chunks.json> <index>` and `search <index> "<query>" -k 5`, which output JSON. The same library code is used on desktop and on the phone.
- JNI bridge + `nativebridge/` Kotlin declarations; `SearchRepository` → Search screen showing the module, week and page for each hit.

Tests (GoogleTest, desktop):
- Tokeniser cases (punctuation, digits, unicode left as is).
- Index build on a 4–5-chunk hand corpus: check document frequencies, term frequencies and lengths.
- **BM25 scores against values computed by hand** on that corpus (show the working in a test comment).
- Phrase queries (adjacent vs non-adjacent).
- Save → mmap load → identical results.

**Exit gate**
- [ ] `cmake --build native/build && ctest --test-dir native/build` passes.
- [ ] `retrieve_cli` on `ml/data/chunks.json` returns sensible top-5 results for 5 queries you know the answers to.
- [ ] Phone: the same 5 queries return the **same top-5 chunks** as `retrieve_cli`. This shows the JNI path is correct.
- [ ] No JNI local-reference leaks: run 1000 searches in a loop without a crash or memory growth (check with the Android Studio profiler).

---

## Phase 3: Query language (Sat 28 Nov)

Build (`native/query/`):
- Write `docs/query-language.md` first: the grammar from spec §5.3, examples, precedence, and error messages.
- Lexer (positions on every token) → recursive-descent parser → AST. Error messages give the column: `expected ')' at col 17`.
- Planner: (1) resolve filters into a candidate set using the chunk metadata; (2) order AND terms by ascending document frequency and intersect; (3) apply NOT as a set difference after the positive terms; (4) compute BM25 only for the chunks that survive.
- Naive executor: evaluate left to right and filter after scoring. It exists only for the equivalence test and the benchmark.
- Edge cases to define and document: a pure `NOT x` query (all candidates minus x), a filter-only query (order by recency/page), an empty result, unknown filter keys, `week:6..4`.
- Show parse errors in the search screen.

Tests:
- Lexer and parser: ≥ 15 valid inputs (check the printed AST) and ≥ 10 invalid ones (check error message and position).
- **Planner equivalence:** for a generated set of ≥ 500 random queries over the real corpus vocabulary, planned and naive execution return identical result sets and identical scores (within an epsilon).
- Filter ranges: inclusive bounds, single week, reversed range rejected.

Benchmark:
- `retrieve_cli bench --planned/--naive bench/queries.txt` → `docs/results/planner.csv` (time per query for each mode). This is also the time to write `bench/queries.txt` (≥ 200 distinct queries; see problem 1).

**Exit gate**
- [ ] All C++ tests pass, including the 500-query equivalence test.
- [ ] `planner.csv` is committed, with the commit hash and machine recorded.
- [ ] On the phone, `module:<real module> week:4..6 "<real phrase>" NOT tutorial` returns correct pages (check by hand).

---

## Phase 4: Test set, teacher, distillation, int8 (Sun 29 Nov)

**New concepts:** sentence embeddings and mean pooling, knowledge distillation, post-training quantisation.

Build (`ml/`):
- `ml/data/questions.json`: `{id, question, split: "dev"|"test", answers: [{docSha, page} | {docSha, startMs, endMs}]}`. About 20 dev and about 100 test, with the recording-only questions spread across both. **Freeze the test split** by committing it and recording the file's SHA in NOTES.md. Never look at test results while tuning.
- `ml/eval.py`: one harness. Methods: `bm25` (calls `retrieve_cli`), `semantic --model X`, `hybrid --model X`. Defines recall@5 as "any of the top 5 chunks matches any answer location". Writes to `docs/results/recall.csv`. Fixed seed.
- `baseline.py`: teacher (MiniLM-class). Print the parameter count **broken down per module**, the embedding dimension, and recall@5 on dev and on test.
- `distill.py`: train the student on lecture chunks (never on questions) with MSE or cosine loss against teacher embeddings. If the student's hidden size differs from the teacher's, add a projection head. Choose the design using the parameter breakdown (problem 7). Log loss curves to `docs/results/distill_loss.csv` and save a plot.
- `quantize.py`: int8 post-training quantisation. Record the size on disk.

Tests:
- Eval reproducibility: two runs → identical `recall.csv` rows.
- A tiny sanity test: the teacher scored against itself gives recall@5 = 1.0 on a synthetic set where question = chunk text.

**Exit gate**
- [ ] `questions.json` is committed and its test-split SHA recorded. Every answer location resolves to at least one chunk in `chunks.json` (a script checks this).
- [ ] `recall.csv` has BM25, teacher, student and student-int8 rows on **test**, all produced by `eval.py`.
- [ ] Parameter counts are printed by code and pasted into results, not computed by hand.
- [ ] Laptop latency per model recorded with a fixed input set.

---

## Phase 5: LiteRT on device, hybrid ranking, CPU vs GPU (Mon 30 Nov)

**New concepts:** LiteRT interpreter and delegates, model assets, running work off the main thread.

Build:
- `export_litert.py` for the teacher and the student-int8. **Parity check**: run 50 inputs through PyTorch and LiteRT and assert a maximum absolute difference below a threshold (record the threshold and why you chose it).
- Kotlin WordPiece tokeniser + **token-id parity test** against HuggingFace on the same 50 inputs plus edge cases (punctuation, unknown words, long inputs that get truncated).
- `EmbedWorker`: embed every chunk and store it in `Chunk.embedding`. Brute-force cosine top-k (fine at this corpus size; record the size).
- RRF in Kotlin: `Σ 1/(60 + rank)`. A shared fixture `ml/data/rrf_fixture.json` is checked by both the Kotlin test and `eval.py`, so the phone and the eval compute the same fusion.
- An on-device benchmark (instrumented test or debug screen) that writes CSV to app storage. Pull it with `adb pull`. It times the teacher and the student-int8 with CPU and with GPU delegates (20 warm-up runs, then the full input set) and logs which delegate actually ran (problem 8).

**Exit gate**
- [ ] Parity tests (embeddings and token ids) pass.
- [ ] `recall.csv` gains a `hybrid` row from `eval.py`.
- [ ] On the phone: semantic top-5 for 5 dev questions matches `eval.py`'s semantic top-5 for the same model.
- [ ] `docs/results/embed_latency.csv` has teacher and student-int8 rows for CPU and GPU, with P50 and P99, device fields filled in by code.

---

## Phase 6: Transcription and timestamp playback (Tue 1 Dec)

**New concepts:** MediaCodec/MediaExtractor audio decoding, Media3 ExoPlayer, long-running work.

Build:
- whisper.cpp built into `native/whisper/` through the same CMake, plus JNI. The audio path is: decode → 16 kHz mono float PCM (resample if needed) → whisper → segments `(t0, t1, text)`.
- Merge segments into ~30-second chunks with `startMs`. Add them to Room, the C++ index and the embeddings using the existing pipeline.
- Audio results show the module, week and `mm:ss`. Tapping a result opens the player at `startMs`.
- `TranscribeWorker` (basic; hardened in Phase 8).
- Benchmark: the fixed 30-minute recording with tiny and with base → `docs/results/transcription.csv` (real-time factor, wall time, model size). Choose a default and justify it in `docs/` (accuracy spot-check on 10 segments vs speed).

Tests:
- Segment-merge unit tests (boundaries, a very long segment, gaps).
- Decode test: a known WAV yields the expected sample count at 16 kHz.

**Exit gate**
- [ ] Recording-only dev questions are answered by an audio chunk, and tapping one plays within a few seconds of the labelled moment.
- [ ] `eval.py` now covers the recording-only questions (this re-runs every row of `recall.csv`, because the corpus changed. Commit the new rows and keep the old ones with their commit hash).
- [ ] `transcription.csv` committed with tiny and base rows.

---

## Phase 7: Measurement pass v1 and shippable checkpoint (Wed 2 Dec)

**Goal:** after today the three core CV bullets are true and filled in, even if nothing else lands.

Do:
- The latency benchmark: fresh install, airplane mode, warm index, 20 warm-up queries, then all of `bench/queries.txt` (≥ 200), timed end to end through Kotlin → JNI → results. Output → `docs/results/search_latency.csv` (P50, P99).
- Re-run `eval.py` for all methods on the final corpus.
- README v1: problem, architecture diagram, results tables (with the phone model), build instructions, demo video v1.
- Fill `docs/results/README.md`: each CV bracket → CSV row → command.
- **Play release step R3:** upload this build to the closed testing track and start the 14 days.

**Exit gate**
- [ ] Someone could clone the repo, follow the README, and reproduce `ctest`, the Gradle tests and `eval.py`. Test this from a fresh clone in a temporary directory.
- [ ] Every bracket in CV bullets 1–3 is traceable in `docs/results/README.md`.
- [ ] Acceptance items 1 and 2 (spec §9) are ticked.

---

## Phase 8: WorkManager hardening and battery (Thu 3 Dec)

**New concepts:** foreground workers (`setForeground`), foreground service types (check which type fits long media processing on your targetSdk), work chaining, constraints.

Build:
- Chain Import → Index → Embed, and Transcribe → Index → Embed. Show progress in the UI and a notification. Make the chains resumable: a worker killed mid-run restarts without duplicating chunks (enforced by the stableKey uniqueness constraint).
- Battery: `adb shell dumpsys batterystats --reset` → index 100 slides → `dumpsys batterystats <pkg>`. Then do the same for 1 hour of transcription. Record the method and the raw output alongside → `docs/results/battery.csv`. Also record index size vs raw text size, and peak memory (from the profiler or `dumpsys meminfo`).

**Exit gate**
- [ ] Force-stopping the app during a transcription, then reopening it, resumes the transcription and ends with no duplicate chunks (check row counts).
- [ ] `battery.csv` committed, following the §6 conditions (charger unplugged, screen on, airplane mode).

---

## Phase 9: Question answering with citations (Fri 4 to Sat 5 Dec)

Build:
- Integrate the runtime and model chosen in the Phase 0 spike. Load the model lazily and release it when leaving the Ask screen.
- Prompt: the top-k hybrid chunks, each labelled with its citation tag `[MODULE wk N slide P]` / `[MODULE wk N mm:ss]`. The model must cite. Post-processing checks that each cited tag was in the context; flag answers whose tags weren't.
- Refusal threshold: if the top hybrid score is below a threshold, reply "not in your materials". **Tune the threshold on the dev split only.**
- Tapping a citation opens the slide or the timestamp.
- Record time to first token and tokens per second.

Evaluation:
- 30 questions → `docs/results/qa_eval.csv`: question, answer, citations, judgment (correct / partly correct / wrong / unsupported claim), notes. Fix the 30 questions before generating any answers.

**Exit gate**
- [ ] 30 rows judged and committed. Count of answers judged faithful computed by script from the CSV.
- [ ] A question about something not in the corpus gets the refusal.
- [ ] TTFT and tokens per second recorded with device fields.

---

## Phase 10: Final measurements and release (Mon 7 to Tue 8 Dec)

Sun 6 Dec is now a buffer day (Vulkan profiling was dropped from scope): use it to catch up, or for the Phase 9 fallback if the tablet can't run the LLM.

Do:
- From a fresh install, re-run every benchmark and eval on the final commit. Regenerate all CSVs and tag the commit `v1.0`.
- README final, demo video v2 (search with the query language, audio jump, Ask with citations).
- Fill in the CV brackets using only `docs/results/README.md`.

**Exit gate (= spec §9 acceptance checklist)**
- [ ] All 7 items in spec §9 are ticked.
- [ ] `git log` shows small commits spread across the days, and NOTES.md has an entry for each day.

---

## Play release track (runs alongside the phases)

The release work runs in parallel with the build phases rather than after them. The deciding constraint is Play's rule for personal developer accounts: before you can publish to production, a closed test needs **at least 12 testers opted in for 14 days in a row**. Check this in step R0, because the rule may not apply to your account, or may have changed. The 14 days can't be compressed, so the closed test starts at the Phase 7 checkpoint, and later phases ship to testers as updates.

Each step R0–R5 below has its own exit gate, just like the build phases.

| Step | When | Depends on |
| --- | --- | --- |
| R0 Account and testers | Phase 0 (by Wed 25 Nov) | none |
| R1 Release build and signing | Phase 1 (Thu 26 Nov) | R0 |
| R2 Model delivery | Phases 5–6 (Mon 30 Nov to Tue 1 Dec) | R1 |
| R3 Closed test starts | Phase 7 (Wed 2 Dec) | R2, Phase 7 gate |
| R4 Closed test runs | Thu 3 Dec until the 14 days are complete | R3 |
| R5 Production | After R4; earliest date = test start + 14 days + review | R4 |

### R0: Account and testers (Phase 0)

- In the Play Console, check the account type and whether the closed-test requirement applies. Write the exact current rule, with a link to the Help page, in NOTES.md.
- Recruit **at least 15 testers** (a buffer above 12, since some will drop out). Collect their Google account emails into a Google Group, so you add one group to the track instead of individual emails.
- Choose how to deliver the models, using a short spike: Play's on-device AI delivery ("AI packs") or Play Asset Delivery, vs downloading on first launch with a checksum check. Record the choice and why. Prefer delivery through Play: it keeps the app free of the internet permission and fits the "everything on device" story.
- Check the current Play targetSdk requirement and the current download size limits. Record both.

**Exit gate**
- [ ] Testing rule, targetSdk requirement and size limits recorded with links.
- [ ] At least 15 testers have said yes and are in the Google Group.
- [ ] Model delivery method chosen.

### R1: Release build and signing (Phase 1)

- Generate an upload keystore. Keep its passwords in a gitignored `keystore.properties`, and back up the keystore and passwords somewhere other than the laptop (e.g. a password manager). Enrol in Play App Signing on the first upload.
- Set up the `release` build type: R8 minification on, `debuggable false`, targetSdk at the Play requirement.
- **New concept: R8 and JNI.** R8 renames and removes classes it believes are unused. Native code finds Kotlin methods by name, so add `-keep` rules for `nativebridge/` and anything called from C++. Without them, release builds crash even though debug builds work.
- Add `versionCode`/`versionName` rules: the versionCode increases on every upload, and the versionName matches the git tag.

**Exit gate**
- [ ] `./gradlew bundleRelease` produces a signed AAB.
- [ ] The release build, installed through `bundletool build-apks --mode=universal` → `bundletool install-apks`, imports a PDF and searches correctly. This proves R8 didn't break JNI.
- [ ] Keystore backup verified: restore it to a temporary location and check that `keytool -list` reads it.

### R2: Model delivery (Phases 5–6)

- Bundle the small models (embedding model, Whisper tiny) in the base module only if they fit the size budget. Deliver the large ones (Whisper base, the QA model in Phase 9) using the R0 method, and fetch them only when the user first transcribes or opens Ask.
- Add a "model not yet available" state to the UI, with download progress. Search must still work without the large models.
- Check every model file's SHA-256 before loading it.

**Exit gate**
- [ ] `bundletool get-size total` on the AAB shows the base download under the Play limit. Record the output in NOTES.md.
- [ ] Local testing of the delivery path (`bundletool build-apks --local-testing` for asset/AI packs, or a real download): on a freshly installed phone, the models arrive, the checksums pass, and search and transcription then work in airplane mode.

### R3: Closed test starts (Phase 7, Wed 2 Dec)

Fill in the minimum the Console needs to accept a closed-test release:
- **Privacy policy:** a one-page "nothing leaves your device" policy, hosted on GitHub Pages.
- **Data safety form:** no data collected or shared. This must match the manifest: with Play-delivered models there's no internet permission.
- **Content rating questionnaire**, **target audience** (18+ is simplest), and **ads: none**.
- **Foreground service declaration:** declare the service type used for transcription and indexing, with a written reason. Record the short demo video now if the type requires one.
- **Store listing:** app name, short and full description, icon, feature graphic, at least 2 phone screenshots. Default quality is fine (spec §10).
- **Licences screen in the app:** notices for Whisper (MIT), the embedding model (check its licence) and whisper.cpp. The Gemma notice is added in Phase 9.
- Upload the v0.7 AAB (the Phase 7 build) to the **closed testing** track, add the tester Google Group, and submit it for review.

**Exit gate**
- [ ] Review passed and the closed-test release is live.
- [ ] Console shows at least 12 testers opted in. **Record the date the 14 days start in NOTES.md**; from it, compute the earliest production date.
- [ ] One tester (not you) installed the app from Play and searched their own slides.

### R4: Closed test runs (Thu 3 Dec until the 14 days are complete)

- Ship Phases 8–10 to the closed track as updates (bump the versionCode each time). The 14-day count isn't reset by new releases. Confirm this in the Help page from R0.
- Check the opted-in tester count every 2–3 days. If it gets close to 12, recruit from your spare testers before it drops below.
- Collect feedback with a short form linked in the test description. Fix crashes and ANRs reported in Play Console → Android vitals.
- Never upload a debuggable build.

**Exit gate**
- [ ] The 14 days are complete with at least 12 testers opted in the whole time (screenshot the Console).
- [ ] v1.0 (the Phase 10 tag) is on the closed track with no open crash clusters in Android vitals.
- [ ] Gemma's licence notice and use restrictions are shown in the app and the listing before the QA feature ships to testers.

### R5: Production

- Apply for production access. The Console asks how you ran the test and what feedback changed, so keep notes during R4 to answer from.
- Release to production as a **staged rollout** (e.g. 20% first, then 100% after a couple of days with no new crash clusters).
- Link the Play listing from the README, and link the GitHub repo from the listing.

**Exit gate**
- [ ] Production access granted and the release has rolled out to 100%.
- [ ] README has the Play link. Any Play-derived number on the CV (installs, ratings) is a dated screenshot in `docs/results/`, under the same rule as every other number (spec §0).

---

## Cutting scope if a day slips

Cut in this order: spec §10 polish → Phase 9 evaluation size (keep QA, judge fewer, and say so) → Phase 8 battery. **Never cut:** the Phase 2 and 3 tests, the frozen test split, the parity tests, or the Phase 7 checkpoint. The CV bullets depend on them.

**Don't let R3 slip.** Every day the closed test starts late pushes production back by a day. If Phase 7 isn't complete on Wed 2 Dec, start the closed test anyway with whatever passes Phases 1–3 and the R1/R2 gates (keyword search over slides is a usable app). Ship the rest as updates.
