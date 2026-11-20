#!/usr/bin/env python3
"""Tiny adb UI driver: tap elements by text / content-desc, type, screenshot.
    ui.py tap "Library"          tap first node whose text or desc contains this (case-insensitive)
    ui.py tapx "Library"         exact match
    ui.py list [filter]          list visible text/desc nodes with centres
    ui.py shot out.png           screenshot
"""
import os, re, subprocess, sys, time
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")

def adb(*a, out=False):
    r = subprocess.run([ADB, *a], capture_output=True)
    return r.stdout if out else r.returncode

def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/ui.xml", out=True).decode("utf-8", "replace")
    res = []
    for m in re.finditer(r'<node [^>]*>', xml):
        n = m.group(0)
        g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ""])[1]
        b = re.findall(r'\d+', g("bounds"))
        if len(b) != 4: continue
        x1, y1, x2, y2 = map(int, b)
        res.append(dict(text=g("text"), desc=g("content-desc"), rid=g("resource-id"), cls=g("class"),
                        click=g("clickable"), cx=(x1 + x2) // 2, cy=(y1 + y2) // 2, b=(x1, y1, x2, y2)))
    return res

def find(q, exact=False, idx=0):
    ql = q.lower()
    hits = [n for n in nodes() if (n["text"].lower() == ql or n["desc"].lower() == ql) if exact] if exact else \
           [n for n in nodes() if ql in n["text"].lower() or ql in n["desc"].lower()]
    return hits[idx] if len(hits) > idx else None

if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd in ("tap", "tapx"):
        idx = int(sys.argv[3]) if len(sys.argv) > 3 else 0
        n = find(sys.argv[2], exact=(cmd == "tapx"), idx=idx)
        if not n: print("NOT FOUND", sys.argv[2]); sys.exit(1)
        adb("shell", "input", "tap", str(n["cx"]), str(n["cy"])); print("tapped", n["text"] or n["desc"], n["cx"], n["cy"])
    elif cmd == "list":
        f = sys.argv[2].lower() if len(sys.argv) > 2 else ""
        for n in nodes():
            label = n["text"] or n["desc"]
            if label and f in label.lower(): print(f'{n["cx"]:5} {n["cy"]:5}  {label[:90]!r}  {n["cls"].split(".")[-1]}')
    elif cmd == "shot":
        open(sys.argv[2], "wb").write(adb("exec-out", "screencap", "-p", out=True)); print("saved", sys.argv[2])
