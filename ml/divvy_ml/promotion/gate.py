"""Promotion gate: a candidate model replaces the current one only if it does not regress.

Both models are scored on the same held-out data. Accuracy is measured on the items that
would be auto-assigned at the live confidence threshold, calibration as item-level ECE.
"""

import logging
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

import numpy as np

from divvy_ml.eval import Confidence, confidence, decide, exact_correct, expected_calibration_error

log = logging.getLogger("divvy_ml.promotion")

AUTO_THRESHOLD = 0.9


@dataclass(frozen=True)
class Tolerance:
    accuracy: float = 0.01  # max drop in accuracy at threshold
    ece: float = 0.01  # max rise in expected calibration error


@dataclass(frozen=True)
class Decision:
    promote: bool
    reasons: tuple[str, ...]
    current: dict[str, Any] | None
    candidate: dict[str, Any]


def _score(truth: np.ndarray, probs: np.ndarray, threshold: float) -> dict[str, Any]:
    pred = decide(probs)
    conf = confidence(probs, pred, Confidence.JOINT)
    correct = exact_correct(truth, pred)
    auto = conf >= threshold
    return {
        "accuracy_at_threshold": float(correct[auto].mean()) if auto.any() else None,
        "auto_rate": float(auto.mean()) if len(auto) else 0.0,
        "ece": expected_calibration_error(conf, correct),
        "n": len(truth),
    }


def compare(
    truth: np.ndarray,
    candidate_probs: np.ndarray,
    current_probs: np.ndarray | None = None,
    threshold: float = AUTO_THRESHOLD,
    tolerance: Tolerance | None = None,
) -> Decision:
    """Whether the candidate may replace the current model. No current model means promote."""
    tolerance = tolerance or Tolerance()
    truth = truth.astype(int)
    cand = _score(truth, candidate_probs, threshold)
    if current_probs is None:
        return Decision(True, ("no current model",), None, cand)

    cur = _score(truth, current_probs, threshold)
    reasons = []

    if cur["accuracy_at_threshold"] is not None:
        if cand["accuracy_at_threshold"] is None:
            reasons.append("candidate auto-assigns nothing at the threshold")
        elif cand["accuracy_at_threshold"] < cur["accuracy_at_threshold"] - tolerance.accuracy:
            reasons.append(
                f"accuracy at threshold fell from {cur['accuracy_at_threshold']:.3f} "
                f"to {cand['accuracy_at_threshold']:.3f}"
            )

    if cand["ece"] > cur["ece"] + tolerance.ece:
        reasons.append(f"ECE rose from {cur['ece']:.3f} to {cand['ece']:.3f}")

    return Decision(not reasons, tuple(reasons) or ("no regression",), cur, cand)


@dataclass
class Promoter[M]:
    """Holds the serving model. A rejected or failed candidate leaves it untouched."""

    threshold: float = AUTO_THRESHOLD
    tolerance: Tolerance = field(default_factory=Tolerance)
    current: M | None = None
    history: list[Decision] = field(default_factory=list)

    def consider(
        self, candidate: M, predict: Callable[[M], np.ndarray], truth: np.ndarray
    ) -> Decision:
        try:
            current_probs = predict(self.current) if self.current is not None else None
            decision = compare(
                truth, predict(candidate), current_probs, self.threshold, self.tolerance
            )
        except Exception as exc:
            log.exception("promotion check failed, keeping current model")
            decision = Decision(False, (f"evaluation failed: {exc}",), None, {})

        if decision.promote:
            self.current = candidate

        self.history.append(decision)
        log.info(
            "promotion %s: %s",
            "accepted" if decision.promote else "rejected",
            "; ".join(decision.reasons),
        )
        return decision
