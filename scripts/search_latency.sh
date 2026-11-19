#!/usr/bin/env bash
# Phase 7: on-device search latency (spec §6). Run on the measurement phone in airplane mode with the
# library imported and embedded, screen on, nothing else running.
#
#   scripts/search_latency.sh
#
# Workload: bench/queries.txt (210 mixed plain/filter/phrase/boolean queries) plus the 121 test
# questions (plain questions, which take the hybrid path). 20 warm-up queries, then each query is
# timed once on two paths (see SearchLatencyBenchTest). Writes docs/results/search_latency.csv.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
APP=com.mideyy7.retrieve.debug
RUNNER="$APP.test/androidx.test.runner.AndroidJUnitRunner"
DIR=/sdcard/Android/data/$APP/files
TMP=$(mktemp -d)

grep -v '^#' bench/queries.txt > "$TMP/workload.txt"
python3 -c "import json; [print(q['question']) for q in json.load(open('ml/data/questions.json'))['questions'] if q['split']=='test']" >> "$TMP/workload.txt"
echo "$(wc -l < "$TMP/workload.txt") queries"

./gradlew -q installDebug installDebugAndroidTest
"$ADB" push "$TMP/workload.txt" "$DIR/latency_workload.txt" >/dev/null
"$ADB" shell am instrument -w -e class com.mideyy7.retrieve.search.SearchLatencyBenchTest -e queries latency_workload.txt "$RUNNER" \
  | grep -E "^(OK|FAILURES)|AssumptionViolated|shortMsg" || true
"$ADB" pull "$DIR/search_latency.jsonl" "$TMP/out.jsonl" >/dev/null
mkdir -p docs/results/raw && cp "$TMP/out.jsonl" "docs/results/raw/search_latency-$(date +%Y%m%d-%H%M%S).jsonl"

python3 - "$TMP/out.jsonl" "$(git rev-parse --short HEAD)" <<'PY'
import csv, json, os, sys, datetime
rows = [json.loads(l) for l in open(sys.argv[1])]
def pct(xs, p):
    xs = sorted(xs); i = min(len(xs) - 1, max(0, round(p / 100 * len(xs) + 0.5) - 1)); return xs[i]
out = "docs/results/search_latency.csv"
new = not os.path.exists(out)
with open(out, "a", newline="") as f:
    w = csv.writer(f)
    if new:
        w.writerow(["date", "git_commit", "device", "soc", "android", "chunks", "path", "n", "p50_ms", "p90_ms", "p99_ms", "max_ms", "script"])
    d = rows[0]
    sets = {
        "keyword (Kotlin->JNI->C++ BM25, top 20)": [r["keyword_ns"] for r in rows if r["keyword_hits"] >= 0],
        "app search, query-language queries (keyword + Room)": [r["app_ns"] for r in rows if not r["app_hybrid"]],
        "app search, plain questions (hybrid: embed + semantic + RRF + Room)": [r["app_ns"] for r in rows if r["app_hybrid"]],
    }
    for name, ns in sets.items():
        ms = [x / 1e6 for x in ns]
        if not ms: continue
        w.writerow([datetime.date.today(), sys.argv[2], d["device"], d["soc"], d["android"], d["chunks"], name, len(ms),
                    f"{pct(ms,50):.3f}", f"{pct(ms,90):.3f}", f"{pct(ms,99):.3f}", f"{max(ms):.3f}", "scripts/search_latency.sh"])
        print(f"{name}: n={len(ms)} p50 {pct(ms,50):.3f} ms, p99 {pct(ms,99):.3f} ms")
PY
rm -rf "$TMP"
