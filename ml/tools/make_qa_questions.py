#!/usr/bin/env python3
"""Fixes the 30 Q&A evaluation questions before any answer is generated (Phase 9).

    python ml/tools/make_qa_questions.py

From the test split only: 20 slide/PDF questions and 5 recording questions (questions.json), and
5 questions whose answers aren't in the corpus (unanswerable.json), sampled with a fixed seed.
Writes ml/data/qa/qa_questions.json; rerunning gives the same file.
"""
import json
import random
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import common  # noqa: E402

SEED = 30


def main():
    rng = random.Random(SEED)
    test = common.load_questions("test")
    slides = sorted((q for q in test if all("page" in a for a in q["answers"])), key=lambda q: q["id"])
    recordings = sorted((q for q in test if all("start_ms" in a for a in q["answers"])), key=lambda q: q["id"])
    negatives = sorted((q for q in json.load(open(common.path("ml/data/unanswerable.json")))["questions"] if q["split"] == "test"),
                       key=lambda q: q["id"])
    picked = (
        [dict(id=q["id"], question=q["question"], kind="slides", module=q["module"]) for q in rng.sample(slides, 20)]
        + [dict(id=q["id"], question=q["question"], kind="recording", module=q["module"]) for q in rng.sample(recordings, 5)]
        + [dict(id=q["id"], question=q["question"], kind="not_in_corpus", module=None) for q in rng.sample(negatives, 5)]
    )
    out = common.path("ml/data/qa/qa_questions.json")
    json.dump(picked, open(out, "w"), indent=1)
    print(f"{len(picked)} questions -> {out}")


if __name__ == "__main__":
    main()
