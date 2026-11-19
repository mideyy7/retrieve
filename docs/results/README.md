# Results: where every CV number comes from

Every number on the CV or in the README traces to a row in a CSV in this folder, written by the
script in that row's `script` column, at the commit in its `git_commit` column. Nothing is typed in
by hand. Rows are only ever appended, so older runs stay visible.

Devices: laptop figures are from an Apple M1 (8 GB). On-device figures so far are from the
**development** tablet (Galaxy Tab A7, SM-T505N, Snapdragon 662, 3 GB). The final on-device numbers
are re-measured on the measurement phone (spec §6, docs/NOTES.md "development device vs measurement
device"); the device is named in every on-device row.

## CV bullet 1: on-device search

> answers queries in **[X] ms at P99**, with a C++ inverted index and BM25 ranking called from Kotlin over JNI

| Bracket | Value | CSV | Row | Command |
| --- | --- | --- | --- | --- |
| [X] ms at P99 | **to measure on the phone** | `search_latency.csv` | path `keyword (Kotlin->JNI->C++ BM25, top 20)`, column `p99_ms` | `scripts/search_latency.sh` (≥ 200 warm queries: 210 workload + 121 test questions, 20 warm-ups) |

The same run also records the app's whole search path (hybrid for plain questions) in separate rows.

## CV bullet 2: query language and planner

> a parser and planner that orders terms by selectivity and applies filters before scoring

| Claim | Value | CSV | Command |
| --- | --- | --- | --- |
| Planned vs naive, median over 210 queries × 101 runs | 8.9 µs vs 171 µs (~21×) | `planner.csv` (one row per query, `planned_us`, `naive_us`, `speedup`) | `python3 scripts/planner_bench.py --runs 101` |
| Planned and naive give identical results | 1,000 random queries, bit-identical | (test) | `ctest --test-dir native/build` (`retrieve_cli equiv`) |

## CV bullet 3: distillation and quantisation

> Distilled a sentence-embedding model from **[X]M to [Y]M parameters** and quantised it to int8,
> keeping recall@5 at **[Z]%** on a **[N]-question** labelled test set while cutting on-device latency
> from **[A] ms to [B] ms**

| Bracket | Value | CSV | Row | Command |
| --- | --- | --- | --- | --- |
| [X]M | 22.6 M (22,565,376) | `embed_latency_laptop.csv` | model `teacher MiniLM-L6 fp32 (PyTorch)`, column `params` | `python ml/baseline.py --model teacher` |
| [Y]M | 10.4 M (10,429,056) | `embed_latency_laptop.csv` | model `student-l3 fp32 (PyTorch)`, column `params` | `python ml/baseline.py --model ml/checkpoints/student-l3` |
| int8 size | 11.2 MB (from 42.0 MB) | `quantize.csv` | model `student-l3` | `python ml/quantize.py --name student-l3` |
| [Z]% | 82.6% (shipped student int8); hybrid in the app 86.8% | `recall.csv` | method `student-l3 int8 (LiteRT)` / `hybrid ...`, split `test`, column `recall@5` | `python ml/eval.py --split test` |
| [N] | 121 (101 slides, 20 recordings), frozen test split | `recall.csv`, column `n` | | test-split hash in docs/NOTES.md |
| [A] → [B] ms | 269.9 → 88.6 ms (tablet, median, CPU); **re-measure on the phone** | `embed_latency_device.csv` | models teacher / student-l3 int8, column `median_ms` | `scripts/device_embed_bench.sh` |
| Confidence intervals | e.g. hybrid − BM25 +0.033 [−0.017, +0.083] | `recall_ci.csv` | | `python ml/significance.py` |

## Supporting facts

| Claim | Value | CSV | Command |
| --- | --- | --- | --- |
| Hybrid vs BM25 recall@5 (test) | 83.5% → 86.8% (not significant at n = 121) | `recall.csv`, `recall_ci.csv` | `python ml/eval.py`, `python ml/significance.py` |
| Hiding weak meaning-only matches | recall@5 0.868 → 0.860, recall@1 0.545 → 0.570 (test); dev unchanged | `weak_filter.csv` | `python ml/weak_filter.py` |
| Recordings searchable by timestamp | 20 recording-only test questions, recall@5 0.90 (hybrid) | `recall.csv` (`--only recordings`) | `python ml/eval.py --only recordings` |
| Off-topic questions refused | 8 / 8 test, 96% of answerable answered; threshold 0.4768 tuned on dev | `refusal.csv` | `python ml/refusal.py` |
| Q&A faithfulness, **[f] of 30** | **16 / 30** (v2); first run 11 / 30 (v1) | `qa_eval_v2.csv`, `qa_eval.csv` (judgment per row) | `scripts/qa_eval.sh` then `python ml/qa_report.py score docs/results/qa_eval_v2.csv` |
| Ask speed | TTFT 20.5 s, 4.5 tok/s (tablet, v2); **re-measure on the phone** | `qa_eval_v2.csv` (`ttft_ms`, `decode_tok_s`) | same |
| LLM choice | Gemma 3 1B vs Qwen3-0.6B vs Gemma 270M | `llm_spike.csv` | `spikes/spike1_llm_device.sh` |
| Device ↔ laptop parity | keyword 40/40 identical; semantic top-1 100/100, top-5 96/100 | `parity.csv` | `scripts/parity_check.py`, `scripts/semantic_parity.py` |
| Battery per indexing / embedding run | **to measure on the phone** | `battery.csv` (+ raw in `raw/`) | `scripts/battery_run.sh index <ids>` / `embed` |
| Resumes after force-stop, no duplicates | **to run on the device** | `resume_check.csv` | `scripts/resume_check.sh` |

## Caveats kept with the numbers

- Q&A v2 was designed after reading v1's test answers (failure types, not per-question tuning), so
  16/30 is a second run on the same 30 questions, not a fresh held-out number. Both are kept.
- Q&A answers were judged by Claude at Ayo's request, each against the full text of the passages the
  model was given; the reason is in every row's `notes`.
- The lecture corpus isn't in the repo (university copyright). The questions and labels are, keyed by
  document SHA-256 and page or time range, so the evaluation reproduces on the same files.
