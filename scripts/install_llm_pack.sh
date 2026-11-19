#!/usr/bin/env bash
# Puts the answering model into the gemma_pack AI pack for a release bundle (bundleRelease).
# Hard-linked rather than copied (same 584 MB file, no extra disk). Checks the SHA-256 the app expects.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=spikes/third_party/litertlm/gemma3-1b-it-int4.litertlm
DST=gemma_pack/src/main/assets/gemma3-1b-it-int4.litertlm
WANT=$(grep -oE '[0-9a-f]{64}' app/src/main/java/com/mideyy7/retrieve/qa/LlmModel.kt | head -1)
[[ -f $SRC ]] || { echo "missing $SRC (download it after accepting the Gemma licence; docs/NOTES.md Spike 1)" >&2; exit 1; }
GOT=$(shasum -a 256 "$SRC" | cut -d' ' -f1)
[[ $GOT == "$WANT" ]] || { echo "sha256 mismatch: $GOT, app expects $WANT" >&2; exit 1; }
mkdir -p "$(dirname "$DST")"; rm -f "$DST"; ln "$SRC" "$DST"
echo "linked $DST ($GOT)"
