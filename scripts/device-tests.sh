#!/usr/bin/env bash
# Runs the instrumented (on-device) tests WITHOUT uninstalling the app afterwards.
#
# Don't use `./gradlew connectedDebugAndroidTest` on the test device: it uninstalls the debug app
# when it finishes, which deletes the imported library (this happened on 2026-11-19).
#
# Usage: scripts/device-tests.sh [test class or class#method]
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
APP=com.mideyy7.retrieve.debug
RUNNER="$APP.test/androidx.test.runner.AndroidJUnitRunner"

./gradlew -q installDebug installDebugAndroidTest

args=(-w -r)
if [[ $# -gt 0 ]]; then args+=(-e class "$1"); fi

out=$("$ADB" shell am instrument "${args[@]}" "$RUNNER")
echo "$out" | grep -E "^(OK|FAILURES|Tests run)|INSTRUMENTATION_(FAILED|CODE)|shortMsg|^[[:space:]]+at com\.mideyy7" || true
echo "$out" | grep -q "^OK (" || { echo "device tests FAILED" >&2; exit 1; }
