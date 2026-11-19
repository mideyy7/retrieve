#!/usr/bin/env python3
"""Paired bootstrap confidence intervals for recall@5 on the test split.

    python ml/significance.py

With ~100 questions, each method's recall has roughly +/-4 points of uncertainty, so a claim like
"hybrid beats BM25" needs the *paired* difference (same resampled questions for both methods).
Writes docs/results/recall_ci.csv: 95% percentile intervals from 10,000 resamples, fixed seed.
"""
import numpy as np

import common
import eval as harness

N_BOOT = 10_000
K = 5


def hits(ranked, questions):
    return np.array([(r := common.hit_ranks(x, q["answers"])) is not None and r <= K for x, q in zip(ranked, questions)], dtype=float)


def main():
    common.seed_everything()
    chunks, qs = common.load_chunks(), common.load_questions("test")
    cli = common.path("native/build-release/retrieve_cli")
    int8 = common.path("models/student-l3/model_int8.tflite")
    bm25 = harness.bm25_rankings(qs, chunks, cli)
    sem = {
        "teacher MiniLM-L6 fp32": harness.rankings_from_scores(harness.semantic_scores(qs, chunks, "teacher"), chunks),
        "student-l3 fp32": harness.rankings_from_scores(harness.semantic_scores(qs, chunks, common.path("ml/checkpoints/student-l3")), chunks),
        "student-l3 int8 (LiteRT)": harness.rankings_from_scores(harness.semantic_scores(qs, chunks, int8), chunks),
    }
    methods = {"BM25 (retrieve_cli)": hits(bm25, qs), **{k: hits(v, qs) for k, v in sem.items()}}
    methods["hybrid BM25 + student-l3 int8"] = hits([harness.rrf([a, b]) for a, b in zip(bm25, sem["student-l3 int8 (LiteRT)"])], qs)

    rng = np.random.default_rng(common.SEED)
    idx = rng.integers(0, len(qs), size=(N_BOOT, len(qs)))
    def ci(x):
        boots = x[idx].mean(axis=1)
        return x.mean(), np.percentile(boots, 2.5), np.percentile(boots, 97.5)

    rows = []
    print(f"recall@{K} on {len(qs)} test questions, 95% paired bootstrap ({N_BOOT:,} resamples)")
    for name, h in methods.items():
        m, lo, hi = ci(h)
        rows.append({"comparison": name, "recall@5_or_diff": f"{m:.4f}", "ci95_low": f"{lo:.4f}", "ci95_high": f"{hi:.4f}"})
        print(f"  {name:<34} {m:.3f}  [{lo:.3f}, {hi:.3f}]")
    for a, b in [("hybrid BM25 + student-l3 int8", "BM25 (retrieve_cli)"), ("student-l3 fp32", "teacher MiniLM-L6 fp32"),
                 ("student-l3 int8 (LiteRT)", "student-l3 fp32"), ("hybrid BM25 + student-l3 int8", "student-l3 int8 (LiteRT)")]:
        d = methods[a] - methods[b]
        m, lo, hi = ci(d)
        verdict = "significant" if lo > 0 or hi < 0 else "not significant"
        rows.append({"comparison": f"{a} minus {b}", "recall@5_or_diff": f"{m:+.4f}", "ci95_low": f"{lo:+.4f}", "ci95_high": f"{hi:+.4f}"})
        print(f"  {a} - {b}: {m:+.3f} [{lo:+.3f}, {hi:+.3f}] ({verdict}; {int((d > 0).sum())} gained, {int((d < 0).sum())} lost)")
    for r in rows:
        common.append_result("recall_ci.csv", {**r, "split": "test", "n": len(qs), "resamples": N_BOOT, "seed": common.SEED, "script": "ml/significance.py"})


if __name__ == "__main__":
    main()
