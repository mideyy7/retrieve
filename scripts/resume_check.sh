#!/usr/bin/env bash
# Phase 8 exit gate: force-stop the app while it indexes, reopen it, and check the work resumes and
# ends with no duplicate chunks.
#
#   scripts/resume_check.sh            (uses the document with the most chunks)
#
# Steps: snapshot chunk counts -> queue re-indexing -> wait until it's mid-way -> am force-stop ->
# reopen the app (WorkManager reschedules force-stopped work on the next start) -> wait for READY ->
# compare counts and look for duplicate stable keys. Writes docs/results/resume_check.csv.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
APP=com.mideyy7.retrieve.debug
RUNNER="$APP.test/androidx.test.runner.AndroidJUnitRunner"
TMP=$(mktemp -d)

pull_db() { # $1 = destination dir; copies the Room database with its WAL so counts are current
  for f in retrieve.db retrieve.db-wal retrieve.db-shm; do
    "$ADB" exec-out run-as $APP cat "databases/$f" > "$1/$f" 2>/dev/null || rm -f "$1/$f"
  done
}
counts() { # prints: documents chunks distinct_keys status_of_doc
  python3 - "$1/retrieve.db" "$2" <<'PY'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1]); doc = int(sys.argv[2])
c = db.execute("select count(*), count(distinct stableKey) from chunks where docId=?", (doc,)).fetchone()
s = db.execute("select status from documents where id=?", (doc,)).fetchone()
print(c[0], c[1], s[0] if s else "GONE")
PY
}

./gradlew -q installDebug installDebugAndroidTest
"$ADB" shell am start -n $APP/com.mideyy7.retrieve.MainActivity >/dev/null; sleep 5
"$ADB" shell am instrument -w -e class com.mideyy7.retrieve.work.ReindexTrigger#queue -e docs "${1:-largest}" "$RUNNER" >/dev/null
# The instrumentation run ends by killing the app process, leaving the work to the job scheduler,
# which can wait minutes. Reopening the app lets WorkManager start it straight away.
"$ADB" shell am start -n $APP/com.mideyy7.retrieve.MainActivity >/dev/null
DOC=$("$ADB" shell cat /sdcard/Android/data/$APP/files/resume_check.txt | tr -d '\r' | cut -d, -f1)
mkdir -p "$TMP/before"; pull_db "$TMP/before"
read -r BEFORE_N BEFORE_K _ < <(counts "$TMP/before" "$DOC")
echo "document $DOC: $BEFORE_N chunks before"

# Wait until it is indexing (up to 10 min), then a little longer so it is mid-way, then kill it.
S=""
for _ in $(seq 1 300); do mkdir -p "$TMP/w"; pull_db "$TMP/w"; read -r _ _ S < <(counts "$TMP/w" "$DOC"); [[ $S == INDEXING ]] && break; sleep 2; done
[[ $S == INDEXING ]] || { echo "indexing never started; not a valid test" >&2; exit 1; }
sleep "${KILL_AFTER:-8}"
"$ADB" shell am force-stop $APP
mkdir -p "$TMP/killed"; pull_db "$TMP/killed"; read -r _ _ KILLED_STATUS < <(counts "$TMP/killed" "$DOC")
echo "force-stopped while $KILLED_STATUS"

"$ADB" shell am start -n $APP/com.mideyy7.retrieve.MainActivity >/dev/null
for _ in $(seq 1 600); do mkdir -p "$TMP/a"; pull_db "$TMP/a"; read -r N K S < <(counts "$TMP/a" "$DOC"); [[ $S == READY || $S == FAILED ]] && break; sleep 2; done
echo "after reopening: $S, $N chunks, $K distinct keys"

# Only a kill during INDEXING tests resuming; anything else is reported as a failed check.
ok=$([[ $KILLED_STATUS == INDEXING && $S == READY && $N == "$BEFORE_N" && $N == "$K" ]] && echo yes || echo no)
mkdir -p docs/results
[[ -f docs/results/resume_check.csv ]] || echo "date,git_commit,device,doc_id,status_when_killed,chunks_before,chunks_after,distinct_keys_after,final_status,pass" > docs/results/resume_check.csv
echo "$(date +%F),$(git rev-parse --short HEAD),$("$ADB" shell getprop ro.product.model | tr -d '\r'),$DOC,$KILLED_STATUS,$BEFORE_N,$N,$K,$S,$ok" >> docs/results/resume_check.csv
echo "pass: $ok"
rm -rf "$TMP"
[[ $ok == yes ]]
