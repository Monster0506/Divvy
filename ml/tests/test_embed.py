import sys
import time
import types

import numpy as np
import pytest
from fastapi.testclient import TestClient

from divvy_ml.app import app
from divvy_ml.embed import (
    CachedEmbedder,
    HashingEmbedder,
    SentenceEmbedder,
    configured_embedder,
    make_embedder,
)
from divvy_ml.embed.router import MAX_TEXTS


def cosine(a, b):
    return float(a @ b)


def test_hashing_rows_are_unit_length_float32():
    vectors = HashingEmbedder().embed(["Coke Zero 12-pack", "Paper towels"])

    assert vectors.shape == (2, 4096)
    assert vectors.dtype == np.float32
    assert np.allclose(np.linalg.norm(vectors, axis=1), 1.0, atol=1e-5)


def test_hashing_is_deterministic():
    first = HashingEmbedder().embed(["Greek yogurt"])
    second = HashingEmbedder().embed(["Greek yogurt"])

    assert np.array_equal(first, second)


def test_similar_wordings_are_closer_than_unrelated_items():
    coke12, coke24, towels = HashingEmbedder().embed(
        ["Coke Zero 12-pack", "Coke Zero 24-pack", "Paper towels"]
    )

    assert cosine(coke12, coke24) > 0.7
    assert cosine(coke12, coke24) > cosine(coke12, towels) + 0.4


def test_empty_input_gives_an_empty_matrix():
    assert HashingEmbedder().embed([]).shape == (0, 4096)
    assert CachedEmbedder(HashingEmbedder()).embed([]).shape[0] == 0


class Counting:
    name = "counting"

    def __init__(self):
        self.seen = []

    def embed(self, texts):
        self.seen.append(list(texts))
        return np.eye(len(texts), 3, dtype=np.float32)


def test_cache_embeds_each_distinct_text_once():
    inner = Counting()
    cached = CachedEmbedder(inner)

    first = cached.embed(["a", "b", "a"])
    again = cached.embed(["b", "a"])

    assert inner.seen == [["a", "b"]]
    assert np.array_equal(first[0], first[2])
    assert np.array_equal(again[0], first[1])
    assert cached.name == "counting"


def test_make_embedder_names():
    assert make_embedder("hashing").name == "hashing"
    with pytest.raises(ValueError, match="unknown embedder"):
        make_embedder("word2vec")
    with pytest.raises(ValueError, match="unknown embedder"):
        make_embedder("st:")


def test_configured_embedder_reads_the_environment(monkeypatch):
    monkeypatch.setenv("DIVVY_EMBEDDER", "nonsense")
    with pytest.raises(ValueError):
        configured_embedder()

    monkeypatch.setenv("DIVVY_EMBEDDER", "hashing")
    assert configured_embedder().name == "hashing"


def test_sentence_embedder_wraps_the_model(monkeypatch):
    class FakeModel:
        def __init__(self, name):
            self.name = name

        def encode(self, texts, normalize_embeddings, convert_to_numpy):
            assert normalize_embeddings and convert_to_numpy
            return np.ones((len(texts), 4))

        def get_sentence_embedding_dimension(self):
            return 4

    module = types.SimpleNamespace(SentenceTransformer=FakeModel)
    monkeypatch.setitem(sys.modules, "sentence_transformers", module)

    embedder = SentenceEmbedder("some/model")

    assert embedder.name == "some/model"
    assert embedder.embed(["a", "b"]).shape == (2, 4)
    assert embedder.embed([]).shape == (0, 4)


def test_sentence_embedder_explains_the_missing_extra(monkeypatch):
    monkeypatch.setitem(sys.modules, "sentence_transformers", None)

    with pytest.raises(RuntimeError, match="semantic"):
        SentenceEmbedder()


def test_embed_endpoint_returns_one_vector_per_text():
    client = TestClient(app)

    response = client.post("/embed", json={"texts": ["Coke Zero", "Paper towels", "Eggs"]})

    assert response.status_code == 200
    body = response.json()
    assert body["embedder"] == "hashing"
    assert body["dimensions"] == 4096
    assert len(body["embeddings"]) == 3
    assert all(len(v) == 4096 for v in body["embeddings"])


def test_embedder_is_loaded_once_at_startup():
    with TestClient(app) as client:
        loaded = app.state.embedder
        client.post("/embed", json={"texts": ["a"]})
        client.post("/embed", json={"texts": ["b"]})

        assert app.state.embedder is loaded


@pytest.mark.parametrize(
    "body",
    [
        {"texts": []},
        {"texts": [""]},
        {"texts": ["x" * 513]},
        {"texts": ["x"] * (MAX_TEXTS + 1)},
        {"texts": ["a"], "extra": 1},
        {},
    ],
)
def test_embed_endpoint_rejects_bad_requests(body):
    assert TestClient(app).post("/embed", json=body).status_code == 422


def test_thirty_items_embed_well_inside_the_two_second_budget():
    client = TestClient(app)
    texts = [f"Item description number {i} family size" for i in range(30)]

    started = time.perf_counter()
    response = client.post("/embed", json={"texts": texts})
    elapsed = time.perf_counter() - started

    assert response.status_code == 200
    assert elapsed < 2.0
