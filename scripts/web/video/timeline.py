#!/usr/bin/env python3
"""The Retrieve hero video timeline.
    timeline.py preview          key frames -> preview.png
    timeline.py render out.mp4   full render
    timeline.py frame 12.3 x.png one frame
"""
import os, subprocess, sys, time
from PIL import Image, ImageDraw
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import compose as C

HERE = C.HERE
USE_CLIPS = all(os.path.isdir(os.path.join(HERE, "clips", n)) for n in ("search", "recording", "query", "ask"))

def src(name, still, dur, segments=None):
    if USE_CLIPS and segments: return C.Clip(name, segments)
    return C.Still(os.path.join(HERE, still), dur)

# Clip timings (seconds in the source recording). Filled in after recording; stills otherwise.
T = {}
exec(open(os.path.join(HERE, "timings.py")).read(), T)

HOOK_END = 3.4
LEFT_POP_X, LEFT_POP_W = 600, 760

def scenes():
    s, t = [], HOOK_END
    def add(sc):
        nonlocal t
        sc["start"] = t; sc["dur"] = sc["src"].duration; t += sc["dur"]; s.append(sc)
    add(dict(src=src("search", "q4.png", 7.0, T.get("SEARCH")),
             caption=("SEARCH BY MEANING", ["Type what", "you *remember."], ["It finds the slide that means it,", "not just the one with your words."], False),
             chips=[dict(t=T.get("SEARCH_RESULTS", 3.6), until=6.4, label="121 ms · on the tablet", x=1040, y=300, kind="light", icon="bolt")],
             pops=[]))
    add(dict(src=src("search", "q3.png", 4.6, T.get("VIEWER")),
             caption=("SEE THE REAL SLIDE", ["The actual page,", "your words *marked."], ["Swipe to the slides either side."], False),
             chips=[dict(t=.6, until=5.2, label="Week 12 › slide 9", x=1040, y=640, kind="brand")]))
    add(dict(src=src("recording", "q1.png", 5.6, T.get("RECORDING")),
             caption=("LECTURE RECORDINGS", ["Jump to the", "*minute it was said."], ["Captions from the lecture portal,", "searchable in 30-second chunks."], False),
             chips=[dict(t=T.get("RECORDING_RESULTS", 2.6), until=8.4, label="37:02 in the lecture", x=1040, y=330, kind="brand")]))
    add(dict(src=src("query", "q3.png", 4.8, T.get("QUERY")),
             caption=("WHEN YOU KNOW ROUGHLY WHERE", ["Narrow it", "*down."], ['week:6 type:slides "towers of hanoi"'], True),
             chips=[dict(t=T.get("QUERY_RESULTS", 2.4), until=5.9, label="2 ms", x=1040, y=300, kind="light", icon="bolt")]))
    ask = dict(src=src("ask", "a5.png", 7.4, T.get("ASK")),
               caption=("ASK", ["Ask. See the", "*source."], ["Key sentences from your slides first,", "then a short answer written on the phone."], False),
               chips=[dict(t=T.get("ASK_DONE", 5.0), until=14.0, label="O(log n) · Week 12 › slide 8", x=1040, y=520, kind="brand")])
    if "ASK_FAST" in T: ask["fast"] = T["ASK_FAST"]; ask["fast_label"] = T.get("ASK_FAST_LABEL", "sped up")
    add(ask)
    return s, t

def make_logo():
    p = os.path.join(HERE, "logo.png")
    if os.path.exists(p): return
    im = Image.open(os.path.join(C.ROOT, "web/assets/apple-touch-icon.png")).convert("RGBA").resize((192, 192), Image.LANCZOS)
    m = Image.new("L", (192, 192), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, 191, 191), 44, fill=255)
    im.putalpha(m); im.save(p)

def timeline():
    make_logo()
    sc, end = scenes()
    total = end + 3.6
    return sc, end, total

def frame(t):
    sc, end, total = timeline()
    return C.render_frame(t, sc, HOOK_END, end, total)

if __name__ == "__main__":
    cmd = sys.argv[1]
    sc, end, total = timeline()
    if cmd == "frame":
        C.render_frame(float(sys.argv[2]), sc, HOOK_END, end, total).save(sys.argv[3]); print("ok")
    elif cmd == "preview":
        ts = [float(x) for x in sys.argv[2:]] or [1.6, 3.9, 6.5, 9.6, 12.5, 16.5, 21.5, 26.5, 31.0, total - 1.6]
        thumbs = [C.render_frame(t, sc, HOOK_END, end, total).resize((960, 480)) for t in ts]
        cols = 2; rows = (len(thumbs) + 1) // 2
        sheet = Image.new("RGB", (960 * cols + 10, 490 * rows), (40, 40, 40))
        for i, th in enumerate(thumbs): sheet.paste(th, ((i % cols) * 970, (i // cols) * 490))
        sheet.save(os.path.join(HERE, "preview.png")); print("total", round(total, 2), "s; scenes", [(round(s["start"], 1), round(s["dur"], 1)) for s in sc])
    elif cmd == "render":
        out = sys.argv[2]; n = int(total * C.FPS); t0 = time.time()
        p = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{C.W}x{C.H}",
                              "-r", str(C.FPS), "-i", "-", "-c:v", "libx264", "-preset", "medium", "-crf", "10", "-pix_fmt", "yuv420p", out],
                             stdin=subprocess.PIPE)
        for i in range(n):
            p.stdin.write(C.render_frame(i / C.FPS, sc, HOOK_END, end, total).tobytes())
            if i % 90 == 0: print(f"{i}/{n} {time.time() - t0:.0f}s", flush=True)
        p.stdin.close(); p.wait(); print("done", out, f"{time.time() - t0:.0f}s")
