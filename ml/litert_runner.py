"""Runs an exported embedding model (.tflite) with the LiteRT interpreter on the laptop.

Lets ml/eval.py measure recall on the exact file the phone runs. The tokenizer is loaded from the
model's directory (models/<name>/), which export_litert.py fills.
"""
import os

import numpy as np

import common


class TfliteEmbedder:
    def __init__(self, model_path, threads=4):
        from ai_edge_litert.interpreter import Interpreter
        from transformers import AutoTokenizer
        self.tok = AutoTokenizer.from_pretrained(os.path.dirname(os.path.abspath(model_path)))
        self.interp = Interpreter(model_path=model_path, num_threads=threads)
        self.runner = self.interp.get_signature_runner()
        inputs = self.runner.get_input_details()
        self.names = sorted(inputs)  # args_0 = input ids, args_1 = attention mask
        self.dtype = inputs[self.names[0]]["dtype"]

    def embed_one(self, text):
        enc = self.tok([text], padding="max_length", truncation=True, max_length=common.MAX_LEN, return_tensors="np")
        out = self.runner(**{self.names[0]: enc["input_ids"].astype(self.dtype),
                             self.names[1]: enc["attention_mask"].astype(self.dtype)})
        return np.asarray(next(iter(out.values())))[0]

    def embed(self, texts):
        return np.stack([self.embed_one(t) for t in texts])
