#!/usr/bin/env python3
"""Model facts for the results table: parameter breakdown, size on disk, laptop latency.

    python ml/baseline.py --model teacher
    python ml/baseline.py --model ml/checkpoints/student

Latency: one query at a time (batch 1, MAX_LEN tokens), CPU, fixed thread count, median and P95
of the 100 longest-to-shortest-mixed dev+test questions after 10 warm-up runs. Appends a row to
docs/results/embed_latency_laptop.csv. Recall comes from ml/eval.py, not from here.
"""
import argparse
import os
import tempfile
import time

import numpy as np
import torch

import common

THREADS = 4


def size_mb(model):
    with tempfile.TemporaryDirectory() as tmp:
        p = os.path.join(tmp, "m.pt")
        torch.save(model.state_dict(), p)
        return os.path.getsize(p) / 1e6


@torch.no_grad()
def latency_ms(tok, model, texts, warmup=10):
    torch.set_num_threads(THREADS)
    def once(t):
        enc = tok([t], padding="max_length", truncation=True, max_length=common.MAX_LEN, return_tensors="pt")
        start = time.perf_counter()
        model(enc["input_ids"], enc["attention_mask"])
        return (time.perf_counter() - start) * 1000
    for t in texts[:warmup]:
        once(t)
    times = np.array([once(t) for t in texts])
    return float(np.median(times)), float(np.percentile(times, 95))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="teacher")
    ap.add_argument("--label", default="")
    ap.add_argument("--no-record", action="store_true")
    args = ap.parse_args()
    common.seed_everything()

    model_id = common.TEACHER_ID if args.model == "teacher" else args.model
    tok, model = common.load_embedder(model_id)
    total, groups = common.param_breakdown(model.encoder)
    print(f"{args.model}: {total:,} parameters  hidden={model.encoder.config.hidden_size} "
          f"layers={model.encoder.config.num_hidden_layers} vocab={model.encoder.config.vocab_size} "
          f"positions={model.encoder.config.max_position_embeddings}")
    for k, v in sorted(groups.items(), key=lambda kv: -kv[1])[:8]:
        print(f"  {k:<45} {v:>11,}  {100 * v / total:5.1f}%")

    texts = [q["question"] for q in common.load_questions("all")][:100]
    med, p95 = latency_ms(tok, model, texts)
    mb = size_mb(model)
    print(f"size {mb:.1f} MB (fp32 state dict)   laptop CPU latency median {med:.2f} ms, p95 {p95:.2f} ms ({THREADS} threads)")
    if not args.no_record:
        common.append_result("embed_latency_laptop.csv", {
            "model": args.label or args.model, "params": total, "size_mb": f"{mb:.1f}",
            "median_ms": f"{med:.2f}", "p95_ms": f"{p95:.2f}", "threads": THREADS, "batch": 1,
            "max_len": common.MAX_LEN, "inputs": len(texts), "torch": torch.__version__, "script": "ml/baseline.py",
        })


if __name__ == "__main__":
    main()
