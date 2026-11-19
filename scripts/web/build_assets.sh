#!/usr/bin/env bash
# Regenerates the website's generated images in web/assets/:
#   og.png               1200x630 link preview, from scripts/web/og.html
#   apple-touch-icon.png 180x180, from web/assets/favicon.svg
#   qr-play.svg          QR code to the Play listing (verified by decoding it)
# Needs macOS with Google Chrome and Swift (Xcode command line tools). Run from the repo root.
set -euo pipefail
cd "$(dirname "$0")/../.."

CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
PLAY_URL="https://play.google.com/store/apps/details?id=com.mideyy7.retrieve&utm_source=website&utm_medium=qr"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

shot() { # url width height out
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
    --virtual-time-budget=4000 --window-size="$2,$3" --screenshot="$4" "$1" >/dev/null 2>&1
}

shot "file://$PWD/scripts/web/og.html" 1200 630 web/assets/og.png

cat > "$TMP/icon.html" <<EOF
<html><body style="margin:0;background:#3A47D5"><img src="file://$PWD/web/assets/favicon.svg" style="width:180px;height:180px;display:block"></body></html>
EOF
shot "file://$TMP/icon.html" 180 180 web/assets/apple-touch-icon.png

swift scripts/web/make_qr.swift "$PLAY_URL" web/assets/qr-play.svg

ls -l web/assets/og.png web/assets/apple-touch-icon.png web/assets/qr-play.svg
