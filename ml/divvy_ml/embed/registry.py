"""Build an embedder from a short name: 'hashing', 'minilm' or 'st:<model id>'."""

import os

from divvy_ml.embed.base import CachedEmbedder, Embedder
from divvy_ml.embed.hashing import HASHING, HashingEmbedder
from divvy_ml.embed.sentence import MINILM, SentenceEmbedder

SENTENCE_PREFIX = "st:"
ENV_VAR = "DIVVY_EMBEDDER"
DEFAULT = HASHING


def make_embedder(spec: str = DEFAULT) -> Embedder:
    if spec == HASHING:
        return CachedEmbedder(HashingEmbedder())

    if spec == MINILM:
        return CachedEmbedder(SentenceEmbedder())

    if spec.startswith(SENTENCE_PREFIX) and len(spec) > len(SENTENCE_PREFIX):
        return CachedEmbedder(SentenceEmbedder(spec.removeprefix(SENTENCE_PREFIX)))

    raise ValueError(f"unknown embedder {spec!r}; use 'hashing', 'minilm' or 'st:<model id>'")


def configured_embedder() -> Embedder:
    """The embedder named by DIVVY_EMBEDDER, or the default."""
    return make_embedder(os.environ.get(ENV_VAR, DEFAULT))
