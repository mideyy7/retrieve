#!/usr/bin/env python3
"""Device-vs-CLI parity: the same queries must return the same chunks, in the same order, with the
same BM25 scores on the tablet (JNI, NDK build) and on the laptop (retrieve_cli).

Prereqs: the app has been opened (so its index exists), ml/data/chunks.json is a fresh export of
the same library, and native/build/retrieve_cli is built.

    python3 scripts/parity_check.py
"""
import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
CLI = os.path.join(ROOT, "native/build/retrieve_cli")
APP = "com.mideyy7.retrieve.debug"
K = 10
# Device scores cross JNI as float32; the CLI prints doubles.
REL_TOL = 1e-5


def run(cmd, **kw):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw).stdout


def main():
    run([os.path.join(ROOT, "scripts/device-tests.sh"), "com.mideyy7.retrieve.search.ParityDumpTest"])
    device = [json.loads(l) for l in run([ADB, "exec-out", "cat",
              f"/sdcard/Android/data/{APP}/files/parity_results.jsonl"]).splitlines() if l.strip()]

    with tempfile.TemporaryDirectory() as tmp:
        idx = os.path.join(tmp, "corpus.idx")
        run([CLI, "build", os.path.join(ROOT, "ml/data/chunks.json"), idx])
        mismatches = 0
        for d in device:
            cli = [json.loads(l) for l in run([CLI, "search", idx, d["query"], "-k", str(K)]).splitlines()]
            dk, ck = [h["key"] for h in d["hits"]], [h["stable_key"] for h in cli]
            scores_ok = all(abs(a["score"] - b["score"]) <= REL_TOL * max(1.0, abs(b["score"]))
                            for a, b in zip(d["hits"], cli))
            ok = dk == ck and scores_ok
            mismatches += not ok
            print(f"{'OK  ' if ok else 'DIFF'} {len(dk):2d} hits  {d['query']}")
            if not ok:
                print("     device:", dk[:5], "\n     cli:   ", ck[:5])
    print(f"\n{len(device) - mismatches}/{len(device)} queries identical (top-{K} keys, order, scores)")
    sys.exit(1 if mismatches else 0)


if __name__ == "__main__":
    main()
