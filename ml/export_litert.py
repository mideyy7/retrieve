#!/usr/bin/env python3
"""Exports an embedding model to LiteRT (fp32) with a numerical parity check against PyTorch.

    python ml/export_litert.py --model teacher --name teacher
    python ml/export_litert.py --model ml/checkpoints/student-l3 --name student-l3

Writes models/<name>/model_fp32.tflite plus the tokenizer files (tokenizer.json, vocab.txt) the
app and litert_runner need. Parity: 50 fixed inputs (25 lecture chunks, 25 questions) through
PyTorch and LiteRT; fails if the max abs difference exceeds PARITY_TOL. Inputs are int32 (the
converter downcasts int64), batch 1, MAX_LEN tokens. Appends a row to docs/results/parity.csv.
"""
import argparse
import os

import numpy as np
import torch

import common

PARITY_TOL = 1e-4  # on L2-normalised 384-d vectors; fp32 conversion differences are ~1e-7


def parity_inputs():
    chunks = common.load_chunks()
    qs = common.load_questions("all")
    step_c, step_q = max(1, len(chunks) // 25), max(1, len(qs) // 25)
    return [c["text"] for c in chunks[::step_c][:25]] + [q["question"] for q in qs[::step_q][:25]]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--name", required=True)
    args = ap.parse_args()
    common.seed_everything()

    model_id = common.TEACHER_ID if args.model == "teacher" else args.model
    tok, net = common.load_embedder(model_id)
    out_dir = common.path("models", args.name)
    os.makedirs(out_dir, exist_ok=True)
    tok.save_pretrained(out_dir)
    if not os.path.exists(os.path.join(out_dir, "vocab.txt")):
        inv = sorted(tok.get_vocab().items(), key=lambda kv: kv[1])
        open(os.path.join(out_dir, "vocab.txt"), "w").write("\n".join(t for t, _ in inv) + "\n")

    import litert_torch
    sample = tok(["sample"], padding="max_length", max_length=common.MAX_LEN, return_tensors="pt")
    edge = litert_torch.convert(net, (sample["input_ids"].to(torch.int32), sample["attention_mask"].to(torch.int32)))
    fp32 = os.path.join(out_dir, "model_fp32.tflite")
    edge.export(fp32)

    import litert_runner
    texts = parity_inputs()
    ref = common.embed(tok, net, texts, batch_size=1)
    got = litert_runner.TfliteEmbedder(fp32).embed(texts)
    max_abs = float(np.abs(ref - got).max())
    min_cos = float((ref * got).sum(-1).min())
    mb = os.path.getsize(fp32) / 1e6
    print(f"{fp32}: {mb:.1f} MB; parity on {len(texts)} inputs: max abs diff {max_abs:.2e}, min cosine {min_cos:.6f}")
    common.append_result("parity.csv", {
        "model": args.name, "file": os.path.relpath(fp32, common.ROOT), "size_mb": f"{mb:.1f}", "inputs": len(texts),
        "max_abs_diff": f"{max_abs:.3e}", "min_cosine": f"{min_cos:.6f}", "tolerance": PARITY_TOL,
        "passed": max_abs <= PARITY_TOL, "script": "ml/export_litert.py",
    })
    if max_abs > PARITY_TOL:
        raise SystemExit(f"PARITY FAILED: {max_abs:.2e} > {PARITY_TOL}")


if __name__ == "__main__":
    main()
