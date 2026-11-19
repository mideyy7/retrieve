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
