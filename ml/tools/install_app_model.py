#!/usr/bin/env python3
"""Installs the embedding model the app ships with into app/src/main/assets/embedding/.

    python ml/tools/install_app_model.py --name student-l3 --file model_int8.tflite

Copies models/<name>/<file> to assets/embedding/model.tflite (gitignored: a build output of the ML
pipeline) and writes assets/embedding/model.json (committed), which records exactly which model the
app embeds with. The app compares model.json's sha256 with the one its stored embeddings were made
with and re-embeds everything if they differ, because vectors from different models aren't comparable.
"""
import argparse
import hashlib
import json
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import common  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", default="student-l3")
    ap.add_argument("--file", default="model_int8.tflite")
    args = ap.parse_args()
    src = common.path("models", args.name, args.file)
    dest_dir = common.path("app/src/main/assets/embedding")
    os.makedirs(dest_dir, exist_ok=True)
    shutil.copy(src, os.path.join(dest_dir, "model.tflite"))
    sha = hashlib.sha256(open(src, "rb").read()).hexdigest()
    vocab = open(os.path.join(dest_dir, "vocab.txt")).read().splitlines()
    info = {"name": f"{args.name}/{args.file}", "sha256": sha, "dim": 384, "max_len": common.MAX_LEN,
            "vocab_size": len(vocab), "size_bytes": os.path.getsize(src)}
    json.dump(info, open(os.path.join(dest_dir, "model.json"), "w"), indent=2)
    print(f"installed {src} -> {dest_dir}/model.tflite ({info['size_bytes'] / 1e6:.1f} MB, sha256 {sha[:16]})")


if __name__ == "__main__":
    main()
