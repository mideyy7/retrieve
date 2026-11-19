#!/usr/bin/env python3
"""Builds ml/data/questions.json from question draft files (IMPLEMENTATION_PLAN Phase 4).

- Answers are locations, not chunk ids: {doc_sha256, page} (plan problem 2), so labels survive
  any change to chunking or overlay collapsing.
- Every page whose words are a near-copy (Jaccard >= 0.95) of a labelled page is added as another
  correct answer: the COMP12111 handout repeats slides verbatim on different pages. 0.8 was too
  loose: Stump 'Fetch' and 'Execute' signal charts share diagram labels (0.81-0.89) and 'Lost
  Update' vs 'Temporary Update' scored 0.88. True copies in this corpus are all >= 0.97.
- About DEV_FRACTION of questions go to a dev split (stratified by module, fixed seed) for tuning;
  the rest are the frozen test split. Never tune on test.
- Fails if any answer location has no chunk in ml/data/chunks.json.

    python3 ml/tools/build_questions.py
Prints the sha256 of the canonical test split, to record in NOTES.md when freezing it.
"""
import hashlib
import json
import os
import random
import re
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DRAFTS = ["ml/data/question_drafts.json"]  # add ml/data/question_drafts_ayo.json etc. here
OUT = "ml/data/questions.json"
DEV_FRACTION = 0.16
SEED = 26120
NEAR_COPY = 0.95
WORD = re.compile(r"[a-z0-9]{3,}")


def main():
    chunks = json.load(open(os.path.join(ROOT, "ml/data/chunks.json")))["chunks"]
    by_key = {c["stable_key"]: c for c in chunks}
    page_text = defaultdict(str)
    for c in chunks:
        if c["page"] is not None:
            page_text[(c["doc_sha256"], c["page"])] += " " + c["text"]
    page_words = {k: set(WORD.findall(v.lower())) for k, v in page_text.items()}

    def near_copies(loc):
        w = page_words.get(loc, set())
        if len(w) < 15:
            return []
        return [k for k, o in page_words.items() if k != loc and len(o) >= 15 and len(w & o) / len(w | o) >= NEAR_COPY]

    questions = []
    for path in DRAFTS:
        draft = json.load(open(os.path.join(ROOT, path)))
        for key, text, extra_pages in draft["questions"]:
            if key not in by_key:
                sys.exit(f"{path}: unknown stable_key {key}")
            src = by_key[key]
            locs = [(src["doc_sha256"], src["page"])] + [(src["doc_sha256"], p) for p in extra_pages]
            for loc in list(locs):
                locs += [n for n in near_copies(loc) if n not in locs]
            questions.append({
                "question": text.strip(),
                "author": draft["author"],
                "module": src["module"],
                "source_key": key,
                "answers": [{"doc_sha256": d, "page": p} for d, p in locs],
            })

    # Stable ids and a stratified dev split.
    rnd = random.Random(SEED)
    by_module = defaultdict(list)
    for i, q in enumerate(questions):
        q["id"] = f"q{i + 1:03d}"
        by_module[q["module"]].append(q)
    for module, qs in sorted(by_module.items()):
        dev_n = max(1, round(len(qs) * DEV_FRACTION))
        dev_ids = {q["id"] for q in rnd.sample(qs, dev_n)}
        for q in qs:
            q["split"] = "dev" if q["id"] in dev_ids else "test"

    # Every answer must point at real chunks.
    locations = {(c["doc_sha256"], c["page"]) for c in chunks}
    for q in questions:
        for a in q["answers"]:
            if (a["doc_sha256"], a["page"]) not in locations:
                sys.exit(f"{q['id']}: answer {a} has no chunk in chunks.json")

    ordered = [{k: q[k] for k in ("id", "split", "author", "module", "question", "answers", "source_key")} for q in questions]
    with open(os.path.join(ROOT, OUT), "w") as f:
        json.dump({"format_version": 1, "label_unit": "page", "questions": ordered}, f, indent=2, ensure_ascii=False)
        f.write("\n")

    test = [q for q in ordered if q["split"] == "test"]
    canonical = json.dumps(test, sort_keys=True, ensure_ascii=False).encode()
    multi = sum(len(q["answers"]) > 1 for q in ordered)
    print(f"wrote {OUT}: {len(ordered)} questions, dev {len(ordered) - len(test)}, test {len(test)}, "
          f"{multi} with more than one answer page")
    for m, qs in sorted(by_module.items()):
        print(f"  {m}: {len(qs)} ({sum(q['split'] == 'dev' for q in qs)} dev)")
    print(f"test split sha256: {hashlib.sha256(canonical).hexdigest()}")


if __name__ == "__main__":
    main()
