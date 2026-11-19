#!/usr/bin/env python3
"""Q&A evaluation: device answers -> the judging CSV, and the judged CSV -> the faithfulness count.

    python ml/qa_report.py table <answers.jsonl> [out.csv]   writes the judging CSV (default docs/results/qa_eval.csv)
    python ml/qa_report.py score [csv]                       counts judgments in it

v2 answers (AskConfig.V2) start with key sentences copied from the passages; the answer column shows
them as "From your slides: ..." before the model's sentences, and the whole answer is judged.

Judgments (one per row, filled in by hand):
  correct          answers the question, every claim supported by the cited passages
  partly correct   right but incomplete, or one minor slip; nothing invented
  wrong            doesn't answer it, or contradicts the passages
  unsupported claim  states something the passages don't support (a hallucination)
  correct refusal  says it isn't in the materials, and it isn't
  wrong refusal    says it isn't in the materials, but it is
"Faithful" = correct, partly correct or correct refusal: the answer stays within the materials.
"""
import csv
import json
import sys

import common

OUT = common.path("docs/results/qa_eval.csv")
FIELDS = ["id", "kind", "question", "outcome", "config", "answer", "cited", "citations_valid", "citations_repaired",
          "citations_unsupported", "sources", "ttft_ms", "decode_tok_s", "prefill_tokens", "decode_tokens",
          "device", "android", "soc", "ram_mb", "model", "style", "git_commit", "judgment", "notes"]
FAITHFUL = {"correct", "partly correct", "correct refusal"}
JUDGMENTS = FAITHFUL | {"wrong", "unsupported claim", "wrong refusal"}


def shown_answer(r):
    """The answer as the app shows it: copied key sentences first (v2), then the model's text."""
    if r["outcome"] == "refused_retrieval":
        return f"(refused before asking the model: best similarity {r.get('top_similarity', 0):.3f})"
    keys = r.get("key_sentences") or []
    quoted = " ".join(f"\u201c{k['text']}\u201d {k['source']}" for k in keys)
    return (f"From your slides: {quoted} | Model: " if keys else "") + r.get("answer", "")


def table(path, out=OUT):
    kinds = {q["id"]: q["kind"] for q in json.load(open(common.path("ml/data/qa/qa_questions.json")))}
    rows = []
    for line in open(path):
        r = json.loads(line)
        rows.append({
            **{k: r.get(k, "") for k in FIELDS},
            "kind": kinds.get(r["id"], ""),
            "config": r.get("config", "v1"),
            "answer": shown_answer(r),
            "cited": " ".join(r.get("cited", [])),
            "sources": " ".join(r.get("sources", [])),
            "decode_tok_s": f"{r['decode_tok_s']:.2f}" if "decode_tok_s" in r else "",
            "git_commit": common.git_commit(),
            "judgment": "",
            "notes": "",
        })
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        w.writeheader()
        w.writerows(rows)
    print(f"{len(rows)} rows -> {out}")


def score(path=OUT):
    rows = list(csv.DictReader(open(path)))
    missing = [r["id"] for r in rows if r["judgment"] not in JUDGMENTS]
    if missing:
        raise SystemExit(f"unjudged or unknown judgment: {missing}")
    counts = {j: sum(r["judgment"] == j for r in rows) for j in sorted(JUDGMENTS)}
    faithful = sum(r["judgment"] in FAITHFUL for r in rows)
    print(f"faithful: {faithful} of {len(rows)}")
    for j, n in counts.items():
        print(f"  {j:<18} {n}")
    for kind in ("slides", "recording", "not_in_corpus"):
        sub = [r for r in rows if r["kind"] == kind]
        if sub:
            print(f"  {kind:<14} faithful {sum(r['judgment'] in FAITHFUL for r in sub)} of {len(sub)}")
    answered = [r for r in rows if r["outcome"] in ("answered", "declined_by_model") and r["ttft_ms"]]
    if answered:
        ttft = sorted(int(r["ttft_ms"]) for r in answered)
        tps = sorted(float(r["decode_tok_s"]) for r in answered)
        print(f"  TTFT median {ttft[len(ttft) // 2] / 1000:.1f} s, decode median {tps[len(tps) // 2]:.2f} tok/s "
              f"(n={len(answered)}, {answered[0]['device']}, {answered[0]['soc']})")


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "table":
        table(sys.argv[2], *(sys.argv[3:4]))
    elif len(sys.argv) in (2, 3) and sys.argv[1] == "score":
        score(*(sys.argv[2:3]))
    else:
        raise SystemExit(__doc__)
