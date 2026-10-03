"""Logistic regression on the embedding, optionally stacked with neighbour ownership features."""

from collections.abc import Sequence

import numpy as np

from divvy_ml.embed import Embedder
from divvy_ml.models.heads import (
    Head,
    fit_heads,
    group_counts,
    predict_heads,
    uninformed_heads,
)
from divvy_ml.models.knn import Neighbors

LOGIT_CLIP = 1e-4
COMBINED = "combined"


class LogRegModel:
    """One L2 logistic regression per person on item embeddings alone."""

    name = "logreg"

    def __init__(self, embedder: Embedder, strength: float = 4.0) -> None:
        self._embedder = embedder
        self._strength = strength
        self._heads: list[Head] = []

    def fit(self, items: Sequence[str], labels: np.ndarray, known: np.ndarray) -> None:
        self._heads = uninformed_heads(labels.shape[1])
        if not len(items):
            return

        texts, inverse = np.unique(np.array(items), return_inverse=True)
        first, owned, missed = group_counts(inverse, labels, known)
        self._heads = fit_heads(self._embedder.embed(texts.tolist()), owned, missed, self._strength)

    def predict(self, items: Sequence[str]) -> np.ndarray:
        return predict_heads(self._heads, self._embedder.embed(list(items)))


class StackedModel:
    """Embedding plus kNN ownership features (logit votes and best similarity).

    Training rows get held-out neighbour features, so they never see their own label. Each row's
    features describe the item and its neighbours, never who is predicted.
    """

    name = COMBINED

    def __init__(self, embedder: Embedder, strength: float = 4.0) -> None:
        self._embedder = embedder
        self._strength = strength
        self._neighbors = Neighbors(embedder)
        self._heads: list[Head] = []

    def fit(self, items: Sequence[str], labels: np.ndarray, known: np.ndarray) -> None:
        self._neighbors.fit(items, labels, known)
        self._heads = uninformed_heads(labels.shape[1])
        if not len(items):
            return

        votes, best, group = self._neighbors.held_out()
        first, owned, missed = group_counts(group, labels, known)
        x = self.features([items[i] for i in first], votes[first], best[first])
        self._heads = fit_heads(x, owned, missed, self._strength)

    def predict(self, items: Sequence[str]) -> np.ndarray:
        votes, best = self._neighbors.query(items)
        return predict_heads(self._heads, self.features(list(items), votes, best))

    def features(self, items: Sequence[str], votes: np.ndarray, best: np.ndarray) -> np.ndarray:
        clipped = np.clip(votes, LOGIT_CLIP, 1 - LOGIT_CLIP)
        logit = np.log(clipped / (1 - clipped))
        return np.hstack([self._embedder.embed(list(items)), logit, best[:, None]])
