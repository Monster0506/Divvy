"""When to say "unknown" instead of guessing.

Two cases: the household has too little history, or nothing in its history resembles the item.
The similarity cut is tuned for the hashing embedder (items below 0.3 were never right, 0.3 to 0.5
about one in five); recalibrate it for another embedder.
"""

from dataclasses import dataclass

import numpy as np

MIN_HISTORY = 30
MIN_SIMILARITY = 0.4


@dataclass(frozen=True)
class AbstentionPolicy:
    min_history: int = MIN_HISTORY
    min_similarity: float | None = MIN_SIMILARITY

    def unknown(self, history: int | np.ndarray, familiarity: np.ndarray | None) -> np.ndarray:
        """True for each item the model should not answer.

        `history` is the number of labelled items the household has (one value, or one per item).
        `familiarity` is each item's similarity to its closest past purchase, if the model has it.
        """
        history = np.asarray(history)
        size = len(familiarity) if familiarity is not None else history.size
        unknown = np.broadcast_to(history < self.min_history, (size,)).copy()
        if familiarity is not None and self.min_similarity is not None:
            unknown |= familiarity < self.min_similarity

        return unknown


def unknown_stats(correct: np.ndarray, unknown: np.ndarray) -> dict[str, float | None]:
    """How many items would be "unknown", and how accurate the model is on each side."""
    answered = ~unknown
    return {
        "unknown_rate": float(unknown.mean()) if len(unknown) else 0.0,
        "unknown_accuracy": float(correct[unknown].mean()) if unknown.any() else None,
        "answered_accuracy": float(correct[answered].mean()) if answered.any() else None,
    }
