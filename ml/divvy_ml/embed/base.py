"""Embedder interface. Rows are L2-normalised float32, so a dot product is the cosine."""

from collections.abc import Sequence
from typing import Protocol

import numpy as np


class Embedder(Protocol):
    name: str

    def embed(self, texts: Sequence[str]) -> np.ndarray:
        """One row per text, shape (len(texts), dimensions)."""
        ...


class CachedEmbedder:
    """Memoises per text. Item descriptions repeat constantly, so repeat calls are free."""

    def __init__(self, inner: Embedder) -> None:
        self.name = inner.name
        self._inner = inner
        self._cache: dict[str, np.ndarray] = {}
        self._width = 0

    def embed(self, texts: Sequence[str]) -> np.ndarray:
        missing = list(dict.fromkeys(t for t in texts if t not in self._cache))
        if missing:
            vectors = self._inner.embed(missing)
            self._width = vectors.shape[1]
            self._cache.update(zip(missing, vectors, strict=True))

        if not texts:
            return np.zeros((0, self._width), dtype=np.float32)

        return np.stack([self._cache[t] for t in texts])
