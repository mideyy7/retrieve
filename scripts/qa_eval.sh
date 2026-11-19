#!/usr/bin/env bash
# Runs the app's Q&A pipeline on the connected device over a question file and pulls the answers.
#
#   scripts/qa_eval.sh <questions.json> <out.jsonl> [LETTERS|NUMBERED|TAGS]
#   DETACH=1 scripts/qa_eval.sh ...   start it on the device and return; the run continues if the
#                                     laptop sleeps or the cable comes out. Collect it later with
#   scripts/qa_eval.sh --pull <out.jsonl>
#
# questions.json: [{"id": ..., "question": ...}, ...]. The model must already be on the device
# (scripts/push_llm.sh). Output lines are appended on the device, then copied to <out.jsonl>.
# Uses `am instrument` directly: connectedAndroidTest would uninstall the app and its library.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
APP=com.mideyy7.retrieve.debug
RUNNER="$APP.test/androidx.test.runner.AndroidJUnitRunner"
DIR="/sdcard/Android/data/$APP/files/qa"

if [[ "${1:-}" == "--pull" ]]; then
  NAME="$(basename "$2")"
  "$ADB" shell cat "$DIR/run.log" 2>/dev/null | grep -E "^(OK|FAILURES)|INSTRUMENTATION_(FAILED|CODE)|shortMsg" || echo "(no log yet: still running?)"
  "$ADB" pull "$DIR/$NAME" "$2" >/dev/null
  echo "$(wc -l < "$2") answers in $2"
  exit 0
fi

[[ $# -ge 2 ]] || { echo "usage: $0 <questions.json> <out.jsonl> [LETTERS|NUMBERED|TAGS]" >&2; exit 2; }
STYLE="${3:-LETTERS}"
NAME="$(basename "$2")"

./gradlew -q installDebug installDebugAndroidTest
"$ADB" shell mkdir -p "$DIR"
"$ADB" push "$1" "$DIR/input.json" >/dev/null
"$ADB" shell rm -f "$DIR/$NAME"
INSTRUMENT="am instrument -w -r -e class com.mideyy7.retrieve.qa.QaEvalTest -e questions qa/input.json -e style $STYLE -e out qa/$NAME $RUNNER"
if [[ "${DETACH:-0}" == 1 ]]; then
  "$ADB" shell rm -f "$DIR/run.log"
  "$ADB" shell "nohup $INSTRUMENT > $DIR/run.log 2>&1 &"
  echo "started on the device; collect with: $0 --pull $2"
  exit 0
fi
"$ADB" shell $INSTRUMENT \
  | grep -E "^(OK|FAILURES|Tests run)|INSTRUMENTATION_(FAILED|CODE)|shortMsg|AssumptionViolated|^[[:space:]]+at com\.mideyy7" || true
"$ADB" pull "$DIR/$NAME" "$2" >/dev/null
echo "$(wc -l < "$2") answers in $2"
