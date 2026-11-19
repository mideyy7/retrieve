#!/usr/bin/env python3
"""Tunes the Q&A refusal threshold: below it, the app says "not in your materials" instead of answering.

    python ml/refusal.py

Signal: the highest cosine similarity between the question and any chunk, from the same int8 student
model the app runs (so the number means the same thing on the phone). Tuned on the dev split only:
the dev answerable questions (questions.json) against the dev out-of-corpus questions
(unanswerable.json). The threshold maximises balanced accuracy (mean of "answers what it should" and
"refuses what it should"); ties go to the lowest threshold, preferring to answer. The test split is
then scored once with that threshold as a held-out check. Writes docs/results/refusal.csv.
"""
import json

import numpy as np

import common
import eval as harness

INT8 = common.path("models/student-l3/model_int8.tflite")


def max_cosine(questions, chunks):
    return harness.semantic_scores(questions, chunks, INT8).max(axis=1)


def rates(pos, neg, t):
    answered = float((pos >= t).mean())
    refused = float((neg < t).mean())
    return answered, refused, (answered + refused) / 2


def tune(pos, neg):
    """Candidate thresholds are midpoints between neighbouring observed scores."""
    s = np.unique(np.concatenate([pos, neg]))
    candidates = np.concatenate([[s[0] - 1e-6], (s[:-1] + s[1:]) / 2, [s[-1] + 1e-6]])
    best = max(candidates, key=lambda t: (rates(pos, neg, t)[2], -t))
    return float(best)


def main():
    chunks = common.load_chunks()
    neg_all = json.load(open(common.path("ml/data/unanswerable.json")))["questions"]
    out = {}
    for split in ("dev", "test"):
        pos = max_cosine(common.load_questions(split), chunks)
        neg = max_cosine([q for q in neg_all if q["split"] == split], chunks)
        out[split] = (pos, neg)
        print(f"{split}: answerable n={len(pos)} max-cos median {np.median(pos):.3f} [min {pos.min():.3f}]; "
              f"not-in-corpus n={len(neg)} median {np.median(neg):.3f} [max {neg.max():.3f}]")

    t = tune(*out["dev"])
    print(f"threshold (dev) = {t:.4f}")
    for split, (pos, neg) in out.items():
        answered, refused, bal = rates(pos, neg, t)
        print(f"  {split}: answers {answered:.3f} of answerable, refuses {refused:.3f} of not-in-corpus, balanced {bal:.3f}")
        common.append_result("refusal.csv", {
            "split": split, "threshold": f"{t:.4f}", "tuned_on": "dev", "signal": "max cosine, student-l3 int8",
            "n_answerable": len(pos), "n_unanswerable": len(neg),
            "answered_rate": f"{answered:.4f}", "refused_rate": f"{refused:.4f}", "balanced_accuracy": f"{bal:.4f}",
            "script": "ml/refusal.py",
        })


if __name__ == "__main__":
    main()
