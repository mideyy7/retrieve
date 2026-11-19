#!/usr/bin/env python3
"""Inputs and expected outputs for the on-device embedding benchmark (EmbedBenchTest).

    python ml/tools/make_embed_fixture.py

Writes app/src/androidTest/assets/embed_fixture.json:
  latency_inputs  100 questions, the fixed input set timed for every model and accelerator
  parity          50 inputs (questions + WikiText-2, no lecture text) with the embeddings the laptop's
                  LiteRT interpreter produces for the student int8 and teacher fp32 .tflite files;
                  the device must reproduce them (same file, same tokenizer)
Also stages models/device/teacher/ (model.tflite, vocab.txt, model.json) for pushing to the tablet.
"""
import hashlib
import json
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import common  # noqa: E402
import litert_runner  # noqa: E402


def stage(name, tflite, out):
    os.makedirs(out, exist_ok=True)
    shutil.copy(tflite, os.path.join(out, "model.tflite"))
    shutil.copy(common.path("models", name, "vocab.txt"), os.path.join(out, "vocab.txt"))
    vocab = open(os.path.join(out, "vocab.txt")).read().splitlines()
    json.dump({"name": f"{name}/{os.path.basename(tflite)}", "sha256": hashlib.sha256(open(tflite, "rb").read()).hexdigest(),
               "dim": 384, "max_len": common.MAX_LEN, "vocab_size": len(vocab), "size_bytes": os.path.getsize(tflite)},
              open(os.path.join(out, "model.json"), "w"), indent=2)


def main():
    qs = [q["question"] for q in common.load_questions("all")]
    import pyarrow.parquet as pq
    wiki = pq.read_table(common.path("ml/data/general/wikitext-2-raw-v1/train-00000-of-00001.parquet")).column("text").to_pylist()
    wiki = [l.strip() for l in wiki if len(l.split()) >= 12][::200][:25]
    parity_inputs = qs[::4][:25] + wiki

    student = common.path("models/student-l3/model_int8.tflite")
    teacher = common.path("models/teacher/model_fp32.tflite")
    fixture = {
        "latency_inputs": qs[:100],
        "parity": {
            "inputs": parity_inputs,
            "student-l3-int8": litert_runner.TfliteEmbedder(student).embed(parity_inputs).round(6).tolist(),
            "teacher-fp32": litert_runner.TfliteEmbedder(teacher).embed(parity_inputs).round(6).tolist(),
        },
    }
    out = common.path("app/src/androidTest/assets/embed_fixture.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump(fixture, open(out, "w"))
    stage("teacher", teacher, common.path("models/device/teacher"))
    print(f"wrote {out} ({len(fixture['latency_inputs'])} latency inputs, {len(parity_inputs)} parity inputs); "
          f"staged models/device/teacher/")


if __name__ == "__main__":
    main()
