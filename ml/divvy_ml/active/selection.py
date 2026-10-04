"""Pick a small batch of items worth asking the household to label.

Items are scored by model uncertainty, grouped by embedding similarity, and taken round-robin from
the groups so one kind of item cannot fill the batch. Near-duplicates are never both chosen.
"""

from collections.abc import Sequence

import numpy as np

DUPLICATE_SIMILARITY = 0.9
_KMEANS_ITERATIONS = 20


def uncertainty(confidences: Sequence[float | None]) -> np.ndarray:
    """1 - confidence; items with no prediction (None) are maximally uncertain."""
    return np.array([1.0 if c is None else 1.0 - min(max(c, 0.0), 1.0) for c in confidences])


def _unit(embeddings: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
    return embeddings / np.where(norms == 0, 1.0, norms)


def _cluster(unit: np.ndarray, count: int) -> np.ndarray:
    """Deterministic k-means: farthest-point seeding, then a few Lloyd iterations."""
    centres = [0]
    nearest = 1 - unit @ unit[0]
    while len(centres) < count:
        centres.append(int(np.argmax(nearest)))
        nearest = np.minimum(nearest, 1 - unit @ unit[centres[-1]])
    centroids = unit[centres]
    labels = np.argmax(unit @ centroids.T, axis=1)
    for _ in range(_KMEANS_ITERATIONS):
        for c in range(count):
            members = unit[labels == c]
            if len(members):
                centroids[c] = members.mean(axis=0)
        updated = np.argmax(unit @ _unit(centroids).T, axis=1)
        if np.array_equal(updated, labels):
            break
        labels = updated
    return labels


def select_batch(
    embeddings: np.ndarray,
    scores: Sequence[float],
    size: int = 10,
    duplicate_similarity: float = DUPLICATE_SIMILARITY,
) -> list[int]:
    """Indices of up to `size` items, most informative first, with no near-duplicate pairs."""
    n = len(embeddings)
    if n != len(scores):
        raise ValueError("embeddings and scores must have the same length")
    if n == 0 or size <= 0:
        return []
    unit = _unit(np.asarray(embeddings, dtype=float))
    scores = np.asarray(scores, dtype=float)
    labels = _cluster(unit, min(size, n))

    queues = []
    for c in range(labels.max() + 1):
        members = np.flatnonzero(labels == c)
        if len(members):
            queues.append(sorted(members.tolist(), key=lambda i: (-scores[i], i)))
    queues.sort(key=lambda q: (-scores[q[0]], q[0]))

    chosen: list[int] = []
    while len(chosen) < size and any(queues):
        for queue in queues:
            while queue:
                i = queue.pop(0)
                if not chosen or np.max(unit[chosen] @ unit[i]) < duplicate_similarity:
                    chosen.append(i)
                    break
            if len(chosen) == size:
                break
    return chosen
