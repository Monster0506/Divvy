"""Household memory: similarity-weighted ownership of the most similar past purchases."""

from collections.abc import Sequence

import numpy as np

from divvy_ml.embed import Embedder
from divvy_ml.models.base import ownership_prior

RECENCY_TIEBREAK = 1e-9


class Neighbors:
    """Votes from the k most similar past observations.

    Weights are similarity**sharpness, so weak matches barely count and unfamiliar items fall back
    to the prior. Equally similar repeat purchases favour the most recent. A person's vote only
    counts observations where they were in the household.
    """

    def __init__(
        self, embedder: Embedder, k: int = 10, sharpness: float = 4.0, shrinkage: float = 1.0
    ) -> None:
        self._embedder = embedder
        self._k = k
        self._sharpness = sharpness
        self._shrinkage = shrinkage
        self._prior = np.zeros(0)
        self._vectors = np.zeros((0, 0))
        self._inverse = np.zeros(0, dtype=int)
        self._labels = np.zeros((0, 0))
        self._known = np.zeros((0, 0))

    def fit(self, items: Sequence[str], labels: np.ndarray, known: np.ndarray) -> None:
        self._labels = labels.astype(float)
        self._known = known.astype(float)
        self._prior = ownership_prior(labels, known.astype(bool))
        if not len(items):
            return

        texts, self._inverse = np.unique(np.array(items), return_inverse=True)
        self._vectors = self._embedder.embed(texts.tolist())

    def query(self, items: Sequence[str]) -> tuple[np.ndarray, np.ndarray]:
        """(ownership votes, best similarity to any past item) for new items."""
        if not len(self._labels):
            return np.tile(self._prior, (len(items), 1)), np.zeros(len(items))

        sims = (self._embedder.embed(list(items)) @ self._vectors.T)[:, self._inverse]
        k = min(self._k, sims.shape[1])
        nearest = np.argpartition(-(sims + self._recency()), k - 1, axis=1)[:, :k]
        votes = self._vote(np.take_along_axis(sims, nearest, axis=1), nearest)
        return votes, sims.max(axis=1)

    def held_out(self) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        """(votes, best similarity, feature group) for every past observation, each computed
        without that observation, so a row never votes for itself. Rows in the same group have
        identical features."""
        count = len(self._labels)
        sims = (self._vectors @ self._vectors.T)[:, self._inverse]
        scores = sims + self._recency()

        # the k nearest others are always within the k + 1 nearest overall
        width = min(self._k + 1, count)
        top = np.argpartition(-scores, width - 1, axis=1)[:, :width]
        order = np.argsort(-np.take_along_axis(scores, top, axis=1), axis=1)
        top = np.take_along_axis(top, order, axis=1)

        rows = top[self._inverse]
        is_self = rows == np.arange(count)[:, None]
        keep = ~is_self & (np.cumsum(~is_self, axis=1) <= self._k)
        near = sims[self._inverse[:, None], rows]

        votes = self._vote(np.where(keep, near, 0.0), rows)
        best = np.where(keep, near, -np.inf).max(axis=1, initial=-np.inf)
        best[~keep.any(axis=1)] = 0.0
        position = np.where(is_self.any(axis=1), is_self.argmax(axis=1), width)
        return votes, best, self._inverse * (width + 1) + position

    def _vote(self, sims: np.ndarray, nearest: np.ndarray) -> np.ndarray:
        weights = np.clip(sims, 0, 1) ** self._sharpness
        owned = np.einsum("nk,nkp->np", weights, self._labels[nearest] * self._known[nearest])
        seen = np.einsum("nk,nkp->np", weights, self._known[nearest])
        return (owned + self._shrinkage * self._prior) / (seen + self._shrinkage)

    def _recency(self) -> np.ndarray:
        return np.arange(len(self._labels)) * RECENCY_TIEBREAK


class KNNModel:
    """Neighbour ownership votes used directly as probabilities."""

    name = "knn"

    def __init__(
        self, embedder: Embedder, k: int = 10, sharpness: float = 4.0, shrinkage: float = 1.0
    ) -> None:
        self._neighbors = Neighbors(embedder, k, sharpness, shrinkage)

    def fit(self, items: Sequence[str], labels: np.ndarray, known: np.ndarray) -> None:
        self._neighbors.fit(items, labels, known)

    def predict(self, items: Sequence[str]) -> np.ndarray:
        return self._neighbors.query(items)[0]
