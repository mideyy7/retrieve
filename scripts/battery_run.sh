#!/usr/bin/env bash
# Phase 8: battery, memory and index size for indexing and embedding, on the measurement phone.
#
#   scripts/battery_run.sh index <doc ids, comma-separated, ~100 slides in total>
#   scripts/battery_run.sh embed            (re-embeds the whole library)
#
# Conditions (spec §6): airplane mode, screen on, nothing else running. The USB cable stays in for
# adb, so the battery is marked unplugged with `dumpsys battery unplug` (Android then accounts the
# run as on-battery) and restored afterwards. batterystats is reset before the run; the app's
# estimated mAh is read from `dumpsys batterystats` afterwards, alongside peak memory (dumpsys
# meminfo, sampled every 2 s) and the database / index sizes. Raw outputs go to docs/results/raw/.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
APP=com.mideyy7.retrieve.debug
RUNNER="$APP.test/androidx.test.runner.AndroidJUnitRunner"
MODE="${1:?index <ids> | embed}"
STAMP=$(date +%Y%m%d-%H%M%S)
RAW=docs/results/raw; mkdir -p "$RAW"

./gradlew -q installDebug installDebugAndroidTest
"$ADB" shell am start -n $APP/com.mideyy7.retrieve.MainActivity >/dev/null; sleep 5
UID_NUM=$("$ADB" shell dumpsys package $APP | grep -m1 -oE 'userId=[0-9]+' | cut -d= -f2)
UID_TAG="u0a$((UID_NUM - 10000))"

"$ADB" shell dumpsys battery unplug
"$ADB" shell dumpsys batterystats --reset >/dev/null
START=$(date +%s)
if [[ $MODE == embed ]]; then
  "$ADB" shell am instrument -w -e class com.mideyy7.retrieve.work.ReindexTrigger#reembed -e embed all "$RUNNER" >/dev/null
  WHAT="embed whole library"
else
  "$ADB" shell am instrument -w -e class com.mideyy7.retrieve.work.ReindexTrigger#queue -e docs "${2:?doc ids}" "$RUNNER" >/dev/null
  WHAT="index docs ${2}"
fi
"$ADB" shell am start -n $APP/com.mideyy7.retrieve.MainActivity >/dev/null

# Wait for WorkManager to go idle, sampling memory (PSS) as we go.
PEAK=0
sleep 5
for _ in $(seq 1 1800); do
  PSS=$("$ADB" shell dumpsys meminfo $APP | awk '/TOTAL PSS:/ {print $3; exit} /TOTAL:/ {print $2; exit}' | tr -d '\r')
  [[ -n ${PSS:-} && $PSS -gt $PEAK ]] && PEAK=$PSS
  RUNNING=$("$ADB" shell dumpsys jobscheduler $APP | grep -c "RUNNING\|Pending" || true)
  BUSY=$("$ADB" shell dumpsys activity services $APP | grep -c SystemForegroundService || true)
  [[ $RUNNING -eq 0 && $BUSY -eq 0 ]] && break
  sleep 2
done
SECS=$(( $(date +%s) - START ))

"$ADB" shell dumpsys batterystats $APP > "$RAW/batterystats-$MODE-$STAMP.txt"
"$ADB" shell dumpsys battery reset
MAH=$(grep -m1 -E "^\s+(UID )?$UID_TAG:" "$RAW/batterystats-$MODE-$STAMP.txt" | grep -oE '[0-9]+(\.[0-9]+)?' | head -1 || echo "")
DB_BYTES=$("$ADB" exec-out run-as $APP sh -c 'stat -c %s databases/retrieve.db' | tr -d '\r')
INDEX_BYTES=$("$ADB" exec-out run-as $APP sh -c 'stat -c %s files/index/keyword.idx' | tr -d '\r' || echo "")
MODEL=$("$ADB" shell getprop ro.product.model | tr -d '\r'); SOC=$("$ADB" shell getprop ro.soc.model | tr -d '\r')

OUT=docs/results/battery.csv
[[ -f $OUT ]] || echo "date,git_commit,device,soc,android,run,seconds,app_mah,peak_pss_kb,db_bytes,index_bytes,raw_file,conditions" > $OUT
echo "$(date +%F),$(git rev-parse --short HEAD),$MODEL,$SOC,$("$ADB" shell getprop ro.build.version.release | tr -d '\r'),$WHAT,$SECS,${MAH},$PEAK,$DB_BYTES,${INDEX_BYTES},$RAW/batterystats-$MODE-$STAMP.txt,airplane mode; screen on; battery unplug simulated" >> $OUT
echo "$WHAT: ${SECS}s, ${MAH:-?} mAh, peak PSS ${PEAK} kB -> $OUT"
