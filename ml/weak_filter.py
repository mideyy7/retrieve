#!/usr/bin/env python3
"""Does hiding weak meaning-only matches cost recall?

    python ml/weak_filter.py

The app's search fuses keyword and semantic rankings. A query that isn't covered by the library
(e.g. "raft") still gets ten semantic matches, all weak. This drops semantic matches whose cosine
is below the Q&A refusal threshold (0.4768, tuned on dev in ml/refusal.py) before fusion, and
reports hybrid recall@5 with and without the filter on dev and test. Keyword matches are never
dropped. Writes docs/results/weak_filter.csv.
"""
import numpy as np

import common
import eval as harness

THRESHOLD = 0.4768
INT8 = common.path("models/student-l3/model_int8.tflite")


def main():
    chunks = common.load_chunks()
    cli = common.path("native/build-release/retrieve_cli")
    spans = common.chunk_spans(chunks)
    loc = [common.location(c) for c in chunks]
    for split in ("dev", "test"):
        qs = common.load_questions(split)
        bm25 = harness.bm25_rankings(qs, chunks, cli)
        scores = harness.semantic_scores(qs, chunks, INT8)
        rows = {}
        for name, cut in (("hybrid", None), ("hybrid, weak semantic hidden", THRESHOLD)):
            ranks = []
            for q, kw, row in zip(qs, bm25, scores):
                order = np.argsort(-row, kind="stable")[:harness.K]
                sem = [loc[i] for i in order if cut is None or row[i] >= cut]
                ranks.append(common.hit_ranks(harness.rrf([kw, sem]), q["answers"], spans))
            m = common.metrics(ranks)
            rows[name] = m
            print(f"{split:<5} {name:<30} recall@5 {m['recall@5']:.3f}  recall@1 {m['recall@1']:.3f}")
            common.append_result("weak_filter.csv", {"split": split, "method": name, "threshold": cut if cut else "",
                                                     "n": m["n"], "recall@1": f"{m['recall@1']:.4f}", "recall@5": f"{m['recall@5']:.4f}",
                                                     "script": "ml/weak_filter.py"})


if __name__ == "__main__":
    main()
