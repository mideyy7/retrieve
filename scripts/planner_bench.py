#!/usr/bin/env python3
"""Planner vs naive benchmark (IMPLEMENTATION_PLAN Phase 3) -> docs/results/planner.csv.

Builds a Release retrieve_cli, indexes ml/data/chunks.json, checks planned == naive for every
query in bench/queries.txt, then times both executors (top-10, median of --runs alternating runs
per query). Every CSV row carries the date, git commit, machine and build type.

    python3 scripts/planner_bench.py [--runs 101]
"""
import argparse
import csv
import datetime
import os
import platform
import statistics
import subprocess
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD = os.path.join(ROOT, "native/build-release")


def sh(*cmd, **kw):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw).stdout.strip()


def machine():
    try:
        cpu = sh("sysctl", "-n", "machdep.cpu.brand_string")
        model = sh("sysctl", "-n", "hw.model")
        mem = int(sh("sysctl", "-n", "hw.memsize")) // 2**30
        return f"{model} {cpu} {mem}GB macOS {platform.mac_ver()[0]}"
    except (subprocess.CalledProcessError, FileNotFoundError):
        return platform.platform()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=101)
    args = ap.parse_args()

    sh("cmake", "-S", os.path.join(ROOT, "native"), "-B", BUILD, "-DCMAKE_BUILD_TYPE=Release", "-DRETRIEVE_BUILD_TESTS=OFF")
    sh("cmake", "--build", BUILD, "--target", "retrieve_cli", "-j4")
    cli = os.path.join(BUILD, "retrieve_cli")

    commit = sh("git", "-C", ROOT, "rev-parse", "--short", "HEAD")
    if sh("git", "-C", ROOT, "status", "--porcelain", "--untracked-files=no"):
        commit += "-dirty"

    with tempfile.TemporaryDirectory() as tmp:
        idx, raw = os.path.join(tmp, "corpus.idx"), os.path.join(tmp, "raw.csv")
        sh(cli, "build", os.path.join(ROOT, "ml/data/chunks.json"), idx)
        summary = sh(cli, "bench", idx, os.path.join(ROOT, "bench/queries.txt"), "--runs", str(args.runs), "--csv", raw)
        rows = list(csv.DictReader(open(raw)))

    meta = {
        "date": datetime.date.today().isoformat(),
        "git_commit": commit,
        "machine": machine(),
        "build": "Release (clang, -O3)",
        "script": "scripts/planner_bench.py --runs %d" % args.runs,
    }
    out = os.path.join(ROOT, "docs/results/planner.csv")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(meta) + ["query", "kind", "results", "planned_us", "naive_us", "speedup"])
        w.writeheader()
        for r in rows:
            p, n = float(r["planned_us"]), float(r["naive_us"])
            w.writerow({**meta, **r, "planned_us": f"{p:.2f}", "naive_us": f"{n:.2f}", "speedup": f"{n / p:.2f}"})

    print(summary)
    print(f"wrote {out} ({len(rows)} queries, commit {commit})\n")
    print(f"{'kind':<9}{'n':>4}{'planned med us':>16}{'naive med us':>14}{'speedup med':>13}{'speedup min':>13}")
    kinds = ["plain", "phrase", "filter", "boolean", "all"]
    for k in kinds:
        sel = [r for r in rows if k == "all" or r["kind"] == k]
        if not sel:
            continue
        p = statistics.median(float(r["planned_us"]) for r in sel)
        n = statistics.median(float(r["naive_us"]) for r in sel)
        sp = [float(r["naive_us"]) / float(r["planned_us"]) for r in sel]
        print(f"{k:<9}{len(sel):>4}{p:>16.1f}{n:>14.1f}{statistics.median(sp):>13.2f}{min(sp):>13.2f}")


if __name__ == "__main__":
    main()
