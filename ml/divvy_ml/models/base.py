"""Shared model pieces. A model learns from a household's history and predicts owner probabilities.

Histories are in chronological order. `known[i, j]` is False when person j was not yet in the
household at item i; that cell is unknown and must never count as "does not own".
"""

from collections.abc import Sequence
from typing import Protocol

import numpy as np

UNINFORMED = 0.5


class Model(Protocol):
    name: str

    def fit(self, items: Sequence[str], labels: np.ndarray, known: np.ndarray) -> None: ...

    def predict(self, items: Sequence[str]) -> np.ndarray:
        """Probability each person owns each item: shape (len(items), people)."""
        ...


def ownership_prior(labels: np.ndarray, known: np.ndarray) -> np.ndarray:
    """Per-person ownership rate with a Beta(1, 1) prior, counting only known cells.

    A person with no history gets 0.5, so a new member is neither favoured nor ruled out.
    """
    owned = (labels * known).sum(axis=0)
    seen = known.sum(axis=0)
    return (owned + 1) / (seen + 2)
