#!/usr/bin/env python3
"""Frame-by-frame compositor for the Retrieve hero video.

    compose.py preview            contact sheet of key frames -> preview.png
    compose.py render out.mp4     full render (H.264 intermediate, near-lossless)

Real tablet footage (clips/<name>/%05d.jpg at 30 fps, extracted by extract()) or stills sit in a
device frame; captions, chips and pop-out cards are drawn on top with eased motion.
"""
import math, os, subprocess, sys, json
from functools import lru_cache
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
FONTS = os.path.join(ROOT, "app/src/main/res/font/")
TALL = os.environ.get("LAYOUT") == "tall"
W, H, FPS = (1080, 1350, 30) if TALL else (1920, 960, 30)
CAP_X, CAP_CY, CAP_SIZE = (72, 250, 70) if TALL else (130, H / 2, 78)
HOOK_LINES = ["Six slide decks.", "Two lectures.", "*One *question."] if TALL else ["Six slide decks. Two lectures.", "*One *question."]
HOOK_SIZE = 96 if TALL else 104

INK = (15, 17, 21); MUTED = (74, 75, 87); FAINT = (138, 139, 152); BG = (247, 247, 245)
BRAND = (58, 71, 213); BRAND_SOFT = (224, 224, 255); MARK = (255, 226, 77); WHITE = (255, 255, 255)

# ---------- easing ----------
def clamp(x, a=0.0, b=1.0): return max(a, min(b, x))
def ease_out(x): x = clamp(x); return 1 - (1 - x) ** 3
def ease_in_out(x): x = clamp(x); return 4 * x ** 3 if x < .5 else 1 - (-2 * x + 2) ** 3 / 2
def ease_back(x, s=1.7):
    x = clamp(x); x -= 1; return x * x * ((s + 1) * x + s) + 1
def prog(t, t0, dur): return clamp((t - t0) / dur) if dur > 0 else float(t >= t0)
def lerp(a, b, k): return a + (b - a) * k

# ---------- fonts ----------
@lru_cache(None)
def font(kind, size, weight=None):
    f = ImageFont.truetype(FONTS + {"display": "bricolage_grotesque.ttf", "body": "instrument_sans.ttf",
                                     "mono": "jetbrains_mono.ttf"}[kind], size)
    try:
        if kind == "display": f.set_variation_by_axes([min(96, max(12, size)), weight or 700, 100])
        elif kind == "body": f.set_variation_by_axes([100, weight or 400])
        else: f.set_variation_by_axes([weight or 500])
    except Exception: pass
    return f

@lru_cache(None)
def text_img(s, kind, size, color, weight=None, track=0.0):
    f = font(kind, size, weight)
    if track:
        widths = [f.getlength(c) + track * size for c in s]
        w = int(sum(widths)) + 4
    else:
        w = int(f.getlength(s)) + 4
    asc, desc = f.getmetrics()
    im = Image.new("RGBA", (max(w, 1), asc + desc + 4), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if track:
        x = 0
        for c, cw in zip(s, widths): d.text((x, 0), c, font=f, fill=color); x += cw
    else:
        d.text((0, 0), s, font=f, fill=color)
    return im

def paste(canvas, im, x, y, alpha=1.0):
    if alpha <= 0: return
    if alpha < 1:
        im = im.copy(); a = im.getchannel("A").point(lambda v: int(v * alpha)); im.putalpha(a)
    canvas.alpha_composite(im, (int(round(x)), int(round(y))))

# ---------- background ----------
@lru_cache(None)
def glow(rgb, r, a):
    g = Image.new("RGBA", (r * 2, r * 2), (0, 0, 0, 0))
    yy, xx = np.mgrid[0:r * 2, 0:r * 2]
    d = np.sqrt((xx - r) ** 2 + (yy - r) ** 2) / r
    alpha = (np.clip(1 - d, 0, 1) ** 2 * a * 255).astype(np.uint8)
    arr = np.zeros((r * 2, r * 2, 4), np.uint8); arr[..., :3] = rgb; arr[..., 3] = alpha
    return Image.fromarray(arr, "RGBA")

def background(t):
    c = Image.new("RGBA", (W, H), BG + (255,))
    g1 = glow((58, 71, 213), 620, .16); g2 = glow((255, 226, 77), 460, .10); g3 = glow((120, 130, 240), 520, .10)
    c.alpha_composite(g1, (int(1250 + 120 * math.sin(t * .35)) - 620, int(-180 + 60 * math.cos(t * .3)) - 620 + 400))
    c.alpha_composite(g2, (int(-120 + 80 * math.cos(t * .27)) - 460 + 300, int(820 + 50 * math.sin(t * .4)) - 460))
    c.alpha_composite(g3, (int(1700 + 70 * math.cos(t * .22)) - 520, int(900 + 40 * math.sin(t * .33)) - 520))
    return c

# ---------- sources ----------
CROP_TOP, CROP_BOT = 0.02, 0.965   # drop status bar and system nav bar, keep the app's tab bar

class Clip:
    """Frames of a recorded clip, with a time remap: segments of (src_start, src_end, speed)."""
    def __init__(self, name, segments):
        self.dir = os.path.join(HERE, "clips", name); self.segments = segments
        self.n = len([f for f in os.listdir(self.dir) if f.endswith(".jpg")])
        self.duration = sum((b - a) / s for a, b, s in segments)
    def src_time(self, t):
        for a, b, s in self.segments:
            d = (b - a) / s
            if t <= d: return a + t * s
            t -= d
        return self.segments[-1][1]
    def frame(self, t):
        i = min(self.n, max(1, int(round(self.src_time(t) * FPS)) + 1))
        return load_jpg(os.path.join(self.dir, f"{i:05d}.jpg"))
    def speed(self, t):
        for a, b, s in self.segments:
            d = (b - a) / s
            if t <= d: return s
            t -= d
        return 1

class Still:
    def __init__(self, path, duration): self.path = path; self.duration = duration
    def frame(self, t): return load_jpg(self.path)
    def speed(self, t): return 1

@lru_cache(maxsize=8)
def load_jpg(path):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    return im.crop((0, int(h * CROP_TOP), w, int(h * CROP_BOT)))

# ---------- device ----------
SCREEN_H = 800
def screen_size(scale=1.0, src=None):
    sw, sh = (src.size if src else (1080, int(1800 * (CROP_BOT - CROP_TOP))))
    h = SCREEN_H * scale; return int(h * sw / sh), int(h)

@lru_cache(None)
def round_mask(w, h, r):
    m = Image.new("L", (w * 2, h * 2), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, w * 2 - 1, h * 2 - 1), r * 2, fill=255)
    return m.resize((w, h), Image.LANCZOS)

@lru_cache(None)
def device_shell(w, h):
    """Bezel + soft shadow around a w x h screen. Returns (image, screen offset)."""
    bez, pad = 16, 70
    sw, sh = w + bez * 2, h + bez * 2
    im = Image.new("RGBA", (sw + pad * 2, sh + pad * 2), (0, 0, 0, 0))
    sh_im = Image.new("RGBA", im.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh_im).rounded_rectangle((pad + 10, pad + 40, pad + sw - 10, pad + sh + 10), 46, fill=(30, 34, 90, 120))
    im.alpha_composite(sh_im.filter(ImageFilter.GaussianBlur(34)))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((pad, pad, pad + sw, pad + sh), 44, fill=(16, 17, 20, 255))
    d.rounded_rectangle((pad + 2, pad + 2, pad + sw - 2, pad + sh - 2), 42, outline=(60, 62, 70, 255), width=2)
    d.ellipse((pad + sw // 2 - 4, pad + 6, pad + sw // 2 + 4, pad + 14), fill=(40, 42, 50, 255))
    return im, (pad + bez, pad + bez)

def draw_device(canvas, scr, cx, cy, scale=1.0, alpha=1.0, rot=0.0):
    w, h = screen_size(scale, scr)
    shell, (ox, oy) = device_shell(w, h)
    dev = shell.copy()
    s = scr.resize((w, h), Image.LANCZOS).convert("RGBA")
    s.putalpha(round_mask(w, h, 30))
    dev.alpha_composite(s, (ox, oy))
    if rot: dev = dev.rotate(rot, resample=Image.BICUBIC, expand=True)
    paste(canvas, dev, cx - dev.width / 2, cy - dev.height / 2, alpha)
    return (cx - w / 2, cy - h / 2, w, h)   # screen rect on canvas (unrotated)

# ---------- captions ----------
def caption(canvas, t, t_in, t_out, eyebrow, lines, sub=None, x=None, cy=None, size=None, mono_sub=False):
    x = CAP_X if x is None else x; cy = CAP_CY if cy is None else cy; size = CAP_SIZE if size is None else size
    """Eyebrow + headline words rising in one by one + sub; whole block lifts and fades out."""
    if t < t_in - .01 or t > t_out + .45: return
    out_k = ease_in_out(prog(t, t_out, .4))
    lift = -36 * out_k; fade_out = 1 - out_k
    line_h = int(size * 1.02)
    sub_imgs = []
    if sub:
        for s in sub:
            sub_imgs.append(text_img(s, "mono" if mono_sub else "body", 34 if not mono_sub else 30,
                                     BRAND if mono_sub else MUTED, 500 if mono_sub else 400))
    total = 44 + line_h * len(lines) + (24 + 46 * len(sub_imgs) if sub_imgs else 0)
    y = cy - total / 2 + lift
    eb = text_img(eyebrow, "mono", 22, BRAND, 600, track=0.12)
    k = ease_out(prog(t, t_in, .45))
    paste(canvas, eb, x, y + 14 * (1 - k), k * fade_out)
    pill_w = 36
    y += 44
    i = 0
    for line in lines:
        wx = x
        for wi, word in enumerate(line.split(" ")):
            accent = word.startswith("*")
            word = word.strip("*")
            im = text_img(word, "display", size, BRAND if accent else INK, 700)
            k = ease_out(prog(t, t_in + .12 + i * .07, .55))
            paste(canvas, im, wx, y + 40 * (1 - k), k * fade_out)
            wx += im.width + size * .24 - 4
            i += 1
        y += line_h
    if sub_imgs:
        y += 24
        for j, im in enumerate(sub_imgs):
            k = ease_out(prog(t, t_in + .35 + i * .07 + j * .08, .5))
            paste(canvas, im, x, y + 18 * (1 - k), k * fade_out)
            y += 46

# ---------- chips & pop-outs ----------
@lru_cache(None)
def chip_img(label, kind="light", icon=None):
    f = text_img(label, "body" if kind != "mono" else "mono", 30, INK if kind in ("light", "mono") else WHITE, 600)
    pw, ph = f.width + 52 + (34 if icon else 0), 66
    im = Image.new("RGBA", (pw + 60, ph + 60), (0, 0, 0, 0))
    sh = Image.new("RGBA", im.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle((30, 40, 30 + pw, 30 + ph + 6), ph // 2, fill=(20, 24, 80, 70))
    im.alpha_composite(sh.filter(ImageFilter.GaussianBlur(12)))
    fill = {"light": WHITE, "mono": WHITE, "brand": BRAND, "dark": INK, "warn": (224, 161, 0)}[kind]
    ImageDraw.Draw(im).rounded_rectangle((30, 30, 30 + pw, 30 + ph), ph // 2, fill=fill + (255,))
    x = 30 + 26
    if icon == "dot":
        ImageDraw.Draw(im).ellipse((x, 30 + ph / 2 - 7, x + 14, 30 + ph / 2 + 7), fill=(224, 161, 0) if kind != "warn" else WHITE)
        x += 34
    if icon == "bolt":
        ImageDraw.Draw(im).polygon([(x + 10, 30 + 16), (x, 30 + 36), (x + 9, 30 + 36), (x + 4, 30 + 52), (x + 18, 30 + 28), (x + 9, 30 + 28)], fill=MARK if kind != "light" else BRAND)
        x += 34
    im.alpha_composite(f, (int(x), int(30 + (ph - f.height) / 2 + 2)))
    return im

def chip(canvas, t, t_in, t_out, label, x, y, kind="light", icon=None):
    if t < t_in or t > t_out + .3: return
    k = ease_back(prog(t, t_in, .5)); o = 1 - ease_in_out(prog(t, t_out, .3))
    im = chip_img(label, kind, icon)
    s = max(.01, .6 + .4 * k)
    im2 = im.resize((max(1, int(im.width * s)), max(1, int(im.height * s))), Image.LANCZOS) if s != 1 else im
    paste(canvas, im2, x - im2.width / 2, y - im2.height / 2 + 10 * (1 - k), min(1, prog(t, t_in, .2)) * o)

def popout(canvas, t, t_in, t_out, scr, box, cx, cy, width, rect):
    """Lift a region of the screen out of the device and enlarge it beside the device."""
    if t < t_in or t > t_out + .4: return
    k = ease_out(prog(t, t_in, .6)); o = 1 - ease_in_out(prog(t, t_out, .4))
    sw, sh = scr.size
    region = scr.crop((int(box[0] * sw), int(box[1] * sh), int(box[2] * sw), int(box[3] * sh)))
    # start where the region sits on the device screen, end at (cx, cy) with the given width
    rx, ry, rw, rh = rect
    sx = rx + (box[0] + box[2]) / 2 * rw; sy = ry + (box[1] + box[3]) / 2 * rh; s_w = (box[2] - box[0]) * rw
    w = lerp(s_w, width, k); h = w * region.height / region.width
    x = lerp(sx, cx, k); y = lerp(sy, cy, k)
    card = region.resize((int(w), int(h)), Image.LANCZOS).convert("RGBA")
    card.putalpha(round_mask(card.width, card.height, int(18 * w / width) + 4))
    pad = 40
    frame = Image.new("RGBA", (card.width + pad * 2, card.height + pad * 2), (0, 0, 0, 0))
    shd = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    ImageDraw.Draw(shd).rounded_rectangle((pad, pad + 14, pad + card.width, pad + card.height + 10), 22, fill=(20, 24, 80, int(110 * k)))
    frame.alpha_composite(shd.filter(ImageFilter.GaussianBlur(18)))
    ImageDraw.Draw(frame).rounded_rectangle((pad - 3, pad - 3, pad + card.width + 3, pad + card.height + 3), 22, fill=(255, 255, 255, 255))
    frame.alpha_composite(card, (pad, pad))
    paste(canvas, frame, x - frame.width / 2, y - frame.height / 2, o)

def speed_tag(canvas, t, scene_clip, local_t, rect, t_vis):
    s = scene_clip.speed(local_t) if hasattr(scene_clip, "speed") else 1
    if s > 1.5:
        rx, ry, rw, rh = rect
        chip(canvas, t, t_vis[0], t_vis[1], f"{int(round(s))}× speed", rx + rw - 120, ry + 70, "dark")

# ---------- hook & end ----------
def hook(canvas, t, t_out):
    o = 1 - ease_in_out(prog(t, t_out, .5)); lift = -40 * ease_in_out(prog(t, t_out, .5))
    lines = HOOK_LINES
    size = HOOK_SIZE; y0 = H / 2 - 150 - (60 if TALL else 0) + lift
    i = 0
    for li, line in enumerate(lines):
        ims = [(w.strip("*"), w.startswith("*")) for w in line.split(" ")]
        imgs = [text_img(w, "display", size, BRAND if a else INK, 700) for w, a in ims]
        total = sum(im.width for im in imgs) + (len(imgs) - 1) * (size * .24 - 4)
        x = W / 2 - total / 2
        for im in imgs:
            k = ease_out(prog(t, .15 + i * .08, .6))
            paste(canvas, im, x, y0 + li * size * 1.02 + 50 * (1 - k), k * o)
            x += im.width + size * .24 - 4; i += 1
    # scrubber: a lecture timeline the playhead sweeps back and forth on
    bw = 860 if TALL else 980; bx, by = W / 2 - bw / 2, H / 2 + (230 if TALL else 150) + lift
    k = ease_out(prog(t, .5, .6))
    if k > 0:
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(layer)
        d.rounded_rectangle((bx, by, bx + bw * k, by + 12), 6, fill=(228, 227, 234, 255))
        ph = .5 + .45 * math.sin(t * 3.1) * math.cos(t * 1.3)
        px = bx + bw * ph * k
        d.rounded_rectangle((bx, by, px, by + 12), 6, fill=BRAND + (255,))
        d.ellipse((px - 14, by - 8, px + 14, by + 20), fill=WHITE + (255,), outline=BRAND + (255,), width=5)
        canvas.alpha_composite(Image.blend(Image.new("RGBA", (W, H), (0, 0, 0, 0)), layer, k * o) if k * o < 1 else layer)
        sec = int(ph * 2893); stamp = f"{sec // 60}:{sec % 60:02d}"
        paste(canvas, text_img(stamp, "mono", 26, MUTED, 500), bx, by + 34, k * o)
        paste(canvas, text_img("48:13", "mono", 26, FAINT, 500), bx + bw - 80, by + 34, k * o)

def end_card(canvas, t, t_in):
    k = ease_out(prog(t, t_in, .7))
    logo = Image.open(os.path.join(HERE, "logo.png")).convert("RGBA").resize((96, 96), Image.LANCZOS)
    name = text_img("Retrieve", "display", 64, INK, 700)
    gw = logo.width + 22 + name.width
    paste(canvas, logo, W / 2 - gw / 2, H / 2 - 230 + 30 * (1 - k), k)
    paste(canvas, name, W / 2 - gw / 2 + logo.width + 22, H / 2 - 230 + 14 + 30 * (1 - k), k)
    i = 0; size = 100 if TALL else 112; y = H / 2 - 90
    for li, line in enumerate(["Find the slide,", "*not *the *folder."]):
        ims = [(w.strip("*"), w.startswith("*")) for w in line.split(" ")]
        imgs = [text_img(w, "display", size, BRAND if a else INK, 700) for w, a in ims]
        total = sum(im.width for im in imgs) + (len(imgs) - 1) * (size * .24 - 4)
        x = W / 2 - total / 2
        for im in imgs:
            kk = ease_out(prog(t, t_in + .2 + i * .07, .6))
            paste(canvas, im, x, y + li * size * 1.0 + 50 * (1 - kk), kk)
            x += im.width + size * .24 - 4; i += 1
    labels = [("Free", "light", None), ("Works offline", "light", None), ("No internet permission", "brand", None)]
    imgs = [chip_img(a, b, c) for a, b, c in labels]
    total = sum(im.width for im in imgs) - 40 * (len(imgs) - 1)
    x = W / 2 - total / 2
    for j, im in enumerate(imgs):
        kk = ease_back(prog(t, t_in + .8 + j * .1, .5))
        paste(canvas, im, x, H / 2 + 165 + 16 * (1 - kk), clamp(kk))
        x += im.width - 40
    credit = text_img("Demo material: MIT OpenCourseWare 6.0001 (Fall 2016), CC BY-NC-SA 4.0", "body", 22, FAINT, 400)
    paste(canvas, credit, W / 2 - credit.width / 2, H - 58, ease_out(prog(t, t_in + 1.0, .6)))

# ---------- the timeline ----------
DEVICE_X, DEVICE_TOP, DEVICE_SCALE = (540, 520, 1.62) if TALL else (1395, 96, 1.85)

def scene_pan(cur, t, scenes):
    """Vertical pan (px) of the tablet. Each scene has pan keys [(local_t, px)], eased between keys;
    the first key blends from the previous scene's last value."""
    keys = cur.get("pan", [(0, 0)])
    lt = t - cur["start"]
    idx = scenes.index(cur)
    prev_last = scenes[idx - 1].get("pan", [(0, 0)])[-1][1] if idx > 0 else keys[0][1]
    pts = [(-0.0001, prev_last)] + [(max(0.0, a), b) for a, b in keys]
    if lt <= pts[1][0] + .6 and pts[1][0] == 0:   # ease from the previous scene's pan at the cut
        return lerp(prev_last, pts[1][1], ease_in_out(lt / .6))
    v = pts[-1][1]
    for (a, va), (b, vb) in zip(pts, pts[1:]):
        if a <= lt <= b: v = lerp(va, vb, ease_in_out((lt - a) / max(1e-3, b - a))); break
        if lt > b: v = vb
    return v

def build(cfg):
    """cfg: list of scenes {src, start, dur, caption:(eyebrow, lines, sub, mono), chips:[...], pops:[...]}"""
    return cfg

def render_frame(t, scenes, hook_end, end_start, total):
    c = background(t)
    if t < hook_end + .6: hook(c, t, hook_end - .2)
    # device scenes
    active = [s for s in scenes if s["start"] - .4 <= t <= s["start"] + s["dur"] + .4]
    first, last = scenes[0], scenes[-1]
    dev_in = ease_out(prog(t, first["start"] - .25, .9))
    dev_out = ease_in_out(prog(t, end_start - .2, .7))
    if dev_in > 0 and dev_out < 1:
        # pick the scene whose window contains t; cross-fade screens across boundaries
        cur = None
        for s in scenes:
            if s["start"] <= t < s["start"] + s["dur"]: cur = s
        if cur is None: cur = first if t < first["start"] else last
        lt = clamp(t - cur["start"], 0, cur["src"].duration - 1e-3)
        scr = cur["src"].frame(lt)
        idx = scenes.index(cur)
        if idx > 0 and t - cur["start"] < .35:
            prev = scenes[idx - 1]
            pscr = prev["src"].frame(prev["src"].duration - 1e-3)
            k = ease_in_out((t - cur["start"]) / .35)
            if pscr.size == scr.size: scr = Image.blend(pscr, scr, k)
        # big tablet anchored near the top, bleeding off the bottom edge; per-scene pan moves it up
        scale = DEVICE_SCALE
        pan = scene_pan(cur, t, scenes)
        _, sh = screen_size(scale, scr)
        cx = lerp(W + 700, DEVICE_X, dev_in) + lerp(0, 900, dev_out)
        cy = DEVICE_TOP + sh / 2 - pan + lerp(160, 0, dev_in)
        rot = lerp(7, 0, dev_in) + lerp(0, -5, dev_out)
        pulse = 1.0
        if idx > 0 and t - cur["start"] < .5: pulse = 1 - .02 * math.sin(math.pi * (t - cur["start"]) / .5)
        rect = draw_device(c, scr, cx, cy, scale * pulse, 1 - dev_out, rot)
        for s in active:
            st = s["start"]
            for p in s.get("pops", []):
                popout(c, t, st + p["t"], st + p["until"], scr if s is cur else s["src"].frame(clamp(t - st, 0, s["src"].duration - 1e-3)), p["box"], p["cx"], p["cy"], p["w"], rect)
            for ch in s.get("chips", []):
                # chips sit on the tablet's top edge, clear of the app's content
                x, y = rect[0] + rect[2] / 2, max(52, rect[1] - 6)
                chip(c, t, st + ch["t"], st + ch["until"], ch["label"], x, y, ch.get("kind", "light"), ch.get("icon"))
        if "fast" in cur:
            a, b = cur["fast"]
            chip(c, t, cur["start"] + a, cur["start"] + b, cur.get("fast_label", "sped up"), rect[0] + rect[2] / 2, rect[1] - 6, "dark", "bolt")
    for s in scenes:
        e, lines, sub, mono = s["caption"]
        caption(c, t, s["start"] + .05, s["start"] + s["dur"] - .35, e, lines, sub, mono_sub=mono)
    if t >= end_start: end_card(c, t, end_start + .2)
    # fade to the hook's first frame for a seamless loop
    if t > total - .5:
        k = ease_in_out((t - (total - .5)) / .5)
        c = Image.blend(c, background(0), k)
    return c.convert("RGB")
