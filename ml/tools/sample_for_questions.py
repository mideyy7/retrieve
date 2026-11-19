#!/usr/bin/env python3
"""Picks the slides that test questions are drafted from (stratified, informative, no near-duplicates).

Deterministic. Prints one block per candidate: id, module, week, page and text, for drafting.
Candidates are spread over every (module, week) in proportion to its number of chunks, skipping
chunks that are too short, mostly symbols (formula/OCR noise), or near-copies of one already picked.

    python3 ml/tools/sample_for_questions.py --n 140 > /tmp/candidates.txt
"""
import argparse
import json
import os
import random
import re
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
WORD = re.compile(r"[A-Za-z]{3,}")


def words(t):
    return set(w.lower() for w in WORD.findall(t))


def informative(c):
    t = c["text"]
    letters = sum(ch.isalpha() for ch in t)
    return len(WORD.findall(t)) >= 25 and letters / max(1, len(t)) > 0.6


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=140)
    ap.add_argument("--seed", type=int, default=4)
    args = ap.parse_args()
    rnd = random.Random(args.seed)
    chunks = json.load(open(os.path.join(ROOT, "ml/data/chunks.json")))["chunks"]

    groups = defaultdict(list)
    for c in chunks:
        if informative(c):
            groups[(c["module"], c["week"])].append(c)
    total = sum(len(v) for v in groups.values())
    picked, picked_words = [], []
    for key in sorted(groups):
        pool = groups[key][:]
        rnd.shuffle(pool)
        quota = max(2, round(args.n * len(pool) / total))
        for c in pool:
            if quota == 0:
                break
            w = words(c["text"])
            if any(len(w & p) / max(1, len(w | p)) > 0.6 for p in picked_words):
                continue  # near-duplicate of a slide already picked
            picked.append(c)
            picked_words.append(w)
            quota -= 1

    for i, c in enumerate(picked):
        text = " ".join(c["text"].split())[:700]
        print(f"[{i:03d}] {c['stable_key']} | {c['module']} wk{c['week']} p{c['page']} | {c['doc_name'][:40]}\n{text}\n")


if __name__ == "__main__":
    main()
