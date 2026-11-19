# Device runbook: everything left that needs a device

Run top to bottom in one sitting. Steps marked **(phone)** must run on the measurement phone for the
CV numbers; on the tablet they only check that the scripts work. Keep the phone in airplane mode,
screen on, charger cable in (the battery script simulates unplugged).

| # | Step | Command | Writes / checks |
| --- | --- | --- | --- |
| 0 | Free disk on the Mac: ≥ 3 GB for builds, ≥ 5 GB for the release bundle | `df -h /` | |
| 1 | Install the current build, keep the library | `./gradlew installDebug` | |
| 2 | Import a PDF of image slides: OCR still works with no network permission; the "Indexing … page N of M" notification shows | by hand | |
| 3 | Ask screen: "cant" typo question shows key sentences, not a refusal; quoted sources aren't "read, not cited" | by hand | |
| 4 | Force-stop mid-indexing resumes without duplicates (Phase 8 gate) | `scripts/resume_check.sh` | `docs/results/resume_check.csv` |
| 5 | **(phone)** Copy the library to the phone (import the same 21 files), wait until embedded | by hand | |
| 6 | **(phone)** Search latency, P50/P99 (Phase 7, CV bullet 1) | `scripts/search_latency.sh` | `docs/results/search_latency.csv` |
| 7 | **(phone)** Embedding latency, teacher vs student (CV bullet 3) | `scripts/device_embed_bench.sh` | `docs/results/embed_latency_device.csv` |
| 8 | **(phone)** Battery: index ~100 slides, then embed the library | `scripts/battery_run.sh index <ids>`, `scripts/battery_run.sh embed` | `docs/results/battery.csv`, raw in `docs/results/raw/` |
| 9 | **(phone)** Ask speed and the 30 questions on the phone (v2) | `scripts/push_llm.sh`, then `DETACH=1 CONFIG=v2 scripts/qa_eval.sh ml/data/qa/qa_questions.json ml/data/qa/runs/qa_answers_phone_v2.jsonl` | judge → `docs/results/qa_eval_phone_v2.csv` |
| 10 | Release bundle with the Gemma pack; base size under the limit (R2 gate) | `scripts/install_llm_pack.sh && ./gradlew bundleRelease`, then `bundletool get-size total --bundle app/build/outputs/bundle/release/app-release.aab` | record in NOTES |
| 11 | Local AI-pack delivery test on a fresh install (R2 gate) | `bundletool build-apks --bundle … --output out.apks --local-testing && bundletool install-apks --apks out.apks` | Ask → download card → pack arrives → checksum passes → answer in airplane mode |
| 12 | Upload to the closed test (R3), record the start date of the 14 days | Play Console; answers in `docs/play-console.md` | NOTES |

After 6–9, update `docs/results/README.md` (the "to measure" cells) and tell the README/site session
the new numbers.
