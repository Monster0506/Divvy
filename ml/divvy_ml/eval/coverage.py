"""Auto-assign vs human-review tradeoff over confidence thresholds."""

from collections.abc import Sequence
from typing import Any

import numpy as np

TARGET_ACCURACY = 0.95
MIN_AUTO = 10
COVERAGE_RATES = (0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.8, 1.0)


def operating_point(conf: np.ndarray, correct: np.ndarray, threshold: float) -> dict[str, Any]:
    """Items with confidence >= threshold are auto-assigned; the rest go to review."""
    auto = conf >= threshold
    n_auto = int(auto.sum())
    total = len(conf)

    return {
        "threshold": float(threshold),
        "auto_rate": n_auto / total if total else 0.0,
        "auto_accuracy": float(correct[auto].mean()) if n_auto else None,
        "review_rate": 1 - n_auto / total if total else 0.0,
        "n_auto": n_auto,
        "n": total,
    }


def coverage_curve(
    conf: np.ndarray, correct: np.ndarray, rates: Sequence[float] = COVERAGE_RATES
) -> list[dict[str, Any]]:
    """Accuracy when auto-assigning only the most confident fraction of items."""
    order = np.argsort(-conf, kind="stable")
    hits = np.cumsum(correct[order])
    points = []

    for rate in rates:
        k = max(1, round(rate * len(conf))) if len(conf) else 0
        points.append(
            {
                "auto_rate": rate,
                "auto_accuracy": float(hits[k - 1] / k) if k else None,
                "threshold": float(conf[order[k - 1]]) if k else None,
            }
        )

    return points


def pick_threshold(
    conf: np.ndarray,
    correct: np.ndarray,
    target: float = TARGET_ACCURACY,
    min_auto: int = MIN_AUTO,
) -> float | None:
    """Lowest threshold whose auto-assigned items reach the target accuracy.

    Pick it on validation data, then apply it unchanged to test data.
    """
    for threshold in np.unique(conf):
        auto = conf >= threshold
        if auto.sum() < min_auto:
            return None

        if correct[auto].mean() >= target:
            return float(threshold)

    return None
