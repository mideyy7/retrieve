"""Shared pieces for the ML scripts: data loading, the embedding wrapper, metrics, results files.

Every number in docs/results/ comes through here, so methods are compared on identical data with
identical metrics.
"""
import csv
import datetime
import json
import os
import platform
import random
import subprocess

import numpy as np
import torch

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEACHER_ID = "sentence-transformers/all-MiniLM-L6-v2"
MAX_LEN = 128   # tokens per input; also what the on-device model is exported with
SEED = 1234


def seed_everything(seed=SEED):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.use_deterministic_algorithms(True, warn_only=True)


def path(*parts):
    return os.path.join(ROOT, *parts)


def load_chunks():
    return json.load(open(path("ml/data/chunks.json")))["chunks"]


def load_questions(split):
    qs = json.load(open(path("ml/data/questions.json")))["questions"]
    return [q for q in qs if split == "all" or q["split"] == split]


# ---- Embedding model -------------------------------------------------------------------------

class Embedder(torch.nn.Module):
    """BERT-style encoder + attention-masked mean pooling + L2 norm (sentence-transformers style).
    The same wrapper is exported to LiteRT, so laptop and phone compute the same function."""

    def __init__(self, encoder):
        super().__init__()
        self.encoder = encoder

    def forward(self, input_ids, attention_mask):
        hidden = self.encoder(input_ids=input_ids, attention_mask=attention_mask).last_hidden_state
        mask = attention_mask.unsqueeze(-1).to(hidden.dtype)
        pooled = (hidden * mask).sum(1) / mask.sum(1).clamp(min=1e-9)
        return torch.nn.functional.normalize(pooled, dim=-1)


def load_embedder(model_dir_or_id):
    from transformers import AutoModel, AutoTokenizer
    tok = AutoTokenizer.from_pretrained(model_dir_or_id)
    enc = AutoModel.from_pretrained(model_dir_or_id, add_pooling_layer=False).eval()
    return tok, Embedder(enc).eval()


@torch.no_grad()
def embed(tok, model, texts, batch_size=32):
    out = []
    for i in range(0, len(texts), batch_size):
        enc = tok(texts[i:i + batch_size], padding="max_length", truncation=True, max_length=MAX_LEN, return_tensors="pt")
        out.append(model(enc["input_ids"], enc["attention_mask"]))
    return torch.cat(out).numpy()


def param_breakdown(module):
    groups = {}
    for name, p in module.named_parameters():
        parts = name.split(".")
        key = ".".join(parts[:4]) if "layer" in parts else ".".join(parts[:3])
        groups[key] = groups.get(key, 0) + p.numel()
    return sum(groups.values()), groups


# ---- Metrics ---------------------------------------------------------------------------------

def location(chunk):
    """Where a chunk is: (doc, page) for slides/PDFs, (doc, "t", start_ms) for recordings. Fusion and
    hit checks work on locations, so two chunks of one slide count as one place, while each ~30 s of
    a recording is its own place (the app's Rrf.locationKey sorts the same way)."""
    if chunk["page"] is not None:
        return (chunk["doc_sha256"], chunk["page"])
    return (chunk["doc_sha256"], "t", chunk["start_ms"])


def chunk_spans(chunks):
    """(doc, "t", start) -> (start, end) for recording chunks: a chunk runs until the next chunk of the
    same recording starts; the last one is taken to last 30 s."""
    starts = {}
    for c in chunks:
        if c["page"] is None:
            starts.setdefault(c["doc_sha256"], []).append(c["start_ms"])
    spans = {}
    for doc, ss in starts.items():
        ss = sorted(ss)
        for i, s in enumerate(ss):
            spans[(doc, "t", s)] = (s, ss[i + 1] if i + 1 < len(ss) else s + 30_000)
    return spans


def is_hit(loc, answer, spans):
    """Slide answers match the exact (doc, page); recording answers {doc, start_ms, end_ms} match any
    chunk of that recording whose span overlaps the answer's time range."""
    if loc[0] != answer["doc_sha256"]:
        return False
    if "page" in answer:
        return len(loc) == 2 and loc[1] == answer["page"]
    if len(loc) != 3:
        return False
    start, end = spans.get(loc, (loc[2], loc[2] + 30_000))
    return start < answer["end_ms"] and answer["start_ms"] < end


def hit_ranks(ranked_locations, answers, spans=None):
    """1-based rank of the first location that answers the question, or None."""
    spans = spans or {}
    for rank, loc in enumerate(ranked_locations, 1):
        if any(is_hit(loc, a, spans) for a in answers):
            return rank
    return None


def metrics(first_hit_ranks):
    n = len(first_hit_ranks)
    def recall(k):
        return sum(1 for r in first_hit_ranks if r is not None and r <= k) / n
    mrr = sum(1.0 / r for r in first_hit_ranks if r is not None and r <= 10) / n
    return {"n": n, "recall@1": recall(1), "recall@5": recall(5), "recall@10": recall(10), "mrr@10": mrr}


# ---- Results files ---------------------------------------------------------------------------

def git_commit():
    commit = subprocess.run(["git", "-C", ROOT, "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
    dirty = subprocess.run(["git", "-C", ROOT, "status", "--porcelain", "--untracked-files=no"], capture_output=True, text=True).stdout.strip()
    return commit + ("-dirty" if dirty else "")


def machine():
    try:
        cpu = subprocess.run(["sysctl", "-n", "machdep.cpu.brand_string"], capture_output=True, text=True).stdout.strip()
        mem = int(subprocess.run(["sysctl", "-n", "hw.memsize"], capture_output=True, text=True).stdout) // 2**30
        return f"{cpu} {mem}GB macOS {platform.mac_ver()[0]}"
    except (OSError, ValueError):
        return platform.platform()


def append_result(filename, row):
    """Appends a row to docs/results/<filename>, adding the standard metadata columns."""
    full = {"date": datetime.date.today().isoformat(), "git_commit": git_commit(), "machine": machine(), **row}
    out = path("docs/results", filename)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    exists = os.path.exists(out)
    if exists:
        header = next(csv.reader(open(out)))
        if header != list(full):
            raise SystemExit(f"{out} has columns {header}, refusing to append {list(full)}")
    with open(out, "a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(full))
        if not exists:
            w.writeheader()
        w.writerow(full)
