#!/usr/bin/env bash
# Copies the answering model (Gemma 3 1B int4, LiteRT-LM) to the debug app on the connected device.
# Development only: release builds will get it as a Play AI pack (docs/IMPLEMENTATION_PLAN.md, R2).
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
MODEL=spikes/third_party/litertlm/gemma3-1b-it-int4.litertlm
[[ -f $MODEL ]] || { echo "missing $MODEL (see docs/NOTES.md, Spike 1)" >&2; exit 1; }
"$ADB" shell mkdir -p /sdcard/Android/data/com.mideyy7.retrieve.debug/files/llm
"$ADB" push "$MODEL" /sdcard/Android/data/com.mideyy7.retrieve.debug/files/llm/
