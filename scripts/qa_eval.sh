#!/usr/bin/env bash
# Runs the app's Q&A pipeline on the connected device over a question file and pulls the answers.
#
#   scripts/qa_eval.sh <questions.json> <out.jsonl> [NUMBERED|TAGS]
#
# questions.json: [{"id": ..., "question": ...}, ...]. The model must already be on the device
# (scripts/push_llm.sh). Output lines are appended on the device, then copied to <out.jsonl>.
# Uses `am instrument` directly: connectedAndroidTest would uninstall the app and its library.
set -euo pipefail
cd "$(dirname "$0")/.."

[[ $# -ge 2 ]] || { echo "usage: $0 <questions.json> <out.jsonl> [NUMBERED|TAGS]" >&2; exit 2; }
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
APP=com.mideyy7.retrieve.debug
RUNNER="$APP.test/androidx.test.runner.AndroidJUnitRunner"
DIR="/sdcard/Android/data/$APP/files/qa"
STYLE="${3:-NUMBERED}"
NAME="$(basename "$2")"

./gradlew -q installDebug installDebugAndroidTest
"$ADB" shell mkdir -p "$DIR"
"$ADB" push "$1" "$DIR/input.json" >/dev/null
"$ADB" shell rm -f "$DIR/$NAME"
"$ADB" shell am instrument -w -r -e class com.mideyy7.retrieve.qa.QaEvalTest \
  -e questions qa/input.json -e style "$STYLE" -e out "qa/$NAME" "$RUNNER" \
  | grep -E "^(OK|FAILURES|Tests run)|INSTRUMENTATION_(FAILED|CODE)|shortMsg|AssumptionViolated|^[[:space:]]+at com\.mideyy7" || true
"$ADB" pull "$DIR/$NAME" "$2" >/dev/null
echo "$(wc -l < "$2") answers in $2"
