#!/usr/bin/env python3
"""Post-training int8 quantisation of an exported LiteRT model (docs/IMPLEMENTATION_PLAN.md, Phase 4).

    python ml/quantize.py --name student-l3

Reads models/<name>/model_fp32.tflite and writes models/<name>/model_int8.tflite using
ai-edge-quantizer's dynamic-range recipe: weights stored as int8, activations computed in float.
No calibration data is needed, so nothing from the question set can leak in. Reports size and how
far the int8 embeddings drift from fp32 on the parity inputs; appends to docs/results/quantize.csv.
The int8 file is what the phone runs, and ml/eval.py measures its recall directly.
"""
import argparse
import os

import numpy as np

import common
import export_litert
import litert_runner


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", required=True)
    args = ap.parse_args()
    d = common.path("models", args.name)
    fp32, int8 = os.path.join(d, "model_fp32.tflite"), os.path.join(d, "model_int8.tflite")

    from ai_edge_quantizer import quantizer, recipe
    qt = quantizer.Quantizer(fp32)
    qt.load_quantization_recipe(recipe.dynamic_wi8_afp32())
    qt.quantize().export_model(int8, overwrite=True)

    texts = export_litert.parity_inputs()
    a, b = litert_runner.TfliteEmbedder(fp32).embed(texts), litert_runner.TfliteEmbedder(int8).embed(texts)
    cos = (a * b).sum(-1)
    s32, s8 = os.path.getsize(fp32) / 1e6, os.path.getsize(int8) / 1e6
    print(f"{args.name}: fp32 {s32:.1f} MB -> int8 {s8:.1f} MB ({s32 / s8:.2f}x smaller); "
          f"int8 vs fp32 cosine: mean {cos.mean():.5f}, min {cos.min():.5f}")
    common.append_result("quantize.csv", {
        "model": args.name, "recipe": "dynamic_wi8_afp32", "fp32_mb": f"{s32:.1f}", "int8_mb": f"{s8:.1f}",
        "ratio": f"{s32 / s8:.2f}", "inputs": len(texts), "mean_cos_vs_fp32": f"{cos.mean():.5f}",
        "min_cos_vs_fp32": f"{cos.min():.5f}", "script": "ml/quantize.py",
    })


if __name__ == "__main__":
    main()
