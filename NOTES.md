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
- [ ] Toolchain installed (Android Studio, NDK, SDK CMake, platform for chosen targetSdk)
- [ ] Test phone chosen: model / Android version / chipset / Vulkan version
- [ ] Spike 1: LLM runtime + model
- [ ] Spike 2: whisper.cpp on phone
- [ ] Spike 3: PyTorch → LiteRT export of MiniLM, run on phone
- [ ] Spike 4: PDF text extraction library
- [ ] Corpus collected; 30-min benchmark recording SHA-256 recorded
- [ ] ≥ 120 question texts in `ml/data/questions_draft.md`
- [ ] R0: Play testing rule, targetSdk requirement, size limits recorded; ≥ 15 testers; model delivery method chosen

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
- [ ] More of the corpus: 2–3 current modules (~15–20 PDFs). Only one PDF so far.
- [ ] R1: upload key created and backed up; release build installed through bundletool and works on the tablet
