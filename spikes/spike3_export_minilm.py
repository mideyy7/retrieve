"""Spike 3: can a stock MiniLM sentence-embedding model be exported to LiteRT?

Answers three questions before Phase 4/5 depend on them:
  1. Where do the teacher's parameters live? (IMPLEMENTATION_PLAN problem 7)
  2. Does litert-torch convert BERT + mean pooling?
  3. Does the .tflite output match PyTorch on a few sentences?

Run from the repo root:  .venv/bin/python spikes/spike3_export_minilm.py
Output model: models/spike/minilm_fp32.tflite (gitignored). Copy it to the phone for the on-device half.
"""
import os
import time

import numpy as np
import torch
from transformers import AutoModel, AutoTokenizer

MODEL_ID = "sentence-transformers/all-MiniLM-L6-v2"
SEQ_LEN = 128
OUT_DIR = "models/spike"
SENTENCES = [
    "Raft ensures log matching by checking the previous index and term.",
    "Amortised analysis of a dynamic array gives O(1) append.",
    "What is the difference between a process and a thread?",
    "Dijkstra's algorithm fails with negative edge weights.",
]

torch.manual_seed(0)


class Embedder(torch.nn.Module):
    """BERT encoder + attention-masked mean pooling + L2 norm (what sentence-transformers does)."""

    def __init__(self, bert):
        super().__init__()
        self.bert = bert

    def forward(self, input_ids, attention_mask):
        hidden = self.bert(input_ids=input_ids, attention_mask=attention_mask).last_hidden_state
        mask = attention_mask.unsqueeze(-1).to(hidden.dtype)
        pooled = (hidden * mask).sum(1) / mask.sum(1).clamp(min=1e-9)
        return torch.nn.functional.normalize(pooled, dim=-1)


def param_breakdown(model):
    groups = {}
    for name, p in model.named_parameters():
        key = ".".join(name.split(".")[:3]) if ".layer." in name else ".".join(name.split(".")[:2])
        groups[key] = groups.get(key, 0) + p.numel()
    total = sum(groups.values())
    print(f"\nTotal parameters: {total:,}")
    for k, v in sorted(groups.items(), key=lambda kv: -kv[1]):
        print(f"  {k:<40} {v:>12,}  {100 * v / total:5.1f}%")
    return total


def main():
    tok = AutoTokenizer.from_pretrained(MODEL_ID)
    bert = AutoModel.from_pretrained(MODEL_ID).eval()
    model = Embedder(bert).eval()

    print(f"Model: {MODEL_ID}  hidden={bert.config.hidden_size} layers={bert.config.num_hidden_layers} "
          f"vocab={bert.config.vocab_size}")
    param_breakdown(bert)

    enc = tok(SENTENCES, padding="max_length", truncation=True, max_length=SEQ_LEN, return_tensors="pt")
    ids, mask = enc["input_ids"], enc["attention_mask"]
    with torch.no_grad():
        ref = model(ids, mask).numpy()

    import litert_torch  # imported late so the breakdown above prints even if conversion fails

    t0 = time.time()
    edge = litert_torch.convert(model, (ids[:1], mask[:1]))
    print(f"\nConverted in {time.time() - t0:.1f}s")

    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, "minilm_fp32.tflite")
    edge.export(path)
    print(f"Exported {path}: {os.path.getsize(path) / 1e6:.1f} MB")

    # The converter downcasts int64 inputs to int32 (enable_x64=False), so the app must feed int32 token ids too.
    ids32, mask32 = ids.to(torch.int32), mask.to(torch.int32)
    out = np.concatenate([np.asarray(edge(ids32[i:i + 1], mask32[i:i + 1])) for i in range(len(SENTENCES))])
    diff = np.abs(out - ref).max()
    cos = (out * ref).sum(-1)
    print(f"Max abs diff vs PyTorch: {diff:.2e}")
    print(f"Cosine(LiteRT, PyTorch) per sentence: {np.round(cos, 6).tolist()}")


if __name__ == "__main__":
    main()
