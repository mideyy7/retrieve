#!/usr/bin/env bash
# Phase 5 on-device embedding benchmark -> docs/results/embed_latency_device.csv
#
# Teacher fp32 vs distilled student int8, CPU vs GPU, on the connected test device: parity with the
# laptop's LiteRT output and inference latency (median/P95/P99 over the 100 fixed questions).
# Prereq: python ml/tools/make_embed_fixture.py (fixture + staged teacher). Never uninstalls the app.
#
# Usage: scripts/device_embed_bench.sh
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
APP=com.mideyy7.retrieve.debug
DEV=/sdcard/Android/data/$APP/files

./gradlew -q installDebug installDebugAndroidTest
"$ADB" shell mkdir -p "$DEV/bench/teacher"
for f in model.tflite vocab.txt model.json; do "$ADB" push -q "models/device/teacher/$f" "$DEV/bench/teacher/$f"; done
"$ADB" shell am force-stop "$APP"   # nothing else competing for the CPU/GPU during timing

out=$("$ADB" shell am instrument -w -r -e class com.mideyy7.retrieve.search.EmbedBenchTest -e embedBench true \
      "$APP.test/androidx.test.runner.AndroidJUnitRunner")
echo "$out" | grep -E "^(OK|FAILURES)|shortMsg" | head -3

"$ADB" shell cat "$DEV/embed_bench.jsonl" > /tmp/embed_bench_$$.jsonl
python3 - "$(git rev-parse --short HEAD)" /tmp/embed_bench_$$.jsonl <<'EOF'
import csv, datetime, json, os, sys
commit, path = sys.argv[1], sys.argv[2]
rows = [json.loads(l) for l in open(path) if l.strip()]
out = "docs/results/embed_latency_device.csv"
cols = ["date", "git_commit", "device", "android", "soc", "model", "accelerator", "model_file", "init_ms", "runs", "warmup",
        "median_ms", "p95_ms", "p99_ms", "mean_ms", "tokenize_us_per_query", "parity_inputs", "parity_min_cos", "parity_max_abs",
        "error", "script"]
new = not os.path.exists(out)
with open(out, "a", newline="") as f:
    w = csv.DictWriter(f, fieldnames=cols)
    if new: w.writeheader()
    for r in rows:
        w.writerow({**{c: r.get(c, "") for c in cols}, "date": datetime.date.today().isoformat(), "git_commit": commit,
                    "script": "scripts/device_embed_bench.sh"})
print(f"{'model':<16}{'acc':<5}{'median':>9}{'p95':>9}{'p99':>9}{'parity min cos':>16}")
for r in rows:
    if "error" in r: print(f"{r['model']:<16}{r['accelerator']:<5}  {r['error']}"); continue
    print(f"{r['model']:<16}{r['accelerator']:<5}{r['median_ms']:>8.1f}ms{r['p95_ms']:>8.1f}ms{r['p99_ms']:>8.1f}ms{r['parity_min_cos']:>16.5f}")
print(f"appended {len(rows)} rows to {out}")
EOF
rm -f /tmp/embed_bench_$$.jsonl
