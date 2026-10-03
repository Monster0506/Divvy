"""Baselines: how often each person owns anything, and what the same item had before."""

from collections.abc import Sequence

import numpy as np

from divvy_ml.models.base import UNINFORMED, ownership_prior
from divvy_ml.text import normalize_item


class FrequencyModel:
    """Ignores the item: how often does each person own anything?"""

    name = "frequency"

    def __init__(self) -> None:
        self._prior = np.zeros(0)

    def fit(self, items: Sequence[str], labels: np.ndarray, known: np.ndarray) -> None:
        self._prior = ownership_prior(labels, known)

    def predict(self, items: Sequence[str]) -> np.ndarray:
        return np.tile(self._prior, (len(items), 1))


class ExactItemModel:
    """Past owners of the same normalised description, shrunk toward the prior."""

    name = "exact-item"

    def __init__(self, shrinkage: float = 1.0) -> None:
        self._shrinkage = shrinkage
        self._prior = np.zeros(0)
        self._owned: dict[str, np.ndarray] = {}
        self._seen: dict[str, np.ndarray] = {}

    def fit(self, items: Sequence[str], labels: np.ndarray, known: np.ndarray) -> None:
        self._prior = ownership_prior(labels, known)
        self._owned, self._seen = {}, {}
        width = labels.shape[1]

        for item, row, seen in zip(items, labels, known, strict=True):
            key = normalize_item(item)
            self._owned.setdefault(key, np.zeros(width))
            self._seen.setdefault(key, np.zeros(width))
            self._owned[key] += row * seen
            self._seen[key] += seen

    def predict(self, items: Sequence[str]) -> np.ndarray:
        if not self._prior.size:
            return np.full((len(items), 0), UNINFORMED)

        zeros = np.zeros_like(self._prior)
        rows = []
        for item in items:
            key = normalize_item(item)
            owned = self._owned.get(key, zeros)
            seen = self._seen.get(key, zeros)
            rows.append((owned + self._shrinkage * self._prior) / (seen + self._shrinkage))

        return np.array(rows).reshape(-1, len(self._prior))


def baseline_models() -> list:
    return [FrequencyModel(), ExactItemModel()]
