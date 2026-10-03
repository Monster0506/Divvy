"""Multi-label ownership metrics: decisions, confidence, calibration (ECE) and reliability bins.

Arrays have one row per item and one column per person, in the order of `people`. An optional
`known` mask marks cells where the person was in the household; other cells are never scored.
"""

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from enum import StrEnum
from typing import Any

import numpy as np

ItemConfidence = Callable[[np.ndarray, np.ndarray, np.ndarray], np.ndarray]
DECISION_THRESHOLD = 0.5
EPSILON = 1e-9
CALIBRATION_BINS = 10


class Confidence(StrEnum):
    JOINT = "joint"  # P(predicted set | at least one owner), people independent
    WEAKEST = "weakest"  # certainty of the least certain person


@dataclass(frozen=True)
class ReliabilityBin:
    lower: float
    upper: float
    count: int
    mean_confidence: float
    observed_rate: float


def all_known(probs: np.ndarray) -> np.ndarray:
    return np.ones(probs.shape, dtype=bool)


def decide(
    probs: np.ndarray, threshold: float = DECISION_THRESHOLD, known: np.ndarray | None = None
) -> np.ndarray:
    """Threshold each person; an empty set becomes the most likely person.

    People outside the household (known is False) are never predicted.
    """
    known = all_known(probs) if known is None else known.astype(bool)
    allowed = np.where(known, probs, 0.0)
    pred = (allowed >= threshold).astype(int)
    empty = pred.sum(axis=1) == 0
    best = np.where(known, probs, -1.0)[empty].argmax(axis=1)
    pred[empty, best] = 1
    return pred


def confidence(
    probs: np.ndarray, pred: np.ndarray, rule: Confidence, known: np.ndarray | None = None
) -> np.ndarray:
    known = all_known(probs) if known is None else known.astype(bool)
    certainty = np.where(known, np.where(pred == 1, probs, 1 - probs), 1.0)
    if rule is Confidence.WEAKEST:
        return certainty.min(axis=1)

    nonempty = 1 - np.where(known, 1 - probs, 1.0).prod(axis=1)
    return certainty.prod(axis=1) / np.maximum(nonempty, EPSILON)


def exact_correct(
    truth: np.ndarray, pred: np.ndarray, known: np.ndarray | None = None
) -> np.ndarray:
    """1 where the predicted owner set matches the true set on every known person."""
    match = pred == truth.astype(int)
    if known is not None:
        match = match | ~known.astype(bool)

    return match.all(axis=1).astype(int)


def reliability_bins(
    conf: np.ndarray, outcome: np.ndarray, n_bins: int = CALIBRATION_BINS
) -> list[ReliabilityBin]:
    """Equal-width confidence bins with observed hit rate. Empty bins are left out."""
    if n_bins < 1:
        raise ValueError("n_bins must be at least 1")

    conf = np.asarray(conf, dtype=float).ravel()
    outcome = np.asarray(outcome, dtype=float).ravel()
    if conf.shape != outcome.shape:
        raise ValueError("confidence and outcome must have the same length")

    index = np.minimum((conf * n_bins).astype(int), n_bins - 1)
    bins = []
    for b in range(n_bins):
        hit = index == b
        if hit.any():
            bins.append(
                ReliabilityBin(
                    lower=b / n_bins,
                    upper=(b + 1) / n_bins,
                    count=int(hit.sum()),
                    mean_confidence=float(conf[hit].mean()),
                    observed_rate=float(outcome[hit].mean()),
                )
            )

    return bins


def expected_calibration_error(
    conf: np.ndarray, outcome: np.ndarray, n_bins: int = CALIBRATION_BINS
) -> float:
    """Weighted mean gap between confidence and observed hit rate across bins."""
    bins = reliability_bins(conf, outcome, n_bins)
    total = sum(b.count for b in bins)
    if not total:
        return 0.0

    return sum(b.count / total * abs(b.observed_rate - b.mean_confidence) for b in bins)


def report(
    truth: np.ndarray,
    probs: np.ndarray,
    people: Sequence[str],
    known: np.ndarray | None = None,
    item_confidence: ItemConfidence | None = None,
) -> dict[str, Any]:
    """Exact match, per-person precision and recall, F1, losses and calibration.

    `item_confidence(probs, pred, known)` replaces the raw joint confidence, e.g. a calibrated one.
    """
    truth = truth.astype(int)
    if truth.shape != probs.shape or truth.shape[1] != len(people):
        raise ValueError("truth and probs must be (items, len(people)) arrays")

    known = all_known(probs) if known is None else known.astype(bool)
    if known.shape != truth.shape:
        raise ValueError("known must have the same shape as truth")

    pred = decide(probs, known=known)
    tp = ((pred == 1) & (truth == 1) & known).sum(axis=0)
    fp = ((pred == 1) & (truth == 0) & known).sum(axis=0)
    fn = ((pred == 0) & (truth == 1) & known).sum(axis=0)

    per_person = {}
    for i, name in enumerate(people):
        precision = _ratio(tp[i], tp[i] + fp[i])
        recall = _ratio(tp[i], tp[i] + fn[i])
        per_person[name] = {
            "precision": precision,
            "recall": recall,
            "f1": _f1(precision, recall),
            "support": int((truth[:, i] * known[:, i]).sum()),
        }

    n = len(truth)
    cells = int(known.sum())
    micro_p = _ratio(tp.sum(), tp.sum() + fp.sum())
    micro_r = _ratio(tp.sum(), tp.sum() + fn.sum())
    clipped = np.clip(probs, EPSILON, 1 - EPSILON)
    correct = exact_correct(truth, pred, known)
    joint = (
        item_confidence(probs, pred, known)
        if item_confidence
        else confidence(probs, pred, Confidence.JOINT, known)
    )

    return {
        "n": n,
        "exact_match": float(correct.mean()) if n else 0.0,
        "hamming_loss": _ratio(((pred != truth) & known).sum(), cells),
        "micro_f1": _f1(micro_p, micro_r),
        "macro_f1": float(np.mean([m["f1"] for m in per_person.values()])) if people else 0.0,
        "brier": _ratio((((probs - truth) ** 2) * known).sum(), cells),
        "log_loss": _log_loss(truth, clipped, known),
        "ece": expected_calibration_error(probs[known], truth[known]),
        "item_ece": expected_calibration_error(joint, correct),
        "per_person": per_person,
    }


def _log_loss(truth: np.ndarray, clipped: np.ndarray, known: np.ndarray) -> float:
    loss = -(truth * np.log(clipped) + (1 - truth) * np.log(1 - clipped))
    return _ratio((loss * known).sum(), known.sum())


def _ratio(num: float, den: float) -> float:
    return float(num / den) if den else 0.0


def _f1(precision: float, recall: float) -> float:
    return _ratio(2 * precision * recall, precision + recall)
