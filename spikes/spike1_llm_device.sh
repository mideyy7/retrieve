#!/usr/bin/env bash
# Spike 1: can the 3 GB test tablet run an on-device LLM, and how fast?
#
# Pushes a .litertlm model into the debug app's external files dir, runs LlmSpikeTest through the
# LiteRT-LM Android library (debug builds only), and prints load time, time to first token, decode
# speed, free memory and the answer. Uses am instrument directly so nothing is uninstalled
# (see scripts/device-tests.sh). Models live in spikes/third_party/litertlm/ (gitignored).
#
# Usage: spikes/spike1_llm_device.sh <model.litertlm> [cpu|gpu]
#   e.g. spikes/spike1_llm_device.sh gemma3-1b-it-int4.litertlm cpu
# Try in this order: Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm (343 MB),
#                    gemma3-1b-it-int4.litertlm (584 MB), gemma3-270m-it-q8.litertlm (304 MB, fallback)
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
MODEL="$1"
BACKEND="${2:-cpu}"
APP=com.mideyy7.retrieve.debug
DIR=/sdcard/Android/data/$APP/files/llm

./gradlew -q installDebug installDebugAndroidTest
"$ADB" shell mkdir -p "$DIR"
# The tablet has little free storage: keep only the model under test on it.
"$ADB" shell "ls $DIR" | grep -v "^$MODEL\$" | while read -r old; do [ -n "$old" ] && "$ADB" shell rm -f "$DIR/$old"; done
"$ADB" shell "[ -f $DIR/$MODEL ]" || "$ADB" push "spikes/third_party/litertlm/$MODEL" "$DIR/$MODEL"

"$ADB" logcat -c
out=$("$ADB" shell am instrument -w -r -e class com.mideyy7.retrieve.spikes.LlmSpikeTest -e model "$MODEL" -e backend "$BACKEND" \
      "$APP.test/androidx.test.runner.AndroidJUnitRunner")
echo "$out" | grep -E "^(OK|FAILURES)|Process crashed|shortMsg|INSTRUMENTATION_RESULT: stream" | head -5
if "$ADB" logcat -d | grep -qE "lowmemorykiller|Kill.*$APP|$APP.*(died|killed)"; then
  echo "WARNING: low-memory killer activity involving the app during the run"
fi
"$ADB" shell cat "/sdcard/Android/data/$APP/files/llm_spike.jsonl" 2>/dev/null | tail -1 | python3 -m json.tool
