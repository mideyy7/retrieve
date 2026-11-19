#!/usr/bin/env python3
"""Distils the MiniLM teacher into a smaller student (docs/IMPLEMENTATION_PLAN.md, Phase 4).

Student = a subset of the teacher's layers (initialised from them), a vocabulary pruned to the
word pieces that actually occur in lecture text + general English, and position embeddings cut to
MAX_LEN. Why those levers: in the teacher, the word-embedding table is 52% of the parameters and
each layer only ~8% (python ml/baseline.py --model teacher).

Training data never includes the evaluation questions: lecture chunks (whole + 32-word windows)
and WikiText-2 lines. 5% of lecture chunks are held out to measure fidelity = mean cosine between
student and teacher embeddings on unseen lecture text (a tuning signal that doesn't touch the
question set). Loss = 1 - cosine(student, teacher) on L2-normalised embeddings.

    python ml/distill.py --name student-l3 --layers 0,2,5 --epochs 4
    python ml/distill.py --name student-l3 --layers 0,2,5 --init-only   # fidelity before training

Writes ml/checkpoints/<name>/ (model + pruned tokenizer) and docs/results/distill_<name>.csv.
"""
import argparse
import collections
import copy
import csv
import hashlib
import json
import math
import os
import random
import re
import tempfile
import time

import torch
from transformers import AutoModel, AutoTokenizer

import common

BATCH = 64
LR = 1e-4
WARMUP = 0.05
HELDOUT_FRACTION = 0.05


def training_texts():
    chunks = common.load_chunks()
    rnd = random.Random(common.SEED)
    keys = sorted({c["stable_key"] for c in chunks})
    heldout = set(rnd.sample(keys, max(1, int(len(keys) * HELDOUT_FRACTION))))
    train, held = [], []
    for c in chunks:
        words = c["text"].split()
        texts = [" ".join(words)] + [" ".join(words[i:i + 32]) for i in range(0, max(1, len(words) - 8), 24)]
        (held if c["stable_key"] in heldout else train).extend(texts)
    import pyarrow.parquet as pq
    wiki = pq.read_table(common.path("ml/data/general/wikitext-2-raw-v1/train-00000-of-00001.parquet")).column("text").to_pylist()
    general = [l.strip() for l in wiki if len(l.split()) >= 8 and not l.strip().startswith("=")]
    lecture = list(train)
    train += general
    rnd.shuffle(train)
    return train, held, lecture, general


@torch.no_grad()
def teacher_targets(tok, teacher, texts, cache_name):
    """Teacher embeddings for `texts`, cached on disk keyed by the texts' hash."""
    h = hashlib.sha256("\x00".join(texts).encode()).hexdigest()[:16]
    cache = common.path("ml/checkpoints", f"targets_{cache_name}_{h}.pt")
    if os.path.exists(cache):
        return torch.load(cache)
    out = torch.from_numpy(common.embed(tok, teacher, texts, batch_size=64))
    os.makedirs(os.path.dirname(cache), exist_ok=True)
    torch.save(out, cache)
    return out


def token_counts(tok, texts):
    counts = collections.Counter()
    for i in range(0, len(texts), 512):
        for ids in tok(texts[i:i + 512], add_special_tokens=False)["input_ids"]:
            counts.update(ids)
    return counts


def pruned_vocab(teacher_tok, lecture, general, min_general_count):
    """Every teacher token in the (training) lecture text, general-English tokens seen at least
    `min_general_count` times in WikiText-2, special tokens, and every single-character piece (with
    and without ##) so any word can still be spelled out. Sorted, so [PAD] stays id 0."""
    vocab = teacher_tok.get_vocab()
    keep = set(token_counts(teacher_tok, lecture))
    keep |= {i for i, n in token_counts(teacher_tok, general).items() if n >= min_general_count}
    keep |= {teacher_tok.convert_tokens_to_ids(t) for t in teacher_tok.all_special_tokens}
    keep |= {i for t, i in vocab.items() if len(t) == 1 or (t.startswith("##") and len(t) == 3)}
    return sorted(keep)


def build_student(teacher_enc, teacher_tok, layers, keep_ids, out_dir):
    cfg = copy.deepcopy(teacher_enc.config)
    cfg.num_hidden_layers = len(layers)
    cfg.vocab_size = len(keep_ids)
    cfg.max_position_embeddings = common.MAX_LEN
    from transformers import BertModel
    student = BertModel(cfg, add_pooling_layer=False)
    sd = teacher_enc.state_dict()
    new = student.state_dict()
    idx = torch.tensor(keep_ids)
    for k in new:
        if k == "embeddings.word_embeddings.weight":
            new[k] = sd[k][idx].clone()
        elif k == "embeddings.position_embeddings.weight":
            new[k] = sd[k][: common.MAX_LEN].clone()
        elif k == "embeddings.position_ids":
            new[k] = sd[k][:, : common.MAX_LEN].clone()
        elif k.startswith("encoder.layer."):
            i = int(k.split(".")[2])
            new[k] = sd[k.replace(f"encoder.layer.{i}.", f"encoder.layer.{layers[i]}.", 1)].clone()
        else:
            new[k] = sd[k].clone()
    student.load_state_dict(new)

    # Pruned tokenizer: start from the teacher's own tokenizer definition (same normaliser and
    # pre-tokenizer) and swap in the pruned WordPiece vocabulary, remapping the special-token ids
    # its post-processor hard-codes. (BertTokenizerFast(vocab_file=...) silently ignores the file
    # in this transformers version and tokenizes everything to [UNK]: fidelity 0.01.)
    inv = {i: t for t, i in teacher_tok.get_vocab().items()}
    new_id = {inv[old]: new for new, old in enumerate(keep_ids)}
    spec = json.loads(teacher_tok.backend_tokenizer.to_str())
    spec["model"]["vocab"] = new_id
    post = spec.get("post_processor") or {}
    for name, entry in (post.get("special_tokens") or {}).items():
        entry["ids"] = [new_id[t] for t in entry["tokens"]]
    if spec.get("padding"):
        spec["padding"]["pad_id"] = new_id["[PAD]"]
    from tokenizers import Tokenizer
    from transformers import PreTrainedTokenizerFast
    tok = PreTrainedTokenizerFast(
        tokenizer_object=Tokenizer.from_str(json.dumps(spec)), model_max_length=common.MAX_LEN,
        unk_token="[UNK]", pad_token="[PAD]", cls_token="[CLS]", sep_token="[SEP]", mask_token="[MASK]")
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "vocab.txt"), "w") as f:  # plain vocab for the app's WordPiece tokenizer
        f.write("\n".join(inv[i] for i in keep_ids) + "\n")
    return student, tok


@torch.no_grad()
def fidelity(tok, student, targets, texts):
    emb = torch.from_numpy(common.embed(tok, common.Embedder(student).eval(), texts, batch_size=64))
    return float((emb * targets).sum(-1).mean())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", required=True)
    ap.add_argument("--layers", default="0,2,5", help="teacher layers to keep, in order")
    ap.add_argument("--epochs", type=int, default=4)
    ap.add_argument("--no-prune", action="store_true", help="keep the full teacher vocabulary")
    ap.add_argument("--min-general-count", type=int, default=20,
                    help="keep a general-English token only if WikiText-2 uses it this often (lecture tokens always kept)")
    ap.add_argument("--init-only", action="store_true", help="report fidelity of the initialised student and stop")
    args = ap.parse_args()
    common.seed_everything()
    torch.set_num_threads(4)

    layers = [int(x) for x in args.layers.split(",")]
    teacher_tok = AutoTokenizer.from_pretrained(common.TEACHER_ID)
    teacher_enc = AutoModel.from_pretrained(common.TEACHER_ID, add_pooling_layer=False).eval()
    teacher = common.Embedder(teacher_enc).eval()

    train, held, lecture, general = training_texts()
    print(f"training texts: {len(train):,} (lecture windows + WikiText-2), held-out lecture texts: {len(held):,}")
    t_train = teacher_targets(teacher_tok, teacher, train, "train")
    t_held = teacher_targets(teacher_tok, teacher, held, "held")

    keep = list(range(teacher_tok.vocab_size)) if args.no_prune else pruned_vocab(teacher_tok, lecture, general, args.min_general_count)
    out_dir = common.path("ml/checkpoints", args.name)
    student, tok = build_student(teacher_enc, teacher_tok, layers, keep, out_dir)
    total, _ = common.param_breakdown(student)
    teacher_total, _ = common.param_breakdown(teacher_enc)
    print(f"student {args.name}: layers {layers}, vocab {len(keep):,}/{teacher_tok.vocab_size:,}, "
          f"{total:,} params ({total / teacher_total:.1%} of teacher's {teacher_total:,})")
    f0 = fidelity(tok, student, t_held, held)
    print(f"held-out fidelity before training: {f0:.4f}")
    if args.init_only:
        return

    model = common.Embedder(student).train()
    opt = torch.optim.AdamW(model.parameters(), lr=LR, weight_decay=0.01)
    steps = args.epochs * math.ceil(len(train) / BATCH)
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: min(1.0, s / max(1, WARMUP * steps)) * max(0.0, (steps - s) / max(1, steps - WARMUP * steps)))

    log_path = common.path("docs/results", f"distill_{args.name}.csv")
    os.makedirs(os.path.dirname(log_path), exist_ok=True)
    log_file = open(log_path, "w", newline="", buffering=1)  # line-buffered: progress is visible while training
    log = csv.writer(log_file)
    log.writerow(["step", "epoch", "train_loss", "heldout_fidelity", "lr", "elapsed_s"])
    log.writerow([0, 0, "", f"{f0:.5f}", 0, 0])
    start, step = time.time(), 0
    for epoch in range(1, args.epochs + 1):
        order = list(range(len(train)))
        random.Random(common.SEED + epoch).shuffle(order)
        running = []
        for b in range(0, len(order), BATCH):
            ids = order[b:b + BATCH]
            enc = tok([train[i] for i in ids], padding=True, truncation=True, max_length=common.MAX_LEN, return_tensors="pt")
            emb = model(enc["input_ids"], enc["attention_mask"])
            loss = (1 - (emb * t_train[ids]).sum(-1)).mean()
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            sched.step()
            step += 1
            running.append(loss.item())
            if step % 50 == 0:
                log.writerow([step, epoch, f"{sum(running) / len(running):.5f}", "", f"{sched.get_last_lr()[0]:.2e}", f"{time.time() - start:.0f}"])
                print(f"  epoch {epoch} step {step}/{steps} loss {sum(running) / len(running):.4f} ({time.time() - start:.0f}s)", flush=True)
                running = []
        model.eval()
        f = fidelity(tok, student, t_held, held)
        model.train()
        log.writerow([step, epoch, "", f"{f:.5f}", f"{sched.get_last_lr()[0]:.2e}", f"{time.time() - start:.0f}"])
        print(f"epoch {epoch}: held-out fidelity {f:.4f}", flush=True)

    student.save_pretrained(out_dir)
    tok.save_pretrained(out_dir)
    json.dump({"layers": layers, "vocab": len(keep), "min_general_count": args.min_general_count, "params": total, "teacher_params": teacher_total,
               "epochs": args.epochs, "batch": BATCH, "lr": LR, "train_texts": len(train), "heldout_texts": len(held),
               "seed": common.SEED, "final_heldout_fidelity": f}, open(os.path.join(out_dir, "distill_info.json"), "w"), indent=2)
    print(f"saved {out_dir}")


if __name__ == "__main__":
    main()
