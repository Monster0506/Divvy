"""One L2 logistic regression per person, fit on rows grouped by identical features.

Grouping with counts is the same objective as one row per observation, with far fewer rows.
Unknown cells (the person was not in the household yet) add nothing to that person's head.
"""

import numpy as np
from sklearn.linear_model import LogisticRegression

from divvy_ml.models.base import UNINFORMED

Head = LogisticRegression | float


def group_counts(
    group: np.ndarray, labels: np.ndarray, known: np.ndarray
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """For rows sharing a group id: a representative row index, and per person how many of the
    group's known rows owned the item and how many did not."""
    _, first, inverse = np.unique(group, return_index=True, return_inverse=True)
    seen_cells = known.astype(float)
    owned = np.zeros((len(first), labels.shape[1]))
    seen = np.zeros_like(owned)
    np.add.at(owned, inverse, labels * seen_cells)
    np.add.at(seen, inverse, seen_cells)
    return first, owned, seen - owned


def fit_heads(x: np.ndarray, owned: np.ndarray, missed: np.ndarray, strength: float) -> list[Head]:
    prior = (owned.sum(axis=0) + 1) / (owned.sum(axis=0) + missed.sum(axis=0) + 2)
    heads: list[Head] = []

    for j in range(owned.shape[1]):
        # nobody has (or has not) owned anything yet, so there is no boundary to learn
        if not owned[:, j].any() or not missed[:, j].any():
            heads.append(float(prior[j]))
            continue

        pos, neg = owned[:, j] > 0, missed[:, j] > 0
        rows = np.vstack([x[pos], x[neg]])
        target = np.r_[np.ones(pos.sum()), np.zeros(neg.sum())]
        weight = np.r_[owned[pos, j], missed[neg, j]]
        heads.append(
            LogisticRegression(C=strength, max_iter=1000).fit(rows, target, sample_weight=weight)
        )

    return heads


def predict_heads(heads: list[Head], x: np.ndarray) -> np.ndarray:
    if not len(x):
        return np.zeros((0, len(heads)))

    columns = [
        np.full(len(x), h) if isinstance(h, float) else h.predict_proba(x)[:, 1] for h in heads
    ]
    return np.column_stack(columns) if columns else np.zeros((len(x), 0))


def uninformed_heads(people: int) -> list[Head]:
    return [UNINFORMED] * people
