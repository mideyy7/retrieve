#!/usr/bin/env python3
"""Record scripted demo clips of Retrieve on the tablet with adb screenrecord.
    rec.py search | recording | query | ask | all
Each clip lands in clips/<name>.mp4 (1200x2000, as the device records it)."""
import os, subprocess, sys, time
sys.path.insert(0, os.path.dirname(__file__))
import ui

ADB = ui.ADB
HERE = os.path.dirname(os.path.abspath(__file__))
os.makedirs(os.path.join(HERE, "clips"), exist_ok=True)

def sh(*a): subprocess.run([ADB, "shell", *a], capture_output=True)
def tap(x, y): sh("input", "tap", str(x), str(y))

def type_slow(text, delay=0.06):
    for ch in text:
        if ch == " ": arg = "%s"
        elif ch == '"': arg = '"'
        else: arg = ch
        subprocess.run([ADB, "shell", f"input text '{arg}'" if ch != "'" else 'input text "\'"'], capture_output=True)
        time.sleep(delay)

def start(name):
    sh("rm", "-f", f"/sdcard/{name}.mp4")
    p = subprocess.Popen([ADB, "shell", "screenrecord", "--size", "1080x1800", "--bit-rate", "20000000", "--time-limit", "170", f"/sdcard/{name}.mp4"])
    time.sleep(1.2)
    return p

def stop(p, name):
    time.sleep(0.5)
    sh("pkill", "-INT", "screenrecord")
    try: p.wait(timeout=10)
    except Exception: p.kill()
    time.sleep(1.5)
    out = os.path.join(HERE, "clips", f"{name}.mp4")
    subprocess.run([ADB, "pull", f"/sdcard/{name}.mp4", out], capture_output=True)
    print("saved", out, os.path.getsize(out) if os.path.exists(out) else "MISSING")

def to_search_home():
    n = ui.find("Search", exact=True, idx=0)
    tab = [x for x in ui.nodes() if x["text"] == "Search" and x["cy"] > 1800]
    if tab: tap(tab[0]["cx"], tab[0]["cy"]); time.sleep(0.8)
    c = ui.find("Clear search")
    if c: tap(c["cx"], c["cy"]); time.sleep(0.8)
    hide_keyboard()

def hide_keyboard():
    out = subprocess.run([ADB, "shell", "dumpsys", "input_method"], capture_output=True).stdout.decode()
    if "mInputShown=true" in out:
        sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(0.8)

def search(query):
    f = ui.find("Search slides") or ui.find("EditText")
    if f: tap(f["cx"], f["cy"])
    else: tap(600, 568)
    time.sleep(0.7)
    type_slow(query)
    time.sleep(0.4)
    sh("input", "keyevent", "KEYCODE_ENTER")

def clip_search():
    to_search_home()
    p = start("search")
    time.sleep(1.5)
    search("why is binary search faster than looking at every element")
    time.sleep(3.5)
    tap(335, 500)            # the best-match slide preview opens the viewer
    time.sleep(3.0)
    sh("input", "swipe", "1000", "1000", "200", "1000", "300")   # next slide
    time.sleep(2.0)
    sh("input", "swipe", "200", "1000", "1000", "1000", "300")   # back
    time.sleep(2.0)
    stop(p, "search")
    sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1)

def clip_recording():
    to_search_home()
    p = start("recording")
    time.sleep(1.0)
    search("how does a dictionary look up a value")
    time.sleep(3.5)
    tap(335, 459)            # best match: the 37:02 caption card
    time.sleep(4.0)
    stop(p, "recording")
    sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1)

def clip_query():
    to_search_home()
    p = start("query")
    time.sleep(1.0)
    search('week:6 type:slides "towers of hanoi"')
    time.sleep(3.5)
    sh("input", "swipe", "600", "1500", "600", "900", "600")
    time.sleep(1.5)
    stop(p, "query")

def clip_ask():
    # restart the app so Ask opens empty (it keeps the last answer otherwise)
    sh("am", "force-stop", "com.mideyy7.retrieve"); time.sleep(1)
    sh("monkey", "-p", "com.mideyy7.retrieve", "-c", "android.intent.category.LAUNCHER", "1"); time.sleep(3)
    tab = [x for x in ui.nodes() if x["text"] == "Ask" and x["cy"] > 1800]
    tap(tab[0]["cx"], tab[0]["cy"]); time.sleep(1.5)
    hide_keyboard()
    p = start("ask")
    time.sleep(1.0)
    tap(600, 220); time.sleep(0.6)
    type_slow("what is the complexity of bisection search?")
    time.sleep(0.4)
    tap(1140, 220)
    t0 = time.time()
    while time.time() - t0 < 150:
        time.sleep(3)
        if any("first word after" in n["text"] for n in ui.nodes()): break
    time.sleep(3.0)
    stop(p, "ask")

if __name__ == "__main__":
    what = sys.argv[1]
    for name, fn in [("search", clip_search), ("recording", clip_recording), ("query", clip_query), ("ask", clip_ask)]:
        if what in (name, "all"): fn()
