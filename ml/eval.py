#!/usr/bin/env python3
"""The one evaluation harness for every retrieval method (docs/IMPLEMENTATION_PLAN.md, Phase 4).

    python ml/eval.py --method bm25 --split test
    python ml/eval.py --method semantic --model teacher --split test
    python ml/eval.py --method semantic --model ml/checkpoints/student --split test
    python ml/eval.py --method semantic --model models/student_int8.tflite --split test
    python ml/eval.py --method hybrid --model teacher --split test

A question is a hit at k if any of the top-k chunks lies on one of its labelled answer pages.
Results are appended to docs/results/recall.csv (use --no-record for exploration).
BM25 runs through retrieve_cli, i.e. the same C++ engine as the app.
"""
import argparse
import json
import os
import subprocess
import tempfile

import numpy as np

import common

K = 10


def bm25_rankings(questions, chunks, cli):
    with tempfile.TemporaryDirectory() as tmp:
        idx = os.path.join(tmp, "corpus.idx")
        subprocess.run([cli, "build", common.path("ml/data/chunks.json"), idx], check=True, capture_output=True)
        loc = {c["stable_key"]: (c["doc_sha256"], c["page"]) for c in chunks}
        out = []
        for q in questions:
            r = subprocess.run([cli, "search", idx, q["question"], "-k", str(K)], capture_output=True, text=True)
            keys = [json.loads(l)["stable_key"] for l in r.stdout.splitlines()] if r.returncode == 0 else []
            out.append([loc[k] for k in keys])
        return out


def semantic_scores(questions, chunks, model):
    """(n_questions, n_chunks) cosine similarities."""
    if model.endswith(".tflite"):
        import litert_runner
        emb = litert_runner.TfliteEmbedder(model)
        q, c = emb.embed([q["question"] for q in questions]), emb.embed([c["text"] for c in chunks])
    else:
        tok, net = common.load_embedder(common.TEACHER_ID if model == "teacher" else model)
        q, c = common.embed(tok, net, [q["question"] for q in questions]), common.embed(tok, net, [c["text"] for c in chunks])
    return q @ c.T


def rankings_from_scores(scores, chunks):
    loc = [(c["doc_sha256"], c["page"]) for c in chunks]
    # Stable sort on -score: ties keep chunk order, so results are reproducible.
    return [[loc[i] for i in np.argsort(-row, kind="stable")[:K]] for row in scores]


def rrf(rank_lists, k=60):
    """Reciprocal rank fusion over location lists: score = sum 1/(k + rank)."""
    fused = {}
    for ranks in rank_lists:
        for r, loc in enumerate(ranks, 1):
            fused[loc] = fused.get(loc, 0.0) + 1.0 / (k + r)
    return [loc for loc, _ in sorted(fused.items(), key=lambda kv: (-kv[1], kv[0]))][:K]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--method", choices=["bm25", "semantic", "hybrid"], required=True)
    ap.add_argument("--model", default="teacher")
    ap.add_argument("--split", choices=["dev", "test", "all"], default="dev")
    ap.add_argument("--cli", default=common.path("native/build-release/retrieve_cli"))
    ap.add_argument("--label", default="", help="name for the method row, e.g. 'student int8'")
    ap.add_argument("--no-record", action="store_true")
    ap.add_argument("--show-misses", action="store_true")
    ap.add_argument("--max-len", type=int, default=common.MAX_LEN, help="tokens per input (default matches the device export)")
    args = ap.parse_args()
    common.MAX_LEN = args.max_len
    common.seed_everything()

    chunks, questions = common.load_chunks(), common.load_questions(args.split)
    if args.method == "bm25":
        ranked = bm25_rankings(questions, chunks, args.cli)
    elif args.method == "semantic":
        ranked = rankings_from_scores(semantic_scores(questions, chunks, args.model), chunks)
    else:
        lexical = bm25_rankings(questions, chunks, args.cli)
        semantic = rankings_from_scores(semantic_scores(questions, chunks, args.model), chunks)
        ranked = [rrf([a, b]) for a, b in zip(lexical, semantic)]

    ranks = [common.hit_ranks(r, q["answers"]) for r, q in zip(ranked, questions)]
    m = common.metrics(ranks)
    name = args.label or (args.method if args.method == "bm25" else f"{args.method}:{args.model}")
    print(f"{name:<40} split={args.split:<5} n={m['n']:<4} "
          f"R@1={m['recall@1']:.3f} R@5={m['recall@5']:.3f} R@10={m['recall@10']:.3f} MRR@10={m['mrr@10']:.3f}")
    if args.show_misses:
        for q, r in zip(questions, ranks):
            if r is None or r > 5:
                print(f"  miss@5 {q['id']} (rank {r}): {q['question']}")
    if not args.no_record:
        common.append_result("recall.csv", {
            "method": name, "split": args.split, "n": m["n"],
            "recall@1": f"{m['recall@1']:.4f}", "recall@5": f"{m['recall@5']:.4f}",
            "recall@10": f"{m['recall@10']:.4f}", "mrr@10": f"{m['mrr@10']:.4f}",
            "max_len": common.MAX_LEN, "seed": common.SEED, "script": "ml/eval.py",
        })


if __name__ == "__main__":
    main()
