#!/usr/bin/env python3
"""Which two passages should Ask give the model?

    python ml/ask_sources.py

Ask (AskConfig.V2) takes the top 2 places from the app's hybrid ranking. On a library with lecture
captions, long conversational caption chunks can take both places even when a slide answers the
question better (seen on the tablet with an MIT OCW library). This compares, on the labelled
questions, how often a place that answers the question is among the 2 passages, for:
  top2         the current rule
  max1rec      at most one recording passage: if the top 2 are both recordings and a slide/PDF place
               is in the top 10, the second passage becomes the best slide/PDF place
Decide on dev; test is reported once as a check. Writes docs/results/ask_sources.csv.
"""
import common
import eval as harness

INT8 = common.path("models/student-l3/model_int8.tflite")
THRESHOLD = 0.4768


def is_rec(loc):
    return len(loc) == 3


def top2(ranked):
    return ranked[:2]


def max1rec(ranked):
    first = ranked[:2]
    if len(first) == 2 and all(is_rec(l) for l in first):
        slide = next((l for l in ranked[2:10] if not is_rec(l)), None)
        if slide is not None:
            return [first[0], slide]
    return first


def main():
    chunks = common.load_chunks()
    cli = common.path("native/build-release/retrieve_cli")
    spans = common.chunk_spans(chunks)
    loc = [common.location(c) for c in chunks]
    import numpy as np
    for split in ("dev", "test"):
        qs = common.load_questions(split)
        bm25 = harness.bm25_rankings(qs, chunks, cli)
        scores = harness.semantic_scores(qs, chunks, INT8)
        fused = []
        for kw, row in zip(bm25, scores):
            order = np.argsort(-row, kind="stable")[:harness.K]
            sem = [loc[i] for i in order if row[i] >= THRESHOLD]  # the app hides weak semantic matches
            fused.append(harness.rrf([kw, sem]))
        for name, rule in (("top2", top2), ("max1rec", max1rec)):
            for kind in ("all", "slides", "recordings"):
                sub = [(q, f) for q, f in zip(qs, fused)
                       if kind == "all" or (kind == "recordings") == all("start_ms" in a for a in q["answers"])]
                hits = sum(common.hit_ranks(rule(f), q["answers"], spans) is not None for q, f in sub)
                rate = hits / len(sub) if sub else 0
                print(f"{split:<5} {name:<8} {kind:<10} answer in the 2 passages: {hits}/{len(sub)} = {rate:.3f}")
                common.append_result("ask_sources.csv", {"split": split, "rule": name, "questions": kind, "n": len(sub),
                                                         "hits_in_2": hits, "rate": f"{rate:.4f}", "script": "ml/ask_sources.py"})


if __name__ == "__main__":
    main()
