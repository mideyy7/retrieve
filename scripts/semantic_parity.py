#!/usr/bin/env python3
"""Device-vs-evaluation semantic parity (IMPLEMENTATION_PLAN Phase 5 gate).

The device embeds its library and 100 fixed questions itself (SemanticParityDumpTest); here the
laptop does the same with the same .tflite (ml/litert_runner) over ml/data/chunks.json, as
ml/eval.py does. Reports how many questions have an identical top-5 (keys and order) and how many
have the same top-1, and the largest score difference. Requires the library on the device to equal
chunks.json (same export).

    python3 scripts/semantic_parity.py
"""
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "ml"))
import common  # noqa: E402
import litert_runner  # noqa: E402

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
APP = "com.mideyy7.retrieve.debug"


def main():
    subprocess.run([os.path.join(ROOT, "scripts/device-tests.sh"), "com.mideyy7.retrieve.search.SemanticParityDumpTest"], check=True)
    device = [json.loads(l) for l in subprocess.run([ADB, "exec-out", "cat", f"/sdcard/Android/data/{APP}/files/semantic_parity.jsonl"],
                                                     capture_output=True, text=True, check=True).stdout.splitlines() if l.strip()]
    chunks = sorted(common.load_chunks(), key=lambda c: c["stable_key"])
    emb = litert_runner.TfliteEmbedder(common.path("models/student-l3/model_int8.tflite"))
    c = emb.embed([x["text"] for x in chunks])
    q = emb.embed([d["question"] for d in device])
    scores = q @ c.T
    exact = top1 = 0
    max_diff = 0.0
    for d, row in zip(device, scores):
        order = sorted(range(len(chunks)), key=lambda i: (-row[i], i))[:5]
        laptop = [chunks[i]["stable_key"] for i in order]
        dev = [h["key"] for h in d["hits"]]
        exact += laptop == dev
        top1 += laptop[0] == dev[0]
        for h in d["hits"]:
            i = next(j for j, x in enumerate(chunks) if x["stable_key"] == h["key"])
            max_diff = max(max_diff, abs(h["score"] - row[i]))
        if laptop != dev:
            print(f"  differs: {d['question'][:60]}\n    device {dev}\n    laptop {laptop}")
    n = len(device)
    print(f"device indexed {device[0]['indexed_chunks']} chunks, laptop {len(chunks)}")
    print(f"identical top-5 (keys and order): {exact}/{n}; same top-1: {top1}/{n}; max score difference {max_diff:.2e}")


if __name__ == "__main__":
    main()
