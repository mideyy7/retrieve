#!/usr/bin/env python3
"""Token-id parity fixture for the app's Kotlin WordPiece tokenizer (docs/IMPLEMENTATION_PLAN.md
problem 6). Records exactly what the HuggingFace tokenizer of the deployed model produces.

    python ml/tools/make_tokenizer_fixture.py --model models/student-l3

Writes app/src/test/resources/tokenizer_fixture.json (inputs + expected ids, with [CLS]/[SEP],
truncated to MAX_LEN, unpadded) and copies the vocabulary to app/src/main/assets/embedding/vocab.txt.
Inputs: the question set, WikiText-2 lines and hand-written edge cases. No lecture text, so the
fixture can be committed.
"""
import argparse
import json
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import common  # noqa: E402

EDGE_CASES = [
    "", "   ", "\t\n\r", "Hello, World!", "don't stop", "Moore’s law", "naïve café résumé Ångström",
    "ÉCOLE Straße İstanbul", "COMP26120 week:4..6 \"unit propagation\" NOT example",
    "RISC-V 16:1 mux (a|b) [x] {y} <z> ~!@#$%^&*_+=`", "¬p ∧ q → r ↔ s", "𝑫𝑩𝑴𝑺 𝑩𝒖𝒇𝒇𝒆𝒓 𝑻𝟏",
    "中文字符 and 日本語 mixed", "emoji 😀 test 🚀🚀", "zero​width‍joiner", "control\u0001\u0007chars",
    "replacement � char", "soft­hyphen", "nbsp space", "ﬁ ligature ｆｕｌｌｗｉｄｔｈ",
    "a" * 100, "b" * 101, "supercalifragilisticexpialidocious antidisestablishmentarianism",
    "x" + "y" * 99 + " short", "word " * 200, "Ünïcödé ÀÉÎÕÜ àéîõü ñ ç ø å æ œ",
    "tabs\tand\nnewlines\r\nmixed", "12345 3.14159 1,000,000 2^10 1e-9", "e-mail@example.com https://x.org/a?b=c",
    "ﬃ ﬄ ﬅ", "Ⅻ ① ²³", "Ὀδυσσεύς Ελληνικά", "русский текст", "עברית العربية",
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=common.path("models/student-l3"))
    args = ap.parse_args()
    from transformers import AutoTokenizer
    tok = AutoTokenizer.from_pretrained(args.model)

    import pyarrow.parquet as pq
    wiki = pq.read_table(common.path("ml/data/general/wikitext-2-raw-v1/train-00000-of-00001.parquet")).column("text").to_pylist()
    wiki = [l.strip() for l in wiki if len(l.split()) >= 5][::50][:300]
    inputs = [q["question"] for q in common.load_questions("all")] + wiki + EDGE_CASES

    cases = []
    for text in inputs:
        enc = tok(text, truncation=True, max_length=common.MAX_LEN)
        cases.append({"text": text, "ids": enc["input_ids"]})
    out = common.path("app/src/test/resources/tokenizer_fixture.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump({"model": os.path.basename(os.path.normpath(args.model)), "max_len": common.MAX_LEN,
               "vocab_size": tok.vocab_size, "cases": cases}, open(out, "w"), ensure_ascii=False, indent=0)
    assets = common.path("app/src/main/assets/embedding")
    os.makedirs(assets, exist_ok=True)
    shutil.copy(os.path.join(args.model, "vocab.txt"), os.path.join(assets, "vocab.txt"))
    print(f"wrote {out} ({len(cases)} cases) and {assets}/vocab.txt ({tok.vocab_size} pieces)")


if __name__ == "__main__":
    main()
