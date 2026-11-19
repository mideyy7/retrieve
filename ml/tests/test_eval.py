"""Tests for the evaluation harness (docs/IMPLEMENTATION_PLAN.md, Phase 4).

    .venv/bin/python -m pytest ml/tests -q

A bug in the metrics would silently corrupt every recall number on the CV, so the arithmetic is
pinned with hand-checked cases, the whole pipeline is checked for run-to-run reproducibility, and
the semantic path is sanity-checked on a set where the right answer is unambiguous.
"""
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import common  # noqa: E402
import eval as harness  # noqa: E402

A = {"doc_sha256": "a", "page": 1}
B = {"doc_sha256": "b", "page": 7}


def test_hit_rank_is_first_chunk_on_any_answer_page():
    ranked = [("x", 1), ("b", 7), ("a", 1)]
    assert common.hit_ranks(ranked, [A, B]) == 2
    assert common.hit_ranks(ranked, [A]) == 3
    assert common.hit_ranks(ranked, [{"doc_sha256": "z", "page": 1}]) is None
    assert common.hit_ranks([("a", 2)], [A]) is None  # same doc, other page: not a hit


def test_metrics_hand_checked():
    m = common.metrics([1, 3, None, 6, 11])
    assert m["n"] == 5
    assert m["recall@1"] == pytest.approx(1 / 5)
    assert m["recall@5"] == pytest.approx(2 / 5)
    assert m["recall@10"] == pytest.approx(3 / 5)
    assert m["mrr@10"] == pytest.approx((1 + 1 / 3 + 1 / 6) / 5)  # rank 11 is outside @10


def test_rrf_fuses_and_breaks_ties_deterministically():
    lexical = [("a", 1), ("b", 1), ("c", 1)]
    semantic = [("b", 1), ("d", 1), ("a", 1)]
    fused = harness.rrf([lexical, semantic])
    # b: 1/62 + 1/61, a: 1/61 + 1/63 -> b first; c and d tie at 1/63 and 1/62 -> d before c.
    assert fused[:4] == [("b", 1), ("a", 1), ("d", 1), ("c", 1)]
    assert harness.rrf([lexical, semantic]) == fused


def test_every_answer_location_exists_in_the_corpus():
    locations = {(c["doc_sha256"], c["page"]) for c in common.load_chunks()}
    for q in common.load_questions("all"):
        assert q["answers"], q["id"]
        for a in q["answers"]:
            assert (a["doc_sha256"], a["page"]) in locations, (q["id"], a)


def test_dev_and_test_splits_are_disjoint():
    dev = {q["id"] for q in common.load_questions("dev")}
    test = {q["id"] for q in common.load_questions("test")}
    assert dev and test and not dev & test


@pytest.mark.skipif(not os.path.exists(common.path("native/build-release/retrieve_cli")), reason="build retrieve_cli first")
def test_bm25_evaluation_is_reproducible():
    chunks, qs = common.load_chunks(), common.load_questions("dev")
    cli = common.path("native/build-release/retrieve_cli")
    first = harness.bm25_rankings(qs, chunks, cli)
    second = harness.bm25_rankings(qs, chunks, cli)
    assert first == second


def test_semantic_evaluation_is_reproducible_and_sane():
    """Teacher on a synthetic set where each 'question' is a chunk's own text: the chunk must get
    the highest score (ties allowed), and two runs must give identical scores.

    Ties are real: COMP21111 wk3 p77 and p79 share their first 128 tokens (a Beamer overlay that
    replaced later text), so at MAX_LEN=128 they embed identically and either can rank first."""
    chunks = common.load_chunks()
    sample = [c for c in chunks if len(c["text"].split()) > 40][:: 20][:25]
    questions = [{"question": c["text"], "answers": [{"doc_sha256": c["doc_sha256"], "page": c["page"]}]} for c in sample]
    s1 = harness.semantic_scores(questions, chunks, "teacher")
    s2 = harness.semantic_scores(questions, chunks, "teacher")
    assert (s1 == s2).all()
    index = {c["stable_key"]: i for i, c in enumerate(chunks)}
    for row, c in zip(s1, sample):
        assert row[index[c["stable_key"]]] >= row.max() - 1e-6, c["stable_key"]


def test_rrf_matches_shared_fixture():
    """ml/data/rrf_fixture.json is also checked by the app's RrfTest, so phone and eval fuse identically."""
    fx = json.load(open(common.path("ml/data/rrf_fixture.json")))
    assert fx["k"] == 60 and fx["limit"] == harness.K
    for case in fx["cases"]:
        got = [loc[0] for loc in harness.rrf([[(x,) for x in l] for l in case["lists"]])]
        assert got == case["expected"], case["name"]
