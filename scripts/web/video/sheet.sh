#!/usr/bin/env bash
# sheet.sh clip step [start] : contact sheet of clips/<clip>.mp4 every <step> s from <start>, labelled.
cd "$(dirname "$0")"
c=$1; step=${2:-1.5}; start=${3:-0}
rm -f sheet_*.png
ffmpeg -v error -y -ss "$start" -i "clips/$c.mp4" -vf "fps=1/$step,scale=270:-1" -frames:v 40 "sheet_%02d.png"
python3 -I - "$c" "$step" "$start" <<'PY'
import glob, sys, os
from PIL import Image, ImageDraw
fs = sorted(glob.glob("sheet_[0-9]*.png")); ims = [Image.open(f).convert("RGB") for f in fs]
step, start = float(sys.argv[2]), float(sys.argv[3])
w, h = ims[0].size; cols = 8; rows = (len(ims) + cols - 1) // cols
s = Image.new("RGB", (w * cols, h * rows), "white")
for i, im in enumerate(ims):
    d = ImageDraw.Draw(im); d.rectangle((0, 0, 70, 26), fill="white"); d.text((4, 4), f"{start + i * step:.1f}s", fill="red")
    s.paste(im, ((i % cols) * w, (i // cols) * h))
s.save(f"sheet_{sys.argv[1]}.png")
for f in fs: os.remove(f)
print("ok", len(ims))
PY
