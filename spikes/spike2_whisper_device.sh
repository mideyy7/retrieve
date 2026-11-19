#!/usr/bin/env bash
# Spike 2: does whisper.cpp run on the test tablet, and how fast?
#
# Pushes the NDK-built whisper-cli (v1.9.5, arm64, static), a model and an audio file to
# /data/local/tmp, transcribes, and prints the real-time factor (processing time / audio length;
# below 1.0 means faster than real time). Spike only: the app integration is Phase 6.
#
# Prereqs (see docs/NOTES.md, Spike 2): spikes/third_party/whisper.cpp built into build-android/,
# models in spikes/third_party/whisper-models/.
#
# Usage: spikes/spike2_whisper_device.sh [model] [wav] [threads]
#   model   tiny.en-q5_1 (default), base.en-q5_1, tiny.en, base.en
#   wav     16 kHz mono WAV (default: the 11 s JFK sample)
set -euo pipefail
cd "$(dirname "$0")"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
MODEL="${1:-tiny.en-q5_1}"
WAV="${2:-third_party/whisper.cpp/samples/jfk.wav}"
THREADS="${3:-4}"
BIN=third_party/whisper.cpp/build-android/bin/whisper-cli
DEV=/data/local/tmp/whisper

"$ADB" shell mkdir -p "$DEV"
"$ADB" push -q "$BIN" "$DEV/whisper-cli"
"$ADB" push -q "third_party/whisper-models/ggml-$MODEL.bin" "$DEV/"
"$ADB" push -q "$WAV" "$DEV/input.wav"
"$ADB" shell chmod 755 "$DEV/whisper-cli"

AUDIO_S=$(python3 -c "import wave,sys; w=wave.open(sys.argv[1]); print(w.getnframes()/w.getframerate())" "$WAV")
echo "model $MODEL, audio ${AUDIO_S}s, $THREADS threads, device $("$ADB" shell getprop ro.product.model)"
START=$(python3 -c 'import time; print(time.time())')
"$ADB" shell "cd $DEV && ./whisper-cli -m ggml-$MODEL.bin -f input.wav -t $THREADS -nt 2>&1" | grep -vE '^(whisper_|ggml_|system_info|main:|$)' | head -20
END=$(python3 -c 'import time; print(time.time())')
python3 -c "import sys; t=float(sys.argv[2])-float(sys.argv[1]); a=float(sys.argv[3]); print(f'wall {t:.1f}s for {a:.1f}s of audio -> real-time factor {t/a:.2f} (includes model load)')" "$START" "$END" "$AUDIO_S"
