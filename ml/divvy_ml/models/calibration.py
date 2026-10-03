"""Make confidence trustworthy: map the raw item confidence to the chance the prediction is right.

The raw joint confidence assumes people are independent, which is only roughly true, so models like
kNN report 0.9 when they are right 75% of the time. A calibrator learns that mapping from data the
model did not train on, then auto-assign thresholds mean what they say.
"""

from collections.abc import Callable, Sequence
from typing import Protocol

import numpy as np
from sklearn.isotonic import IsotonicRegression
from sklearn.linear_model import LogisticRegression

from divvy_ml.eval.metrics import Confidence, confidence, decide, exact_correct
from divvy_ml.models.base import Model

HOLDOUT = 0.3
MIN_CALIBRATION_ROWS = 40
LOGIT_CLIP = 1e-4


class Calibrator(Protocol):
    def fit(self, conf: np.ndarray, correct: np.ndarray) -> None: ...

    def predict(self, conf: np.ndarray) -> np.ndarray: ...


class IsotonicCalibrator:
    """Monotone, non-parametric map from confidence to hit rate."""

    def __init__(self) -> None:
        self._model = IsotonicRegression(y_min=0.0, y_max=1.0, out_of_bounds="clip")

    def fit(self, conf: np.ndarray, correct: np.ndarray) -> None:
        self._model.fit(conf, correct)

    def predict(self, conf: np.ndarray) -> np.ndarray:
        return np.clip(self._model.predict(conf), 0.0, 1.0)


class PlattCalibrator:
    """Logistic curve on the logit of the confidence: smoother, needs fewer rows."""

    def __init__(self) -> None:
        self._model = LogisticRegression(C=100.0)

    def fit(self, conf: np.ndarray, correct: np.ndarray) -> None:
        self._model.fit(self._logit(conf), correct)

    def predict(self, conf: np.ndarray) -> np.ndarray:
        return self._model.predict_proba(self._logit(conf))[:, 1]

    @staticmethod
    def _logit(conf: np.ndarray) -> np.ndarray:
        clipped = np.clip(conf, LOGIT_CLIP, 1 - LOGIT_CLIP)
        return np.log(clipped / (1 - clipped)).reshape(-1, 1)


class CalibratedModel:
    """Wraps a model so its item confidence is calibrated on the most recent history.

    On every fit the newest HOLDOUT of the history is held back, the model is fit on the rest,
    its predictions on the held-back rows teach the calibrator, and then the model is refit on
    everything. Too little history leaves the raw confidence untouched.
    """

    def __init__(
        self,
        base: Model,
        calibrator: Callable[[], Calibrator] = IsotonicCalibrator,
        holdout: float = HOLDOUT,
    ) -> None:
        self.name = f"{base.name}-calibrated"
        self._base = base
        self._make_calibrator = calibrator
        self._holdout = holdout
        self._calibrator: Calibrator | None = None
        self.holdout: dict[str, float | int] | None = None

    @property
    def calibrated(self) -> bool:
        return self._calibrator is not None

    def fit(self, items: Sequence[str], labels: np.ndarray, known: np.ndarray) -> None:
        self._calibrator = None
        self.holdout = None
        cut = int(len(items) * (1 - self._holdout))
        if len(items) - cut >= MIN_CALIBRATION_ROWS and cut > 0:
            self._base.fit(items[:cut], labels[:cut], known[:cut])
            self._calibrator = self._learn(items[cut:], labels[cut:], known[cut:])

        self._base.fit(items, labels, known)

    def predict(self, items: Sequence[str]) -> np.ndarray:
        return self._base.predict(items)

    def familiarity(self, items: Sequence[str]) -> np.ndarray | None:
        base = getattr(self._base, "familiarity", None)
        return base(items) if base else None

    def item_confidence(
        self, probs: np.ndarray, pred: np.ndarray, known: np.ndarray | None = None
    ) -> np.ndarray:
        raw = confidence(probs, pred, Confidence.JOINT, known)
        return raw if self._calibrator is None else self._calibrator.predict(raw)

    def _learn(
        self, items: Sequence[str], labels: np.ndarray, known: np.ndarray
    ) -> Calibrator | None:
        probs = self._base.predict(items)
        pred = decide(probs, known=known)
        correct = exact_correct(labels, pred, known)
        self.holdout = {"rows": len(items), "exact_match": float(correct.mean())}
        if correct.min() == correct.max():
            return None

        calibrator = self._make_calibrator()
        calibrator.fit(confidence(probs, pred, Confidence.JOINT, known), correct)
        return calibrator
